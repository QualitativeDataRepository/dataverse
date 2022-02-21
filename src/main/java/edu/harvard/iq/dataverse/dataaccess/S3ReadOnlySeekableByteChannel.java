package edu.harvard.iq.dataverse.dataaccess;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.logging.Logger;

//Modified from https://github.com/tomwhite/Amazon-S3-FileSystem-NIO2 (MIT license)

public class S3ReadOnlySeekableByteChannel implements SeekableByteChannel {

    private static final Logger logger = Logger.getLogger(S3ReadOnlySeekableByteChannel.class.getCanonicalName());
    private static final int DEFAULT_BUFFER_SIZE = 64000;

    private AmazonS3 s3client;
    private String bucketName;
    private String key;
    private long length;
    private ExtBufferedInputStream bufferedStream;
    private ReadableByteChannel rbc;
    private long position = 0;
    private long cumPos = 0;
    private S3Object s3Object=null;

    /**
     * Open or creates a file, returning a seekable byte channel
     *
     * @throws IOException if an I/O error occurs
     */
    public S3ReadOnlySeekableByteChannel(AmazonS3 s3client, String key, String bucketName, long length) throws IOException {
        this.s3client = s3client;
        this.key=key;
        this.bucketName=bucketName;
        this.length = length;

        openStreamAt(0);
    }

    private void openStreamAt(long position) throws IOException {
        if (rbc != null) {
            close();
        }
        cumPos+=this.position;
        logger.info("Reopening to go from " + this.position + " to " + position);
        GetObjectRequest rangeObjectRequest = new GetObjectRequest(bucketName, key).withRange(position);
        s3Object = s3client
            .getObject(rangeObjectRequest);
        if(s3Object==null) {
            logger.info("GetObjectRequest failed, pos: " + position);
        }
        bufferedStream = new ExtBufferedInputStream(s3Object.getObjectContent(), DEFAULT_BUFFER_SIZE);
        rbc = Channels.newChannel(bufferedStream);
        this.position = position;
    }

    public boolean isOpen() {
        return rbc.isOpen();
    }

    public long position() throws IOException {
        return position;
    }

    public SeekableByteChannel position(long targetPosition)
        throws IOException
    {
        //logger.info(position + " going to " +targetPosition);
        try {
            bufferedStream.getBytesInBufferAvailable();
        } catch(IOException io) {
            logger.info(io.getMessage());
            //Do nothing - 0 triggers reopening stream
        }
        long offset = targetPosition - position();
        //logger.info("offset: " + offset);
        if (offset > 0 && offset < bufferedStream.getBytesInBufferAvailable()) {
            long skipped = bufferedStream.skip(offset);
            if (skipped != offset) {
                long secondSkip = 0;
                if(skipped<offset) {
                    logger.info("Trying second skip of : " + (offset-skipped));
                    secondSkip = bufferedStream.skip(offset-skipped);
                    bufferedStream.getBytesInBufferAvailable();
                }
                if(skipped+secondSkip != offset) {
                logger.info("SKIP TOO SMALL: " + skipped+secondSkip);
                openStreamAt(targetPosition);
                }
                // shouldn't happen since we are within the buffer
                // throw new IOException("Could not seek to " + targetPosition);
/*Java 12+
                bufferedStream.skipNBytes(offset);
            } catch (Exception e) {
                logger.warning("Exception while skipping: " + e.getMessage());
                openStreamAt(targetPosition);
            }
            */
            }
            position += offset;
            if(offset>100) {
               logger.info("Now positioned at " + position);
            }
        } else if (offset != 0) {
            logger.info("Offset" + offset + " - reopening stream");
            openStreamAt(targetPosition);
        }
        return this;
    }

    public int read(ByteBuffer dst) throws IOException {
        int n = rbc.read(dst);
        if (n > 0) {
            position += n;
        }
        return n;
    }

    public SeekableByteChannel truncate(long size)
        throws IOException
    {
        throw new NonWritableChannelException();
    }

    public int write (ByteBuffer src) throws IOException {
        throw new NonWritableChannelException();
    }

    public long size() throws IOException {
        return length;
    }

    public void close() throws IOException {
        logger.info("Close called. cumPos: " + cumPos);
        if(s3Object!=null && position<length) {
            logger.info("Abort to avoid transfer of (" + (length-position) + ") bytes (or warning).");
            s3Object.getObjectContent().abort();
        }
        rbc.close();
    }

    private class ExtBufferedInputStream extends BufferedInputStream {
        private ExtBufferedInputStream(final InputStream inputStream, final int i) {
            super(inputStream, i);
        }

        /** Returns the number of bytes that can be read from the buffer without reading more into the buffer. 
         * @throws IOException */
        int getBytesInBufferAvailable() throws IOException {
            int avail = available();
            //logger.info("Avail: " + avail);
            return avail;
//            logger.info("Count: " + this.count);
//            logger.info("Pos: " + this.pos);
//            logger.info("Len: " + this.buf.length);
//            return this.buf.length - this.pos;
//            if (this.count == this.pos) return 0;
//            else return this.buf.length - this.pos;
        }
    }
}
