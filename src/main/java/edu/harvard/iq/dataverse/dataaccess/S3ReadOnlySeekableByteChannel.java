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
            rbc.close();
        }
        GetObjectRequest rangeObjectRequest = new GetObjectRequest(bucketName, key).withRange(position);
        S3Object s3Object = s3client
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
        logger.info("Pos: " + position);
        logger.info("TargetPos: " + targetPosition);
        
        long offset = targetPosition - position();
        if (offset > 0 && offset < bufferedStream.getBytesInBufferAvailable()) {
            logger.info("offset: " + offset);
            long skipped = bufferedStream.skip(offset);
            if (skipped != offset) {
                logger.info("skipped: " + skipped);
                openStreamAt(targetPosition);
                // shouldn't happen since we are within the buffer
                // throw new IOException("Could not seek to " + targetPosition);
            }
            position += offset;
        } else if (offset != 0) {
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
        rbc.close();
    }

    private class ExtBufferedInputStream extends BufferedInputStream {
        private ExtBufferedInputStream(final InputStream inputStream, final int i) {
            super(inputStream, i);
        }

        /** Returns the number of bytes that can be read from the buffer without reading more into the buffer. */
        int getBytesInBufferAvailable() {
            logger.info("Count: " + this.count);
            logger.info("Pos: " + this.pos);
            logger.info("Len: " + this.buf.length);
            if (this.count == this.pos) return 0;
            else return this.buf.length - this.pos;
        }
    }
}
