package edu.harvard.iq.dataverse.dataaccess;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsServiceClientConfiguration;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.DvObject;
import edu.harvard.iq.dataverse.datavariable.DataVariable;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.FileUtil;
import opennlp.tools.util.StringUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.validation.constraints.NotNull;

/**
 *
 * @author Matthew A Dunlap
 * @author Sarah Ferry
 * @author Rohit Bhattacharjee
 * @author Brian Silverstein
 * @param <T> what it stores
 */
/*
 * Amazon AWS S3 driver
 */
public class S3AccessIO<T extends DvObject> extends StorageIO<T> {

    @Resource(name = "java:comp/env/concurrent/s3UploadExecutor")
    private ManagedExecutorService executorService;

    private static final Config config = ConfigProvider.getConfig();
    private static final Logger logger = Logger.getLogger("edu.harvard.iq.dataverse.dataaccess.S3AccessIO");
    static final String URL_EXPIRATION_MINUTES = "url-expiration-minutes";
    static final String CUSTOM_ENDPOINT_URL = "custom-endpoint-url";
    static final String PROXY_URL = "proxy-url";
    static final String BUCKET_NAME = "bucket-name";
    static final String MIN_PART_SIZE = "min-part-size";
    static final String CUSTOM_ENDPOINT_REGION = "custom-endpoint-region";
    static final String PATH_STYLE_ACCESS = "path-style-access";
    static final String PAYLOAD_SIGNING = "payload-signing";
    static final String CHUNKED_ENCODING = "chunked-encoding";
    static final String PROFILE = "profile";

    private boolean mainDriver = true;

    private static HashMap<String, S3Client> driverClientMap = new HashMap<String, S3Client>();
    private static HashMap<String, AwsCredentialsProvider> driverCredentialsProviderMap = new HashMap<String, AwsCredentialsProvider>();
    private static HashMap<String, S3TransferManager> driverTMMap = new HashMap<String, S3TransferManager>();

    public S3AccessIO(T dvObject, DataAccessRequest req, String driverId) {
        super(dvObject, req, driverId);
        this.setIsLocalFile(false);

        try {
            bucketName = getBucketName(driverId);
            minPartSize = getMinPartSize(driverId);
            credentialsProvider = getCredentialsProvider(driverId);
            s3 = getClient(driverId);
            tm = getTransferManager(driverId);
            endpoint = getConfigParam(CUSTOM_ENDPOINT_URL, "");
            proxy = getConfigParam(PROXY_URL, "");
            if (!StringUtil.isEmpty(proxy) && StringUtil.isEmpty(endpoint)) {
                logger.severe(driverId + " config error: Must specify a custom-endpoint-url if proxy-url is specified");
            }
            
            // FWIW: There used to be a check here to see if the bucket exists. It was
            // broken/didn't handle custom endpoints.
            // It was very redundant (checking every time we access any file) and didn't do
            // much but potentially make the failure (in the unlikely case a bucket doesn't
            // exist/just disappeared) happen slightly earlier (here versus at the first
            // file/metadata access).

        } catch (Exception e) {
            throw S3Exception.builder().message("Cannot instantiate a S3 client for " + bucketName + "; check your AWS credentials and region")
                    .cause(e).build();
        }
    }

    public S3AccessIO(String storageLocation, String driverId) {
        this(null, null, driverId);
        // TODO: validate the storage location supplied
        logger.fine("Instantiating with location: " + storageLocation);
        bucketName = storageLocation.substring(0, storageLocation.indexOf('/'));
        minPartSize = getMinPartSize(driverId);
        key = storageLocation.substring(storageLocation.indexOf('/') + 1);
    }

    // Used for tests only
    public S3AccessIO(T dvObject, DataAccessRequest req, @NotNull S3Client s3client, String driverId) {
        super(dvObject, req, driverId);
        bucketName = getBucketName(driverId);
        this.setIsLocalFile(false);
        this.s3 = s3client;
    }

    private S3Client s3 = null;
    private AwsCredentialsProvider credentialsProvider;
    private S3TransferManager tm = null;
    private String bucketName = null;
    private String key = null;
    private long minPartSize;
    private String endpoint = null;
    private String proxy = null;

