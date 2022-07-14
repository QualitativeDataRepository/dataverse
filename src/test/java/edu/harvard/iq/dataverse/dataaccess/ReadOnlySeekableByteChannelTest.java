package edu.harvard.iq.dataverse.dataaccess;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ReadOnlySeekableByteChannelTest {

    public ReadOnlySeekableByteChannelTest() {
    }
    
    private static final Logger logger = Logger.getLogger(ReadOnlySeekableByteChannelTest.class.getCanonicalName());

    private File file = null;
    LocalROSBC channel; 
    
    @BeforeEach
    void setUp() {
        logger.info("Doing setup");
        file = new File("src/test/java/edu/harvard/iq/dataverse/dataaccess/cheese.txt");
        try {
            channel = new LocalROSBC(file.length());
        } catch (IOException e) {
            Assert.fail("Can't create ReadOnlySeekableByteChannel from cheese.txt");
        }
    }

    @Test
    void testReadRange() {
        long offset = 5000;
        try (FileInputStream fis = new FileInputStream(file)) {
            //The first 500 bytes
            byte[] orig1 = fis.readNBytes(500);
            long skipped = 0;
            while (skipped < (offset-500)) {
                skipped += fis.skip((offset-500) - skipped);
            }
            //5000-5499
            byte[] orig2 = fis.readNBytes(500);
            //5500-5999
            byte[] orig3 = fis.readNBytes(500);
            
            channel.openStreamAt(offset, false);

            
            ByteBuffer b = ByteBuffer.allocate(500);
            //5000-5499
            channel.read(b);
            byte[] fromStream = b.array();

            String orig = new String(orig2, StandardCharsets.UTF_8);
            String chan = new String(fromStream, StandardCharsets.UTF_8);
            assertEquals(orig, chan);
            
            //First 500
            channel.openStreamAt(0, false);
            b = ByteBuffer.allocate(500);
            channel.read(b);
            fromStream = b.array();
            orig = new String(orig1, StandardCharsets.UTF_8);
            chan = new String(fromStream, StandardCharsets.UTF_8);
            assertEquals(orig, chan);

            //5500-5999
            channel.position(offset+500);
            b = ByteBuffer.allocate(500);
            channel.read(b);
            fromStream = b.array();
            orig = new String(orig3, StandardCharsets.UTF_8);
            chan = new String(fromStream, StandardCharsets.UTF_8);
            assertEquals(orig, chan);
            

        } catch (IOException e) {
            Assert.fail("IOException in test: " + e.getLocalizedMessage());
        }
    }
    
    class LocalROSBC extends ReadOnlySeekableByteChannel {

        public LocalROSBC(long length) throws IOException {
            super(length);
            // TODO Auto-generated constructor stub
        }

        @Override
        protected InputStream getStreamWithRange(long openAt) throws IOException {
            try {
            FileInputStream fis = new FileInputStream("src/test/java/edu/harvard/iq/dataverse/dataaccess/cheese.txt");

                long skipped = 0;
                while (skipped < (openAt)) {
                    skipped += fis.skip(openAt - skipped);
                }
                return fis;
            } catch (Exception e) {
                logger.info(e.getLocalizedMessage());
            }
            return null;
        }

        @Override
        protected InputStream getStreamWithRange(long openAt, long end) throws IOException {
            InputStream is = getStreamWithRange(openAt);
            byte[] rangeBytes = is.readNBytes((int) (end-openAt));
            is.close();
            ByteArrayInputStream bais = new ByteArrayInputStream(rangeBytes);
            return bais;
            
        } 
}
}
