package edu.harvard.iq.dataverse.dataaccess;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.util.IOUtils;

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

public class S3ReadOnlySeekableByteChannel extends ReadOnlySeekableByteChannel implements SeekableByteChannel {

    private static final Logger logger = Logger.getLogger(S3ReadOnlySeekableByteChannel.class.getCanonicalName());

    private AmazonS3 s3client;
    private String bucketName;
    private String key;
    private S3Object s3Object = null;

    /**
     * Open or creates a file, returning a seekable byte channel
     *
     * @throws IOException
     *             if an I/O error occurs
     */
    public S3ReadOnlySeekableByteChannel(AmazonS3 s3client, String key, String bucketName, long length) throws IOException {
        super(length);
        this.s3client = s3client;
        this.key = key;
        this.bucketName = bucketName;
    }

    @Override
    public void close() throws IOException {
        if (s3Object != null && position < length) {
            if (!seq || (length - position < DEFAULT_BUFFER_SIZE)) {
                logger.fine("Draining " + ((seq ? length : posAtOpen + DEFAULT_BUFFER_SIZE) - position) + " bytes to avoid warning.");
                cumMgmtBytes += (seq ? length : posAtOpen + DEFAULT_BUFFER_SIZE) - position;
                IOUtils.drainInputStream(s3Object.getObjectContent());
            } else {
                logger.fine("Abort to avoid transfer of (" + (length - position) + ") bytes (or warning).");
                s3Object.getObjectContent().abort();
            }
        }
        super.close();
    }

    @Override
    protected InputStream getStreamWithRange(long openAt) throws IOException {
        GetObjectRequest rangeObjectRequest = new GetObjectRequest(bucketName, key).withRange(openAt);
        return getRequest(rangeObjectRequest);
    }

    private InputStream getRequest(GetObjectRequest rangeObjectRequest) throws IOException {
        s3Object = s3client
                .getObject(rangeObjectRequest);
        if (s3Object == null) {
            logger.warning("Unabled to get object " + key + " in bucket " + bucketName);
            throw new IOException("Unabled to get object " + key + " in bucket " + bucketName);
        }
        return s3Object.getObjectContent();
    }

    @Override
    protected InputStream getStreamWithRange(long openAt, long end) throws IOException {
        GetObjectRequest rangeObjectRequest = new GetObjectRequest(bucketName, key).withRange(openAt, end);
        return getRequest(rangeObjectRequest);
    }
}