    @Override
    public void open(DataAccessOption... options) throws IOException {
        if (s3 == null) {
            throw new IOException("ERROR: s3 not initialised. ");
        }

        DataAccessRequest req = this.getRequest();

        if (isWriteAccessRequested(options)) {
            isWriteAccess = true;
            isReadAccess = false;
        } else {
            isWriteAccess = false;
            isReadAccess = true;
        }

        if (dvObject instanceof DataFile) {
            String storageIdentifier = dvObject.getStorageIdentifier();

            DataFile dataFile = this.getDataFile();

            if (req != null && req.getParameter("noVarHeader") != null) {
                this.setNoVarHeader(true);
            }

            if (storageIdentifier == null || "".equals(storageIdentifier)) {
                throw new FileNotFoundException("Data Access: No local storage identifier defined for this datafile.");
            }

            // Fix new DataFiles: DataFiles that have not yet been saved may use this method
            // when they don't have their storageidentifier in the final
            // <driverId>://<bucketname>:<id> form
            // So we fix it up here. ToDo: refactor so that storageidentifier is generated
            // by the appropriate StorageIO class and is final from the start.
            String newStorageIdentifier = null;
            if (storageIdentifier.startsWith(this.driverId + DataAccess.SEPARATOR)) {
                if (!storageIdentifier.substring((this.driverId + DataAccess.SEPARATOR).length()).contains(":")) {
                    // Driver id but no bucket
                    if (bucketName != null) {
                        newStorageIdentifier = this.driverId + DataAccess.SEPARATOR + bucketName + ":"
                                + storageIdentifier.substring((this.driverId + DataAccess.SEPARATOR).length());
                    } else {
                        throw new IOException("S3AccessIO: DataFile (storage identifier " + storageIdentifier
                                + ") is not associated with a bucket.");
                    }
                } // else we're OK (assumes bucket name in storageidentifier matches the driver's
                  // bucketname)
            } else {
                if (!storageIdentifier.contains(":")) {
                    // No driver id or bucket
                    newStorageIdentifier = this.driverId + DataAccess.SEPARATOR + bucketName + ":" + storageIdentifier;
                } else {
                    // Just the bucketname
                    newStorageIdentifier = this.driverId + DataAccess.SEPARATOR + storageIdentifier;
                }
            }
            if (newStorageIdentifier != null) {
                // Fixup needed:
                storageIdentifier = newStorageIdentifier;
                dvObject.setStorageIdentifier(newStorageIdentifier);
            }

            if (isReadAccess) {
                this.setSize(retrieveSizeFromMedia());

                if (dataFile.getContentType() != null && dataFile.getContentType().equals("text/tab-separated-values")
                        && dataFile.isTabularData() && dataFile.getDataTable() != null && (!this.noVarHeader())
                        && (!dataFile.getDataTable().isStoredWithVariableHeader())) {

                    List<DataVariable> datavariables = dataFile.getDataTable().getDataVariables();
                    String varHeaderLine = generateVariableHeader(datavariables);
                    this.setVarHeader(varHeaderLine);
                }

            } else if (isWriteAccess) {
                key = dataFile.getOwner().getAuthorityForFileStorage() + "/"
                        + this.getDataFile().getOwner().getIdentifierForFileStorage();
                key += "/" + storageIdentifier.substring(storageIdentifier.lastIndexOf(":") + 1);
            }

            this.setMimeType(dataFile.getContentType());

            try {
                this.setFileName(dataFile.getFileMetadata().getLabel());
            } catch (Exception ex) {
                this.setFileName("unknown");
            }
        } else if (dvObject instanceof Dataset) {
            Dataset dataset = this.getDataset();
            key = dataset.getAuthorityForFileStorage() + "/" + dataset.getIdentifierForFileStorage();
            dataset.setStorageIdentifier(this.driverId + DataAccess.SEPARATOR + key);
        } else if (dvObject instanceof Dataverse) {
            throw new IOException("Data Access: Storage driver does not support dvObject type Dataverse yet");
        } else {
            if (isMainDriver()) {
                // Direct access, e.g. for external upload - no associated DVobject yet, but we
                // want to be able to get the size
                // With small files, it looks like we may call before S3 says it exists, so try
                // some retries before failing
                long contentLength = -1;
                int retries = 20;
                while (retries > 0) {
                    try {
                        // Since s3 is an S3AsyncClient, we need to call .get() to wait for the result.
                        HeadObjectResponse headObjectResponse = s3
                                .headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
                        contentLength = headObjectResponse.contentLength();
                        if (retries != 20) {
                            logger.warning("Success for key: " + key + " after " + ((20 - retries) * 3) + " seconds");
                        }
                        break;
                    } catch (Exception e) {
                        if (retries > 1) {
                            retries--;
                            try {
                                Thread.sleep(3000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                logger.warning("Thread interrupted while waiting to retry");
                            }
                            logger.warning("Retrying after: " + e.getMessage());
                        } else {
                            throw new IOException("Cannot get S3 object " + key + " (" + e.getMessage() + ")", e);
                        }
                    }
                }

                if (contentLength >= 0) {
                    this.setSize(contentLength);
                } else {
                    throw new IOException("Failed to retrieve content length for S3 object " + key);
                }
            }
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (super.getInputStream() == null) {
            ResponseInputStream<GetObjectResponse> responseInputStream;
            responseInputStream = s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build());
            setInputStream(responseInputStream);
        }
        if (super.getInputStream() == null) {
            throw new IOException("Cannot get InputStream for S3 Object" + key);
        }

        setChannel(Channels.newChannel(super.getInputStream()));

        return super.getInputStream();
    }

    @Override
    public Channel getChannel() throws IOException {
        if (super.getChannel() == null) {
            getInputStream();
        }
        return channel;
    }

    @Override
    public ReadableByteChannel getReadChannel() throws IOException {
        // Make sure StorageIO.channel variable exists
        getChannel();
        return super.getReadChannel();
    }

    // StorageIO method for copying a local Path (for ex., a temp file), into this
    // DataAccess location:
    @Override
    public void savePath(Path fileSystemPath) throws IOException {
        long newFileSize = -1;

        if (!this.canWrite()) {
            open(DataAccessOption.WRITE_ACCESS);
        }

        if (dvObject instanceof DataFile) {
            try {
                PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(bucketName).key(key).build();

                tm.uploadFile(
                        UploadFileRequest.builder().putObjectRequest(putObjectRequest).source(fileSystemPath).build())
                        .completionFuture().join();

                newFileSize = Files.size(fileSystemPath);
            } catch (Exception e) {
                throw new IOException(
                        "S3AccessIO: Exception occurred while uploading a local file into S3Object " + key, e);
            }
        } else {
            throw new IOException("DvObject type other than datafile is not yet supported");
        }

        // if it has uploaded successfully, we can reset the size
        // of the object:
        setSize(newFileSize);
    }

    @Override
    public void saveInputStream(InputStream inputStream, Long filesize) throws IOException {
        if (filesize == null || filesize < 0) {
            saveInputStream(inputStream);
        } else {
            saveInputStreamInternal(inputStream, filesize);
        }
    }

    @Override
    public void saveInputStream(InputStream inputStream) throws IOException {
        saveInputStreamInternal(inputStream, null);
    }

    private void saveInputStreamInternal(InputStream inputStream, Long filesize) throws IOException {
        if (!this.canWrite()) {
            open(DataAccessOption.WRITE_ACCESS);
        }

        File tempFile = null;
        try {
            PutObjectRequest.Builder putObjectRequestBuilder = PutObjectRequest.builder().bucket(bucketName).key(key);

            RequestBody requestBody;

            if (filesize != null) {
                putObjectRequestBuilder.contentLength(filesize);
                requestBody = RequestBody.fromInputStream(inputStream, filesize);
            } else {
                String directoryString = FileUtil.getFilesTempDirectory();
                Random rand = new Random();
                Path tempPath = Paths.get(directoryString, Integer.toString(rand.nextInt(Integer.MAX_VALUE)));
                tempFile = createTempFile(tempPath, inputStream);
                requestBody = RequestBody.fromFile(tempFile);
            }

            s3.putObject(putObjectRequestBuilder.build(), requestBody);

            if (filesize == null) {
                HeadObjectResponse headObjectResponse = s3
                        .headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
                setSize(headObjectResponse.contentLength());
            } else {
                setSize(filesize);
            }
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    @Override
    public void delete() throws IOException {
        if (!isDirectAccess()) {
            throw new IOException("Direct Access IO must be used to permanently delete stored file objects");
        }
        if (key == null) {
            throw new IOException("Delete called with null key");
        }
        // Verify that it exists, before we attempt to delete it?
        // (probably unnecessary - attempting to delete it will fail if it doesn't exist
        // - ?)
        DeleteObjectRequest deleteObjRequest = DeleteObjectRequest.builder().bucket(bucketName).key(key).build();
        s3.deleteObject(deleteObjRequest);

        // Delete all the cached aux files as well:
        deleteAllAuxObjects();
    }

    @Override
    public Channel openAuxChannel(String auxItemTag, DataAccessOption... options) throws IOException {
        if (isWriteAccessRequested(options)) {
            // Need size to write to S3
            throw new UnsupportedDataAccessOperationException(
                    "S3AccessIO: write mode openAuxChannel() not yet implemented in this storage driver.");
        }

        InputStream fin = getAuxFileAsInputStream(auxItemTag);

        if (fin == null) {
            throw new IOException("Failed to open auxilary file " + auxItemTag + " for S3 file");
        }

        return Channels.newChannel(fin);
    }

    @Override
    public boolean isAuxObjectCached(String auxItemTag) throws IOException {
        open();
        logger.fine("Inside isAuxObjectCached");
        String destinationKey = getDestinationKey(auxItemTag);

        HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket(bucketName).key(destinationKey)
                .build();

        s3.headObject(headObjectRequest);
        return true;
    }

    @Override
    public long getAuxObjectSize(String auxItemTag) throws IOException {
        open();
        String destinationKey = getDestinationKey(auxItemTag);
        HeadObjectResponse headObjectResponse = s3
                .headObject(HeadObjectRequest.builder().bucket(bucketName).key(destinationKey).build());
        return headObjectResponse.contentLength();
    }

    @Override
    public Path getAuxObjectAsPath(String auxItemTag) throws UnsupportedDataAccessOperationException {
        throw new UnsupportedDataAccessOperationException(
                "S3AccessIO: this is a remote DataAccess IO object, its Aux objects have no local filesystem Paths associated with it.");
    }

    @Override
    public void backupAsAux(String auxItemTag) throws IOException {
        String destinationKey = getDestinationKey(auxItemTag);

        CopyObjectRequest copyObjectRequest = CopyObjectRequest.builder().sourceBucket(bucketName).sourceKey(key)
                .destinationBucket(bucketName).destinationKey(destinationKey).build();

        s3.copyObject(copyObjectRequest);
    }

    @Override
    public void revertBackupAsAux(String auxItemTag) throws IOException {
        String destinationKey = getDestinationKey(auxItemTag);
        CopyObjectRequest copyObjectRequest = CopyObjectRequest.builder().sourceBucket(bucketName)
                .sourceKey(destinationKey).destinationBucket(bucketName).destinationKey(key).build();

        s3.copyObject(copyObjectRequest);
        deleteAuxObject(auxItemTag);
    }

    @Override
    public void savePathAsAux(Path fileSystemPath, String auxItemTag) throws IOException {
        if (!this.canWrite()) {
            open(DataAccessOption.WRITE_ACCESS);
        }
        String destinationKey = getDestinationKey(auxItemTag);
        PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(bucketName).key(destinationKey)
                .build();
        RequestBody requestBody = RequestBody.fromFile(fileSystemPath);
        s3.putObject(putObjectRequest, requestBody);
    }

    @Override
    public void saveInputStreamAsAux(InputStream inputStream, String auxItemTag, Long filesize) throws IOException {
        if (filesize == null || filesize < 0) {
            saveInputStreamAsAux(inputStream, auxItemTag);
        } else {
            if (!this.canWrite()) {
                open(DataAccessOption.WRITE_ACCESS);
            }
            String destinationKey = getDestinationKey(auxItemTag);
            PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(bucketName).key(destinationKey)
                    .contentLength(filesize).build();

            RequestBody requestBody = RequestBody.fromInputStream(inputStream, filesize);

            s3.putObject(putObjectRequest, requestBody);
        }
    }

    /**
     * Implements the StorageIO saveInputStreamAsAux() method. This implementation
     * is problematic, because S3 cannot save an object of an unknown length. This
     * effectively nullifies any benefits of streaming; as we cannot start saving
     * until we have read the entire stream. One way of solving this would be to
     * buffer the entire stream as byte[], in memory, then save it... Which of
     * course would be limited by the amount of memory available, and thus would not
     * work for streams larger than that. So we have eventually decided to save save
     * the stream to a temp file, then save to S3. This is slower, but guaranteed to
     * work on any size stream. An alternative we may want to consider is to not
     * implement this method in the S3 driver, and make it throw the
     * UnsupportedDataAccessOperationException, similarly to how we handle attempts
     * to open OutputStreams, in this and the Swift driver.
     * 
     * @param inputStream InputStream we want to save
     * @param auxItemTag  String representing this Auxiliary type ("extension")
     * @throws IOException if anything goes wrong.
     */
    @Override
    public void saveInputStreamAsAux(InputStream inputStream, String auxItemTag) throws IOException {
        if (!this.canWrite()) {
            open(DataAccessOption.WRITE_ACCESS);
        }

        String destinationKey = getDestinationKey(auxItemTag);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(bucketName).key(destinationKey).build();

        // Create a temporary file from the input stream
        File tempFile = null;
        try {
            String directoryString = FileUtil.getFilesTempDirectory();

            Random rand = new Random();
            String pathNum = Integer.toString(rand.nextInt(Integer.MAX_VALUE));
            Path tempPath = Paths.get(directoryString, pathNum);
            tempFile = createTempFile(tempPath, inputStream);

            // Create a RequestBody that reads from the temporary file
            RequestBody requestBody = RequestBody.fromFile(tempFile);

            // Use the async client to put the object
            s3.putObject(putObjectRequest, requestBody);
        } finally {
            // Close the input stream
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    logger.warning("Failed to close input stream: " + e.getMessage());
                }
            }
            // Delete the temporary file
            if (tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) {
                    logger.warning("Failed to delete temporary file: " + tempFile.getAbsolutePath());
                }
            }
        }
    }

