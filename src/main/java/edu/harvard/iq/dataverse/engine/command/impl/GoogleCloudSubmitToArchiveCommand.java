package edu.harvard.iq.dataverse.engine.command.impl;

import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetLock.Reason;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.ApiToken;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.RequiredPermissions;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.workflow.step.Failure;
import edu.harvard.iq.dataverse.workflow.step.WorkflowStepResult;
import edu.harvard.iq.dataverse.util.bagit.BagGenerator;
import edu.harvard.iq.dataverse.util.bagit.OREMap;
import org.apache.commons.codec.binary.Hex;
import org.threeten.bp.Duration;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.logging.Logger;

@RequiredPermissions(Permission.PublishDataset)
public class GoogleCloudSubmitToArchiveCommand extends AbstractSubmitToArchiveCommand {

    private static final Logger logger = Logger.getLogger(GoogleCloudSubmitToArchiveCommand.class.getName());
    private static final String GOOGLECLOUD_BUCKET = ":GoogleCloudBucket";
    private static final String GOOGLECLOUD_PROJECT = ":GoogleCloudProject";

    // Set timeouts in milliseconds. For example, 5 minutes.
    private static final int timeout = 300_000;

    public GoogleCloudSubmitToArchiveCommand(DataverseRequest aRequest, DatasetVersion version) {
        super(aRequest, version);
    }

