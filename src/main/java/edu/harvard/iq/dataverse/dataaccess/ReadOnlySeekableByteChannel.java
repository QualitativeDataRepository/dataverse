package edu.harvard.iq.dataverse.dataaccess;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.logging.Logger;

//Significantly modified from https://github.com/tomwhite/Amazon-S3-FileSystem-NIO2 (MIT license)

public abstract class ReadOnlySeekableByteChannel implements SeekableByteChannel {

    private static final Logger logger = Logger.getLogger(ReadOnlySeekableByteChannel.class.getCanonicalName());
    private static final int DEFAULT_BUFFER_SIZE = 512;
    private static final int MINIMUM_LOOK_BACK = 0;

    protected long length;
    private ExtBufferedInputStream bufferedStream;
    private ReadableByteChannel rbc;
    protected long position = -1;
    protected long posAtOpen = 0;
    private long cumPos = 0;
    private long cumOffsets = 0;
    protected long cumMgmtBytes = 0;
    protected boolean seq = false;

    /**
     * Open or creates a file, returning a seekable byte channel
     *
     * @throws IOException
     *             if an I/O error occurs
     */
    public ReadOnlySeekableByteChannel(long length) throws IOException {
        this.length = length;

        // openStreamAt(0);
    }

    private void openStreamAt(long targetPosition, boolean sequentialAccess) throws IOException {
        if (rbc != null) {
            close();
        }
        long openAt = Math.max(0, (sequentialAccess ? targetPosition: Math.min(length - DEFAULT_BUFFER_SIZE, targetPosition - MINIMUM_LOOK_BACK)));

        logger.fine((this.position == -1 ? "Opening" : "Reopening") + " at " + openAt + " to get to new position " + targetPosition + " for " + (sequentialAccess ? "seq access" : " random access"));
        InputStream newStream=null;
        if (!sequentialAccess) {
            newStream = getStreamWithRange(openAt, openAt + DEFAULT_BUFFER_SIZE);
        } else {
            newStream = getStreamWithRangeWithRange(openAt);
        }

        if (newStream == null) {
            logger.warning("Unabled to get inputstream starting at position " + openAt);
            throw new IOException("Unabled to get object inputstream starting at position " + openAt);
        }
        bufferedStream = new ExtBufferedInputStream(newStream, DEFAULT_BUFFER_SIZE);
        rbc = Channels.newChannel(bufferedStream);
        posAtOpen = openAt;
        bufferedStream.mark(DEFAULT_BUFFER_SIZE);
        if (targetPosition != openAt) {
            bufferedStream.loopUntilSkipped(targetPosition - openAt);
        }
        seq = sequentialAccess;
        cumMgmtBytes += targetPosition - openAt;
        this.position = targetPosition;
    }

    abstract protected InputStream getStreamWithRangeWithRange(long openAt) throws IOException;

    abstract protected InputStream getStreamWithRange(long openAt, long length) throws IOException;

    public boolean isOpen() {
        return rbc.isOpen();
    }

    public long position() throws IOException {
        return position;
    }

    public SeekableByteChannel position(long targetPosition)
            throws IOException {
        if (position == -1) {
            //When first opening assume seq access if starting at 0 (not true for Zip files)
            openStreamAt(targetPosition, ((targetPosition == 0) ? true : false));
        } else {
            long offset = targetPosition - position();
            if (offset < 0) {
                // Can go backward as far as postAtOpen if that is still within the markLimit
                if ((offset > -(position - posAtOpen)) && ((position - posAtOpen) < DEFAULT_BUFFER_SIZE)) {
                    try {
                        // To go back, reset and go forward from posAtOpen
                        bufferedStream.reset();
                        if (bufferedStream.loopUntilSkipped((position - posAtOpen) + offset)) {
                            // Since the bytes are guaranteed to be in the buffer, it should never block -
                            // warn if it ever does
                            logger.warning("Skipped less than expected when seeking backward through BufferedInputStream");
                        }
                        position += offset;
                        logger.fine("Skipped back : " + offset);
                        // Lower total when backing up since that doesn't add to bytes streamed
                        cumOffsets += offset;
                    } catch (IOException io) {
                        logger.warning("Couldn't reset: Offset " + offset + " from " + position + " - need to reopen stream");
                        openStreamAt(targetPosition, false);
                    }
                } else {
                    logger.fine("Offset " + offset + " from " + position + " - need to reopen stream");
                    openStreamAt(targetPosition, false);
                }
            } else if (offset > 0 && offset < DEFAULT_BUFFER_SIZE - (position - posAtOpen)) {
                try {
                    bufferedStream.loopUntilSkipped(offset);
                    position += offset;
                    cumOffsets += offset;
                    if (offset > 100) {
                        logger.fine("Now positioned at " + position);
                    }
                } catch (IOException io) {
                    logger.warning("Skip Failed, reopening stream: " + io.getLocalizedMessage());
                    openStreamAt(targetPosition, false);
                }
            } else if (offset != 0) {
                // Could decide to allow skipping in seq access case for offset > buffer size
                logger.fine("Offset " + offset + " from " + position + " - need to reopen stream");
                openStreamAt(targetPosition, false);
            }
        }
        return this;
    }

    public int read(ByteBuffer dst) throws IOException {
        int n = rbc.read(dst);
        if (n > 0) {
            position += n;
            // Pre-emptive reopen if read all bytes
            if (!seq && position == posAtOpen + DEFAULT_BUFFER_SIZE) {
                logger.fine("Read existing bytes, reopening at " + position);
                openStreamAt(position, true);
            }
        } else if (n == -1 && !seq && !(position == length)) {
            // skip has used all bytes
            logger.fine("Read all bytes at " + position);
            openStreamAt(position, true);
            n = read(dst);
            
        }
        cumPos += n;
        return n;
    }

    public SeekableByteChannel truncate(long size)
            throws IOException {
        throw new NonWritableChannelException();
    }

    public int write(ByteBuffer src) throws IOException {
        throw new NonWritableChannelException();
    }

    public long size() throws IOException {
        return length;
    }

    public void close() throws IOException {
        logger.fine("Close called. cumPos: " + cumPos + ", cum. offsets: " + cumOffsets + ", overhead: " + cumMgmtBytes);

        rbc.close();
    }

    private class ExtBufferedInputStream extends BufferedInputStream {
        private ExtBufferedInputStream(final InputStream inputStream, final int i) {
            super(inputStream, i);
        }

        // ~Emulates Java 12+ bufferedStream.skipNBytes(offset);
        private boolean loopUntilSkipped(long offset) throws IOException {
            long skipped = 0;
            boolean hadToRepeat = false;
            while (skipped < offset) {
                skipped += this.skip(offset - skipped);
                if (skipped < offset) {
                    hadToRepeat = true;
                }
                if (skipped == 0) {
                    throw new IOException("Can't skip far enough");
                }
            }
            return hadToRepeat;
        }
    }
}