    // Helper method for supporting saving streams with unknown length to S3
    // We save those streams to a file and then upload the file
    private File createTempFile(Path path, InputStream inputStream) throws IOException {

        File targetFile = new File(path.toUri()); // File needs a name
        try (OutputStream outStream = new FileOutputStream(targetFile);) {

            byte[] buffer = new byte[8 * 1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, bytesRead);
            }

        } finally {
            IOUtils.closeQuietly(inputStream);
        }
        return targetFile;
    }

    @Override
    public List<String> listAuxObjects() throws IOException {
        if (!this.canWrite()) {
            open();
        }
        String prefix = getDestinationKey("");

        List<String> ret = new ArrayList<>();
        ListObjectsV2Request listObjectsReqManual = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix)
                .build();

        ListObjectsV2Response listObjectsResponse = null;
        listObjectsResponse = s3.listObjectsV2(listObjectsReqManual);
        if (listObjectsResponse == null) {
            return ret;
        }

        List<S3Object> storedAuxFilesSummary = new ArrayList<>(listObjectsResponse.contents());

        String nextContinuationToken = listObjectsResponse.nextContinuationToken();
        while (nextContinuationToken != null) {
            logger.fine("S3 listAuxObjects: going to next page of list");
            ListObjectsV2Request nextReq = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix)
                    .continuationToken(nextContinuationToken).build();

            ListObjectsV2Response nextResponse = s3.listObjectsV2(nextReq);
            if (nextResponse != null) {
                storedAuxFilesSummary.addAll(nextResponse.contents());
                nextContinuationToken = nextResponse.nextContinuationToken();
            } else {
                nextContinuationToken = null;
            }
        }

        for (S3Object item : storedAuxFilesSummary) {
            String destinationKey = item.key();
            String fileName = destinationKey.substring(destinationKey.lastIndexOf(".") + 1);
            logger.fine("S3 cached aux object fileName: " + fileName);
            ret.add(fileName);
        }
        return ret;
    }

    @Override
    public void deleteAuxObject(String auxItemTag) throws IOException {
        if (!this.canWrite()) {
            open(DataAccessOption.WRITE_ACCESS);
        }
        String destinationKey = getDestinationKey(auxItemTag);
        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder().bucket(bucketName)
                .key(destinationKey).build();

        s3.deleteObject(deleteObjectRequest);
    }

    @Override
    public void deleteAllAuxObjects() throws IOException {
        if (!isDirectAccess() && !this.canWrite()) {
            open(DataAccessOption.WRITE_ACCESS);
        }

        String prefix = getDestinationKey("");

        List<S3Object> storedAuxFilesSummary = new ArrayList<>();
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix).build();

        ListObjectsV2Response listResponse;
        do {
            listResponse = s3.listObjectsV2(listRequest);
            storedAuxFilesSummary.addAll(listResponse.contents());

            listRequest = listRequest.toBuilder().continuationToken(listResponse.nextContinuationToken()).build();
        } while (listResponse.isTruncated());

        if (storedAuxFilesSummary.isEmpty()) {
            logger.fine("S3AccessIO: No auxiliary objects to delete.");
            return;
        }

        List<ObjectIdentifier> objectsToDelete = storedAuxFilesSummary.stream()
                .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build()).collect(Collectors.toList());

        Delete delete = Delete.builder().objects(objectsToDelete).quiet(true).build();

        DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder().bucket(bucketName).delete(delete).build();

        logger.fine("Trying to delete auxiliary files...");
        s3.deleteObjects(deleteRequest);
    }

    @Override
    public String getStorageLocation() throws IOException {
        String locationKey = getMainFileKey();

        if (locationKey == null) {
            throw new IOException("Failed to obtain the S3 key for the file");
        }

        return this.driverId + DataAccess.SEPARATOR + bucketName + "/" + locationKey;
    }

    @Override
    public Path getFileSystemPath() throws UnsupportedDataAccessOperationException {
        throw new UnsupportedDataAccessOperationException(
                "S3AccessIO: this is a remote DataAccess IO object, it has no local filesystem path associated with it.");
    }

    @Override
    public boolean exists() {
        try {
            key = getMainFileKey();
        } catch (IOException e) {
            logger.warning("Caught an IOException in S3AccessIO.exists(): " + e.getMessage());
            return false;
        }
        String destinationKey = null;
        if (dvObject instanceof DataFile) {
            destinationKey = key;
        } else if ((dvObject == null) && (key != null)) {
            // direct access
            destinationKey = key;
        } else {
            logger.warning("Trying to check if a path exists is only supported for a data file.");
        }
        HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket(bucketName).key(destinationKey)
                .build();

        s3.headObject(headObjectRequest);
        return true;
    }

    @Override
    public WritableByteChannel getWriteChannel() throws UnsupportedDataAccessOperationException {
        throw new UnsupportedDataAccessOperationException(
                "S3AccessIO: there are no write Channels associated with S3 objects.");
    }

    @Override
    public OutputStream getOutputStream() throws UnsupportedDataAccessOperationException {
        throw new UnsupportedDataAccessOperationException(
                "S3AccessIO: there are no output Streams associated with S3 objects.");
    }

    @Override
    public InputStream getAuxFileAsInputStream(String auxItemTag) throws IOException {
        String destinationKey = getDestinationKey(auxItemTag);
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucketName).key(destinationKey)
                .build();

        ResponseInputStream<GetObjectResponse> s3ObjectContent = s3
                .getObject(getObjectRequest, ResponseTransformer.toInputStream());
        if (s3ObjectContent != null) {
            return s3ObjectContent;
        }
        return null;

    }

    // Rename this getAuxiliaryKey(), maybe?
    String getDestinationKey(String auxItemTag) throws IOException {
        if (isDirectAccess() || dvObject instanceof DataFile) {
            return getMainFileKey() + "." + auxItemTag;
        } else if (dvObject instanceof Dataset) {
            if (key == null) {
                open();
            }
            return key + "/" + auxItemTag;
        } else {
            throw new IOException("S3AccessIO: This operation is only supported for Datasets and DataFiles.");
        }
    }

    /**
     * TODO: this function is not side effect free (sets instance variables key and
     * bucketName). Is this good or bad? Need to ask @landreev
     *
     * Extract the file key from a file stored on S3. Follows template: "owner
     * authority name"/"owner identifier"/"storage identifier without bucketname and
     * protocol"
     * 
     * @return Main File Key
     * @throws IOException
     */
    String getMainFileKey() throws IOException {
        if (key == null) {
            DataFile df = this.getDataFile();
            // TODO: (?) - should we worry here about the datafile having null for the owner
            // here?
            key = getMainFileKey(df.getOwner(), df.getStorageIdentifier(), driverId);
        }
        return key;
    }

    static String getMainFileKey(Dataset owner, String storageIdentifier, String driverId) throws IOException {

        // or about the owner dataset having null for the authority and/or identifier?
        // we should probably check for that and throw an exception. (unless we are
        // super positive that this condition would have been intercepted by now)
        String baseKey = owner.getAuthorityForFileStorage() + "/" + owner.getIdentifierForFileStorage();
        return getMainFileKey(baseKey, storageIdentifier, driverId);
    }

    private static String getMainFileKey(String baseKey, String storageIdentifier, String driverId) throws IOException {
        String key = null;
        if (storageIdentifier == null || "".equals(storageIdentifier)) {
            throw new FileNotFoundException("Data Access: No local storage identifier defined for this datafile.");
        }

        if (storageIdentifier.indexOf(driverId + DataAccess.SEPARATOR) >= 0) {
            // String driverId = storageIdentifier.substring(0,
            // storageIdentifier.indexOf("://")+3);
            // As currently implemented (v4.20), the bucket is part of the identifier and we
            // could extract it and compare it with getBucketName() as a check -
            // Only one bucket per driver is supported (though things might work if the
            // profile creds work with multiple buckets, then again it's not clear when
            // logic is reading from the driver property or from the DataFile).
            // String bucketName = storageIdentifier.substring(driverId.length() + 3,
            // storageIdentifier.lastIndexOf(":"));
            key = baseKey + "/" + storageIdentifier.substring(storageIdentifier.lastIndexOf(":") + 1);
        } else {
            throw new IOException("S3AccessIO: DataFile (storage identifier " + storageIdentifier
                    + ") does not appear to be an S3 object associated with driver: " + driverId);
        }
        return key;
    }

    @Override
    public boolean downloadRedirectEnabled() {
        String optionValue = getConfigParam(DOWNLOAD_REDIRECT);
        if ("true".equalsIgnoreCase(optionValue)) {
            return true;
        }
        return false;
    }

    public boolean downloadRedirectEnabled(String auxObjectTag) {
        return downloadRedirectEnabled();
    }

    /**
     * Generates a temporary URL for a direct S3 download; either for the main
     * physical file, or (optionally) for an auxiliary.
     * 
     * @param auxiliaryTag      (optional)
     * @param auxiliaryType     (optional) - aux. mime type, if different from the
     *                          main type
     * @param auxiliaryFileName (optional) - file name, if different from the main
     *                          file label.
     * @return redirect url
     * @throws IOException.
     */
    public String generateTemporaryDownloadUrl(String auxiliaryTag, String auxiliaryType, String auxiliaryFileName)
            throws IOException {
        if (s3 == null) {
            throw new IOException("ERROR: s3 not initialised. ");
        }
        if (dvObject instanceof DataFile) {
            String key = auxiliaryTag == null ? getMainFileKey() : getDestinationKey(auxiliaryTag);
            Duration expirationDuration = Duration.ofMinutes(getUrlExpirationMinutes());

            String fileName = auxiliaryFileName == null ? this.getDataFile().getDisplayName() : auxiliaryFileName;
            String contentType = auxiliaryType == null ? this.getDataFile().getContentType() : auxiliaryType;

            // Create S3Presigner
            S3Presigner s3Presigner = S3Presigner.builder()
                    .region(Region.of(s3.serviceClientConfiguration().region().toString()))
                    .credentialsProvider(credentialsProvider).build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(expirationDuration)
                    .getObjectRequest(req -> req.bucket(bucketName).key(key)
                            .responseContentDisposition("attachment; filename*=UTF-8''"
                                    + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20"))
                            .responseContentType(contentType))
                    .build();

            PresignedGetObjectRequest presignedRequest;
            try {
                presignedRequest = s3Presigner.presignGetObject(presignRequest);
            } catch (S3Exception e) {
                logger.warning("Exception generating temporary S3 url for " + key + " (" + e.getMessage() + ")");
                return null;
            } finally {
                s3Presigner.close();
            }

            if (presignedRequest != null) {
                String urlString = presignedRequest.url().toString();
                if (!StringUtil.isEmpty(proxy)) {
                    String endpointServer = endpoint;
                    int protocolEnd = endpoint.indexOf(DataAccess.SEPARATOR);
                    if (protocolEnd >= 0) {
                        endpointServer = endpoint.substring(protocolEnd + DataAccess.SEPARATOR.length());
                    }
                    logger.fine("Endpoint: " + endpointServer);
                    logger.fine("Original Url: " + urlString);
                    String finalUrl = urlString.replaceFirst("http[s]*:\\/\\/([^\\/]+\\.)" + endpointServer, proxy);
                    logger.fine("ProxiedURL: " + finalUrl);
                    return finalUrl;
                } else {
                    return urlString;
                }
            }

            return null;
        } else if (dvObject instanceof Dataset) {
            throw new IOException("Data Access: GenerateTemporaryS3Url: Invalid DvObject type : Dataset");
        } else if (dvObject instanceof Dataverse) {
            throw new IOException("Data Access: GenerateTemporaryS3Url: Invalid DvObject type : Dataverse");
        } else {
            throw new IOException("Data Access: GenerateTemporaryS3Url: Unknown DvObject type");
        }
    }

    private String generateTemporaryS3UploadUrl(String key, Date expiration) throws IOException {
        if (s3 == null) {
            throw new IOException("ERROR: s3 not initialised. ");
        }

        Duration expirationDuration = Duration.between(Instant.now(), expiration.toInstant());

        // Create S3Presigner
        S3Presigner s3Presigner = S3Presigner.builder()
                .region(Region.of(s3.serviceClientConfiguration().region().toString()))
                .credentialsProvider(credentialsProvider).build();
        logger.info("Bucket when signing = " + bucketName);
        PutObjectPresignRequest.Builder presignRequestBuilder = PutObjectPresignRequest.builder()
                .signatureDuration(expirationDuration);

        // Add tagging if not disabled
        final boolean taggingDisabled = JvmSettings.DISABLE_S3_TAGGING.lookupOptional(Boolean.class, this.driverId)
                .orElse(false);
        if (!taggingDisabled) {
            presignRequestBuilder.putObjectRequest(req -> req.tagging("dv-state=temp").bucket(bucketName).key(key));
        } else {
            presignRequestBuilder.putObjectRequest(req -> req.bucket(bucketName).key(key));
        }
        PutObjectPresignRequest presignRequest = presignRequestBuilder.build();

        PresignedPutObjectRequest presignedRequest;
        try {
            presignedRequest = s3Presigner.presignPutObject(presignRequest);
        } catch (S3Exception e) {
            logger.warning("Exception generating temporary S3 upload url for " + key + " (" + e.getMessage() + ")");
            return null;
        } finally {
            s3Presigner.close();
        }

        String urlString = presignedRequest.url().toString();

        if (!StringUtil.isEmpty(proxy)) {
            String endpointServer = endpoint;
            int protocolEnd = endpoint.indexOf(DataAccess.SEPARATOR);
            if (protocolEnd >= 0) {
                endpointServer = endpoint.substring(protocolEnd + DataAccess.SEPARATOR.length());
            }
            logger.fine("Endpoint: " + endpointServer);
            // We're then replacing
            // http or https followed by :// and an optional <bucketname>. before the
            // normalized endpoint url
            // with the proxy info (which is protocol + machine name and optional port)
            urlString = urlString.replaceFirst("http[s]*:\\/\\/([^\\/]+\\.)" + endpointServer, proxy);
            logger.fine("ProxiedURL: " + urlString);
        }

        return urlString;
    }

    public JsonObjectBuilder generateTemporaryS3UploadUrls(String globalId, String storageIdentifier, long fileSize)
            throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        key = getMainFileKey();
        Instant expiration = Instant.now().plus(Duration.ofMinutes(getUrlExpirationMinutes()));

        if (fileSize <= minPartSize) {
            response.add("url", generateTemporaryS3UploadUrl(key, Date.from(expiration)));
        } else {
            JsonObjectBuilder urls = Json.createObjectBuilder();

            // Create S3Client
            S3Client s3Client = S3Client.builder()
                    .region(Region.of(s3.serviceClientConfiguration().region().toString()))
                    .credentialsProvider(credentialsProvider).build();

            // Create S3Presigner
            S3Presigner s3Presigner = S3Presigner.builder()
                    .region(Region.of(s3.serviceClientConfiguration().region().toString()))
                    .credentialsProvider(credentialsProvider).build();

            CreateMultipartUploadRequest.Builder createMultipartUploadRequestBuilder = CreateMultipartUploadRequest
                    .builder().bucket(bucketName).key(key);

            final boolean taggingDisabled = JvmSettings.DISABLE_S3_TAGGING.lookupOptional(Boolean.class, this.driverId)
                    .orElse(false);
            if (!taggingDisabled) {
                createMultipartUploadRequestBuilder.tagging("dv-state=temp");
            }

            CreateMultipartUploadResponse createMultipartUploadResponse = s3Client
                    .createMultipartUpload(createMultipartUploadRequestBuilder.build());
            String uploadId = createMultipartUploadResponse.uploadId();

            for (int i = 1; i <= (fileSize / minPartSize) + (fileSize % minPartSize > 0 ? 1 : 0); i++) {
                final int partNum = i;
                PresignedUploadPartRequest presignedRequest = s3Presigner.presignUploadPart(UploadPartPresignRequest
                        .builder().signatureDuration(Duration.between(Instant.now(), expiration))
                        .uploadPartRequest(b -> b.bucket(bucketName).key(key).uploadId(uploadId).partNumber(partNum))
                        .build());

                String urlString = presignedRequest.url().toString();
                if (!StringUtil.isEmpty(proxy)) {
                    urlString = urlString.replace(endpoint, proxy);
                }
                urls.add(Integer.toString(i), urlString);
            }

            response.add("urls", urls);
            response.add("abort", "/api/datasets/mpupload?globalid=" + globalId + "&uploadid=" + uploadId
                    + "&storageidentifier=" + storageIdentifier);
            response.add("complete", "/api/datasets/mpupload?globalid=" + globalId + "&uploadid=" + uploadId
                    + "&storageidentifier=" + storageIdentifier);

            s3Client.close();
            s3Presigner.close();
        }

        response.add("partSize", minPartSize);

        return response;
    }

    int getUrlExpirationMinutes() {
        String optionValue = getConfigParam(URL_EXPIRATION_MINUTES);
        if (optionValue != null) {
            Integer num;
            try {
                num = Integer.parseInt(optionValue);
            } catch (NumberFormatException ex) {
                num = null;
            }
            if (num != null) {
                return num;
            }
        }
        return 60;
    }

    private static String getBucketName(String driverId) {
        return getConfigParamForDriver(driverId, BUCKET_NAME);
    }

    private static long getMinPartSize(String driverId) {
        // as a default, pick 1 GB minimum part size for AWS S3
        // (minimum allowed is 5*1024**2 but it probably isn't worth the complexity
        // starting at ~5MB. Also - confirmed that they use base 2 definitions)
        long min = 5 * 1024 * 1024l;

        String partLength = getConfigParamForDriver(driverId, MIN_PART_SIZE);
        try {
            if (partLength != null) {
                long val = Long.parseLong(partLength);
                if (val >= min) {
                    min = val;
                } else {
                    logger.warning(min + " is the minimum part size allowed for jvm option dataverse.files." + driverId
                            + ".min-part-size");
                }
            } else {
                min = 1024 * 1024 * 1024l;
            }
        } catch (NumberFormatException nfe) {
            logger.warning("Unable to parse dataverse.files." + driverId + ".min-part-size as long: " + partLength);
        }
        return min;
    }

    private static S3TransferManager getTransferManager(String driverId) {
        if (driverTMMap.containsKey(driverId)) {
            return driverTMMap.get(driverId);
        } else {
            // Get the synchronous S3Client
            S3Client s3Client = getClient(driverId);
            
            // Create an S3AsyncClient from the S3Client
            S3AsyncClient s3AsyncClient = S3AsyncClient.builder()
                .credentialsProvider(((AwsServiceClientConfiguration) s3Client).credentialsProvider())
                .region(s3Client.serviceClientConfiguration().region())
                .endpointOverride(s3Client.serviceClientConfiguration().endpointOverride().orElse(null))
                .build();
    
            // Build the S3TransferManager with the S3AsyncClient
            S3TransferManager manager = S3TransferManager.builder()
                .s3Client(s3AsyncClient)
                .build();
    
            driverTMMap.put(driverId, manager);
            return manager;
        }
    }

    private static S3Client getClient(String driverId) {
        if (driverClientMap.containsKey(driverId)) {
            return (S3Client) driverClientMap.get(driverId);
        } else {
            // Create a builder for the S3Client
            S3ClientBuilder s3CB = S3Client.builder();
    
            // Configure endpoint and region
            String s3CEUrl = getConfigParamForDriver(driverId, CUSTOM_ENDPOINT_URL, "");
            String s3CERegion = getConfigParamForDriver(driverId, CUSTOM_ENDPOINT_REGION, "dataverse");
    
            if (!s3CEUrl.isEmpty()) {
                logger.info("Using URL: " + s3CEUrl + " region " + s3CERegion);
                s3CB.endpointOverride(URI.create(s3CEUrl)).region(Region.of(s3CERegion));
                // ToDo: Disable SSL certificate checks if using http
            }
    
            // Configure path style access
            Boolean s3pathStyleAccess = Boolean
                    .parseBoolean(getConfigParamForDriver(driverId, PATH_STYLE_ACCESS, "false"));
            s3CB.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(s3pathStyleAccess).build());
    
            // Configure chunked encoding
            Boolean s3chunkedEncoding = Boolean
                    .parseBoolean(getConfigParamForDriver(driverId, CHUNKED_ENCODING, "true"));
            s3CB.serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(s3chunkedEncoding).build());
    
            // Configure credentials
            s3CB.credentialsProvider(getCredentialsProvider(driverId));
    
            // Create a custom HTTP client with the desired pool size
            Integer poolSize = Integer.getInteger("dataverse.files." + driverId + ".connection-pool-size", 256);
            ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                    .maxConnections(poolSize);
    
            // Apply the custom HTTP client to the S3ClientBuilder
            s3CB.httpClientBuilder(httpClientBuilder);
            
            // Build the client
            S3Client client = s3CB.build();
            driverClientMap.put(driverId, client);
            return client;
        }
    }
    
    private static AwsCredentialsProvider getCredentialsProvider(String driverId) {
        if (driverCredentialsProviderMap.containsKey(driverId)) {
            return driverCredentialsProviderMap.get(driverId);
        } else {
            List<AwsCredentialsProvider> providers = new ArrayList<>();

            String s3profile = getConfigParamForDriver(driverId, PROFILE);
            boolean allowInstanceCredentials = true;

            if (s3profile != null) {
                allowInstanceCredentials = false;
            }

            Optional<String> accessKey = config.getOptionalValue("dataverse.files." + driverId + ".access-key",
                    String.class);
            Optional<String> secretKey = config.getOptionalValue("dataverse.files." + driverId + ".secret-key",
                    String.class);

            if (accessKey.isPresent() && secretKey.isPresent()) {
                allowInstanceCredentials = false;
                providers.add(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey.get(), secretKey.get())));
            } else if (s3profile == null) {
                s3profile = "default";
            }

            if (s3profile != null) {
                providers.add(ProfileCredentialsProvider.create(s3profile));
            }

            if (allowInstanceCredentials) {
                providers.add(InstanceProfileCredentialsProvider.create());
            }

            Collections.reverse(providers);
            AwsCredentialsProvider provider = AwsCredentialsProviderChain.builder().credentialsProviders(providers)
                    .build();
            driverCredentialsProviderMap.put(driverId, provider);
            return provider;
        }
    }

    public void removeTempTag() throws IOException {
        if (!(dvObject instanceof DataFile)) {
            logger.warning("Attempt to remove tag from non-file DVObject id: " + dvObject.getId());
            throw new IOException("Attempt to remove temp tag from non-file S3 Object");
        }
        try {
            key = getMainFileKey();
            DeleteObjectTaggingRequest deleteObjectTaggingRequest = DeleteObjectTaggingRequest.builder()
                    .bucket(bucketName).key(key).build();
            // Note - currently we only use one tag so delete is the fastest and cheapest
            // way to get rid of that one tag
            // Otherwise you have to get tags, remove the one you don't want and post new
            // tags and get charged for the operations
            s3.deleteObjectTagging(deleteObjectTaggingRequest);
        } catch (IOException e) {
            logger.warning("Could not create key for S3 object.");
            throw e;
        }
    }

    public static void abortMultipartUpload(String globalId, String storageIdentifier, String uploadId)
            throws IOException {
        String baseKey = null;
        int index = globalId.indexOf(":");
        if (index >= 0) {
            baseKey = globalId.substring(index + 1);
        } else {
            throw new IOException("Invalid Global ID (expected form with '<type>:' prefix)");
        }
        String[] info = DataAccess.getDriverIdAndStorageLocation(storageIdentifier);
        String driverId = info[0];
        S3Client s3Client = getClient(driverId);
        String bucketName = getBucketName(driverId);
        String key = getMainFileKey(baseKey, storageIdentifier, driverId);

        AbortMultipartUploadRequest req = AbortMultipartUploadRequest.builder().bucket(bucketName).key(key)
                .uploadId(uploadId).build();

        s3Client.abortMultipartUpload(req);
    }

    public static void completeMultipartUpload(String globalId, String storageIdentifier, String uploadId,
            List<CompletedPart> completedParts) throws IOException {
        String baseKey = null;
        int index = globalId.indexOf(":");
        if (index >= 0) {
            baseKey = globalId.substring(index + 1);
        } else {
            throw new IOException("Invalid Global ID (expected form with '<type>:' prefix)");
        }

        String[] info = DataAccess.getDriverIdAndStorageLocation(storageIdentifier);
        String driverId = info[0];
        S3Client s3Client = getClient(driverId);
        String bucketName = getBucketName(driverId);
        String key = getMainFileKey(baseKey, storageIdentifier, driverId);

        CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder().parts(completedParts)
                .build();

        CompleteMultipartUploadRequest completeMultipartUploadRequest = CompleteMultipartUploadRequest.builder()
                .bucket(bucketName).key(key).uploadId(uploadId).multipartUpload(completedMultipartUpload).build();

        s3Client.completeMultipartUpload(completeMultipartUploadRequest);
    }

    public boolean isMainDriver() {
        return mainDriver;
    }

    public void setMainDriver(boolean mainDriver) {
        this.mainDriver = mainDriver;
    }

    public static String getDriverPrefix(String driverId) {
        return driverId + DataAccess.SEPARATOR + getBucketName(driverId) + ":";
    }

    // Confirm inputs are of the form
    // s3://demo-dataverse-bucket:176e28068b0-1c3f80357c42
    protected static boolean isValidIdentifier(String driverId, String storageId) {
        String storageBucketAndId = storageId.substring(storageId.lastIndexOf("//") + 2);
        String bucketName = getBucketName(driverId);
        if (bucketName == null) {
            logger.warning("No bucket defined for " + driverId);
            return false;
        }
        int index = storageBucketAndId.lastIndexOf(":");
        if (index <= 0) {
            logger.warning("No bucket defined in submitted identifier: " + storageId);
            return false;
        }
        String idBucket = storageBucketAndId.substring(0, index);
        String id = storageBucketAndId.substring(index + 1);
        logger.fine(id);
        if (!bucketName.equals(idBucket)) {
            logger.warning("Incorrect bucket in submitted identifier: " + storageId);
            return false;
        }
        if (!usesStandardNamePattern(id)) {
            logger.warning("Unacceptable identifier pattern in submitted identifier: " + storageId);
            return false;
        }
        return true;
    }

    public void closeInputStream() {
        try {
            ResponseInputStream<GetObjectResponse> responseInputStream = (ResponseInputStream<GetObjectResponse>) getInputStream();
            if (responseInputStream != null && responseInputStream.available() > 0) {
                responseInputStream.abort();
            }
        } catch (IOException e) {
            // Could have already been closed
            errorMessage = e.getLocalizedMessage();
        }
        super.closeInputStream();
    }
    
    private List<String> listAllFiles() throws IOException {
        if (!this.canWrite()) {
            open();
        }
        Dataset dataset = this.getDataset();
        if (dataset == null) {
            throw new IOException("This S3AccessIO object hasn't been properly initialized.");
        }
        String prefix = dataset.getAuthorityForFileStorage() + "/" + dataset.getIdentifierForFileStorage() + "/";

        List<String> ret = new ArrayList<>();
        ListObjectsV2Request listObjectsReqManual = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix)
                .build();

        ListObjectsV2Response listObjectsResponse = null;
        listObjectsResponse = s3.listObjectsV2(listObjectsReqManual);

        if (listObjectsResponse == null) {
            return ret;
        }

        List<S3Object> storedFilesSummary = new ArrayList<>(listObjectsResponse.contents());

        String nextContinuationToken = listObjectsResponse.nextContinuationToken();
        while (nextContinuationToken != null) {
            logger.fine("S3 listObjects: going to next page of list");
            ListObjectsV2Request nextReq = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix)
                    .continuationToken(nextContinuationToken).build();

            ListObjectsV2Response nextResponse = s3.listObjectsV2(nextReq);
            if (nextResponse != null) {
                storedFilesSummary.addAll(nextResponse.contents());
                nextContinuationToken = nextResponse.nextContinuationToken();
            } else {
                nextContinuationToken = null;
            }
        }

        for (S3Object item : storedFilesSummary) {
            String fileName = item.key().substring(prefix.length());
            ret.add(fileName);
        }
        return ret;
    }

    private void deleteFile(String fileName) throws IOException {
        if (!this.canWrite()) {
            open();
        }
        Dataset dataset = this.getDataset();
        if (dataset == null) {
            throw new IOException("This S3AccessIO object hasn't been properly initialized.");
        }
        String prefix = dataset.getAuthorityForFileStorage() + "/" + dataset.getIdentifierForFileStorage() + "/";

        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder().bucket(bucketName)
                .key(prefix + fileName).build();

        s3.deleteObject(deleteObjectRequest);
    }

    @Override
    public List<String> cleanUp(Predicate<String> filter, boolean dryRun) throws IOException {
        List<String> toDelete = this.listAllFiles().stream().filter(filter).collect(Collectors.toList());
        if (dryRun) {
            return toDelete;
        }
        for (String f : toDelete) {
            this.deleteFile(f);
        }
        return toDelete;
    }

    @Override
    public long retrieveSizeFromMedia() throws IOException {
        key = getMainFileKey();
        HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket(bucketName).key(key).build();

        HeadObjectResponse headObjectResponse = s3.headObject(headObjectRequest);
        return headObjectResponse.contentLength();
    }

    public static String getNewIdentifier(String driverId) {
        return driverId + DataAccess.SEPARATOR + getConfigParamForDriver(driverId, BUCKET_NAME) + ":"
                + FileUtil.generateStorageIdentifier();
    }
}