    @Override
    public WorkflowStepResult performArchiveSubmission(DatasetVersion dv, ApiToken token, Map<String, String> requestedSettings) {
        logger.fine("In GoogleCloudSubmitToArchiveCommand...");
        String bucketName = requestedSettings.get(GOOGLECLOUD_BUCKET);
        String projectName = requestedSettings.get(GOOGLECLOUD_PROJECT);
        logger.fine("Project: " + projectName + " Bucket: " + bucketName);

        if (bucketName != null && projectName != null) {
            Storage storage;
            // Set a failure status that will be updated if we succeed
            JsonObjectBuilder statusObject = Json.createObjectBuilder();
            statusObject.add(DatasetVersion.ARCHIVAL_STATUS, DatasetVersion.ARCHIVAL_STATUS_FAILURE);
            statusObject.add(DatasetVersion.ARCHIVAL_STATUS_MESSAGE, "Bag not transferred");

            String cloudKeyFile = JvmSettings.FILES_DIRECTORY.lookup() + File.separator + "googlecloudkey.json";
            RetrySettings retrySettings = RetrySettings.newBuilder()
                    .setTotalTimeout(Duration.ofMillis(timeout))
                    .build();

            // Create temporary file for bag
            Path tempBagFile = null;

            try (FileInputStream cloudKeyStream = new FileInputStream(cloudKeyFile)) {
                storage = StorageOptions.newBuilder()
                        .setCredentials(ServiceAccountCredentials.fromStream(cloudKeyStream))
                        .setProjectId(projectName)
                        .setRetrySettings(retrySettings)
                        .build()
                        .getService();
                Bucket bucket = storage.get(bucketName);

                Dataset dataset = dv.getDataset();
                if (dataset.getLockFor(Reason.finalizePublication) == null) {

                    String spaceName = dataset.getGlobalId().asString().replace(':', '-').replace('/', '-')
                            .replace('.', '-').toLowerCase();

                    String dataciteXml = getDataCiteXml(dv);

                    // Upload datacite.xml
                    MessageDigest messageDigest = MessageDigest.getInstance("MD5");
                    try (PipedInputStream dataciteIn = new PipedInputStream();
                            DigestInputStream digestInputStream = new DigestInputStream(dataciteIn, messageDigest)) {
                        // Add datacite.xml file

                        Thread dcThread = new Thread(new Runnable() {
                            public void run() {
                                try (PipedOutputStream dataciteOut = new PipedOutputStream(dataciteIn)) {

                                    dataciteOut.write(dataciteXml.getBytes(StandardCharsets.UTF_8));
                                    dataciteOut.close();
                                    success = true;
                                } catch (Exception e) {
                                    logger.severe("Error creating datacite.xml: " + e.getMessage());
                                    // TODO Auto-generated catch block
                                    e.printStackTrace();
                                    // throw new RuntimeException("Error creating datacite.xml: " + e.getMessage());
                                }
                            }
                        });
                        dcThread.start();
                        // Have seen Pipe Closed errors for other archivers when used as a workflow
                        // without this delay loop
                        int i = 0;
                        while (digestInputStream.available() <= 0 && i < 100) {
                            Thread.sleep(10);
                            i++;
                        }
                        Blob dcXml = bucket.create(spaceName + "/datacite.v" + dv.getFriendlyVersionNumber() + ".xml", digestInputStream, "text/xml", Bucket.BlobWriteOption.doesNotExist());

                        dcThread.join();
                        String checksum = dcXml.getMd5ToHexString();
                        logger.fine("Content: datacite.xml added with checksum: " + checksum);
                        String localchecksum = Hex.encodeHexString(digestInputStream.getMessageDigest().digest());
                        if (!success || !checksum.equals(localchecksum)) {
                            logger.severe("Failure on " + spaceName);
                            logger.severe(success ? checksum + " not equal to " + localchecksum : "datacite.xml transfer did not succeed");
                            try {
                                dcXml.delete(Blob.BlobSourceOption.generationMatch());
                            } catch (StorageException se) {
                                logger.warning(se.getMessage());
                            }
                            return new Failure("Error in transferring DataCite.xml file to GoogleCloud",
                                    "GoogleCloud Submission Failure: incomplete metadata transfer");
                        }
                    }

                    tempBagFile = Files.createTempFile("dataverse-bag-", ".zip");
                    logger.fine("Creating bag in temporary file: " + tempBagFile.toString());

                    // Generate bag to temporary file
                    try (FileOutputStream fos = new FileOutputStream(tempBagFile.toFile())) {
                        BagGenerator.setNumConnections(getNumberOfBagGeneratorThreads());
                        BagGenerator bagger = new BagGenerator(new OREMap(dv, false), dataciteXml);
                        bagger.setAuthenticationKey(token.getTokenString());

                        if (!bagger.generateBag(fos)) {
                            throw new IOException("Bag generation failed");
                        }
                    }

                    long bagSize = Files.size(tempBagFile);
                    logger.fine("Bag created successfully, size: " + bagSize + " bytes");

                    if (bagSize == 0) {
                        throw new IOException("Generated bag file is empty");
                    }

                    // Upload bag file and calculate checksum during upload
                    String fileName = spaceName + ".v" + dv.getFriendlyVersionNumber() + ".zip";
                    messageDigest = MessageDigest.getInstance("MD5");
                    String localChecksum;

                    try (FileInputStream fis = new FileInputStream(tempBagFile.toFile());
                            DigestInputStream dis = new DigestInputStream(fis, messageDigest)) {

                        logger.fine("Uploading bag to GoogleCloud: " + spaceName + "/" + fileName);

                        Blob bag = bucket.create(
                                spaceName + "/" + fileName,
                                dis,
                                "application/zip",
                                Bucket.BlobWriteOption.doesNotExist());

                        if (bag.getSize() == 0) {
                            throw new IOException("Uploaded bag has zero size");
                        }

                        // Get checksum after upload completes
                        localChecksum = Hex.encodeHexString(dis.getMessageDigest().digest());
                        String remoteChecksum = bag.getMd5ToHexString();

                        logger.fine("Bag: " + fileName + " uploaded");
                        logger.fine("Local checksum:  " + localChecksum);
                        logger.fine("Remote checksum: " + remoteChecksum);

                        if (!localChecksum.equals(remoteChecksum)) {
                            logger.severe("Bag checksum mismatch!");
                            logger.severe("Local: " + localChecksum + " != Remote: " + remoteChecksum);
                            try {
                                bag.delete(Blob.BlobSourceOption.generationMatch());
                            } catch (StorageException se) {
                                logger.warning(se.getMessage());
                            }
                            return new Failure("Error in transferring Zip file to GoogleCloud",
                                    "GoogleCloud Submission Failure: bag checksum mismatch");
                        }
                    }

                    logger.fine("GoogleCloud Submission step: Content Transferred Successfully");

                    // Document the location of dataset archival copy location (actually the URL
                    // where you can view it as an admin)
                    // Changed to point at bucket where the zip and datacite.xml are visible
                    StringBuffer sb = new StringBuffer("https://console.cloud.google.com/storage/browser/");
                    sb.append(bucketName + "/" + spaceName);
                    statusObject.add(DatasetVersion.ARCHIVAL_STATUS, DatasetVersion.ARCHIVAL_STATUS_SUCCESS);
                    statusObject.add(DatasetVersion.ARCHIVAL_STATUS_MESSAGE, sb.toString());

                } else {
                    logger.warning("GoogleCloud Submission Workflow aborted: Dataset locked for pidRegister");
                    return new Failure("Dataset locked");
                }
            } catch (Exception e) {
                logger.warning("GoogleCloud submission failed: " + e.getLocalizedMessage());
                e.printStackTrace();
                return new Failure("GoogleCloud Submission Failure",
                        e.getLocalizedMessage() + ": check log for details");

            } finally {
                // Clean up temporary file
                if (tempBagFile != null) {
                    try {
                        Files.deleteIfExists(tempBagFile);
                        logger.fine("Temporary bag file deleted: " + tempBagFile.toString());
                    } catch (IOException e) {
                        logger.warning("Failed to delete temporary bag file: " + e.getMessage());
                    }
                }

                dv.setArchivalCopyLocation(statusObject.build().toString());
            }
            return WorkflowStepResult.OK;
        } else {
            return new Failure("GoogleCloud Submission not configured - no \":GoogleCloudBucket\" and/or \":GoogleCloudProject\".");
        }
    }
}