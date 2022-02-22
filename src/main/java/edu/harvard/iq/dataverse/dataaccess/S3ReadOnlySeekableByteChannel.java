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
    private long posAtOpen = 0;
    private long cumPos = 0;
    private long cumOffsets = 0;
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
        //cumPos+=(this.position-posAtOpen);
        logger.info("Reopening to go from " + this.position + " to " + position);
        long openAt = position;
        if(length - position < DEFAULT_BUFFER_SIZE) {
            openAt=length-DEFAULT_BUFFER_SIZE;
        }
        GetObjectRequest rangeObjectRequest = new GetObjectRequest(bucketName, key).withRange(openAt);
        s3Object = s3client
            .getObject(rangeObjectRequest);
        if(s3Object==null) {
            logger.info("GetObjectRequest failed, pos: " + position);
        }
        bufferedStream = new ExtBufferedInputStream(s3Object.getObjectContent(), DEFAULT_BUFFER_SIZE);
        rbc = Channels.newChannel(bufferedStream);
        posAtOpen=openAt;
        bufferedStream.mark(DEFAULT_BUFFER_SIZE);
        if(position!=openAt) {
            bufferedStream.skip(position-openAt);
        }
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

        long offset = targetPosition - position();
        if(offset<0) {
            //Can go backward as far as postAtOpen if that is still within the markLimit 
            if((offset> -(position-posAtOpen)) &&((position - posAtOpen) < DEFAULT_BUFFER_SIZE)) {
                try {
                bufferedStream.reset();
                
                bufferedStream.skip((position-posAtOpen)+offset);
                position += offset;
                logger.info("Skipped back : " + offset);
                //Count backward offsets in total
                cumOffsets-=offset;
                } catch (IOException io) {
                    logger.info("Couldn't reset: Offset " + offset + " - reopening stream");
                    openStreamAt(targetPosition);
                }

            } else {
                logger.info("Offset " + offset + " - reopening stream");
                openStreamAt(targetPosition);
            }
        } else if (offset > 0 && offset < Math.min(length-posAtOpen,DEFAULT_BUFFER_SIZE)) {
            long skipped = bufferedStream.skip(offset);
            if (skipped != offset) {
                long secondSkip = 0;
                if(skipped<offset) {
//                    logger.info("Trying second skip of : " + (offset-skipped));
//                    bufferedStream.getBytesInBufferAvailable();
                    secondSkip = bufferedStream.skip(offset-skipped);
                    
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
            cumOffsets+=offset;
            if(offset>100) {
               logger.info("Now positioned at " + position);
            }
        } else if (offset != 0) {
            logger.info("Offset " + offset + " - reopening stream");
            openStreamAt(targetPosition);
        }
        return this;
    }

    public int read(ByteBuffer dst) throws IOException {
        int n = rbc.read(dst);
        if (n > 0) {
            position += n;
        }
        cumPos+=n;
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
        logger.info("Close called. cumPos: " + cumPos + ", cum. offsets: " + cumOffsets);
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
            logger.info("Count: " + this.count + " Pos: " + this.pos);
            //logger.info("Avail: " + avail);
            return avail;

//            logger.info("Pos: " + this.pos);
//            logger.info("Len: " + this.buf.length);
//            return this.buf.length - this.pos;
//            if (this.count == this.pos) return 0;
//            else return this.buf.length - this.pos;
        }
    }
}
