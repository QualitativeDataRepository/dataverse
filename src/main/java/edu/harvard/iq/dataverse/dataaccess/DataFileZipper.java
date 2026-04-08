/*
   Copyright (C) 2005-2012, by the President and Fellows of Harvard College.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

   Dataverse Network - A web application to share, preserve and analyze research data.
   Developed at the Institute for Quantitative Social Science, Harvard University.
   Version 3.0.
*/
package edu.harvard.iq.dataverse.dataaccess;

import java.io.InputStream;
import java.io.IOException;


import edu.harvard.iq.dataverse.DataFile;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;


/**
 *
 * @author Leonid Andreev
 */
public class DataFileZipper {
    
    private static final Logger logger = Logger.getLogger(DataFileZipper.class.getCanonicalName());
    private static final String MANIFEST_FILE_NAME = "MANIFEST.TXT";
    
    private OutputStream outputStream = null; 
    private ZipArchiveOutputStream zipOutputStream = null;
    
    private List<String> fileNameList = null; // the list of file names to check for duplicates
    private List<Long> zippedFilesList = null; // list of successfully zipped files, to update guestbooks and download counts (not yet implemented)
    
    private String fileManifest = "";
    
    private Set<String> zippedFolders = null; 

    public DataFileZipper() {
        fileNameList = new ArrayList<>();
        zippedFilesList = new ArrayList<>(); 
        zippedFolders = new HashSet<>();
    }
    
    public DataFileZipper(OutputStream outputStream) {
        this.outputStream = outputStream;
        fileNameList = new ArrayList<>();
        zippedFilesList = new ArrayList<>();
        zippedFolders = new HashSet<>();
    }
    
    public void setOutputStream(OutputStream outputStream) {
        this.outputStream = outputStream; 
    }
    
    public OutputStream getOutputStream() {
        return this.outputStream;
    }
    
    public void setFileManifest(String fileManifest) {
        this.fileManifest = fileManifest;
    }
    
    public String getFileManifest() {
        return this.fileManifest; 
    }
    
    private void openZipStream() throws IOException {
        if (outputStream == null) {
            throw new IOException("Attempted to create a ZipOutputStream from a NULL OutputStream.");
        }
        this.zipOutputStream = new ZipArchiveOutputStream(outputStream);
        this.zipOutputStream.setEncoding("UTF-8");
        this.zipOutputStream.setUseZip64(Zip64Mode.Always);
    }
    
    public long addFileToZipStream(DataFile dataFile, boolean getOriginal) throws IOException {
        if (zipOutputStream == null) {
            openZipStream();
        }

        boolean createManifest = fileManifest != null;
        
        DataAccessRequest daReq = new DataAccessRequest();
        StorageIO<DataFile> accessObject = DataAccess.getStorageIO(dataFile, daReq);

        if (accessObject != null) {
            Boolean gotOriginal = false;
            if(getOriginal) {
                StorageIO<DataFile> tempAccessObject = StoredOriginalFile.retreive(accessObject);
                if(null != tempAccessObject) { //If there is an original, use it
                    gotOriginal = true;
                    accessObject = tempAccessObject; 
                } 
            }
            if(!gotOriginal) { //if we didn't get this from sof.retreive we have to open it
                accessObject.open();
            }

            long byteSize = 0;

            String fileName = accessObject.getFileName();
            String mimeType = accessObject.getMimeType();
            if (mimeType == null || mimeType.equals("")) {
                mimeType = "application/octet-stream";
            }

            //if (sizeTotal + fileSize < sizeLimit) {

            try (InputStream instream = accessObject.getInputStream()) {
                if (instream == null) {
                    if (createManifest) {
                        addToManifest(fileName
                                + " (" + mimeType
                                + ") COULD NOT be downloaded because an I/O error has occured. \r\n");
                    }
                } else {
                    // If any of the files have non-empty DirectoryLabels we'll
                    // use them to re-create the folders in the Zipped bundle:
                    String folderName = dataFile.getFileMetadata().getDirectoryLabel();
                    if (folderName != null) {
                        // If any of the saved folder names start with with slashes,
                        // we want to remove them:
                        // (i.e., ///foo/bar will become foo/bar)
                        while (folderName.startsWith("/")) {
                            folderName = folderName.substring(1);
                        }
                        if (!"".equals(folderName)) {
                            if (!zippedFolders.contains(folderName)) {
                                ZipArchiveEntry d = new ZipArchiveEntry(folderName + "/");
                                zipOutputStream.putArchiveEntry(d);
                                zipOutputStream.closeArchiveEntry();
                                zippedFolders.add(folderName);
                            }
                            fileName = folderName + "/" + fileName;
                        }
                    }

                    String zipEntryName = checkZipEntryName(fileName);

                    ZipArchiveEntry e = new ZipArchiveEntry(zipEntryName);
                    e.setSize(accessObject.getSize());
                    logger.fine("created new zip entry for " + zipEntryName);

                    zipOutputStream.putArchiveEntry(e);

                    // before writing out any bytes from the input stream, flush
                    // any extra content, such as the variable header for the
                    // subsettable files:
                    String varHeaderLine = accessObject.getVarHeader();
                    if (varHeaderLine != null) {
                        zipOutputStream.write(varHeaderLine.getBytes());
                        byteSize += (varHeaderLine.getBytes().length);
                    }

                    long bytesTransferred = instream.transferTo(zipOutputStream);
                    byteSize += bytesTransferred;
                    logger.fine("transferred " + bytesTransferred + " bytes.");
                    zipOutputStream.flush();

                    zipOutputStream.closeArchiveEntry();
                    logger.fine("closed zip entry for " + zipEntryName);

                    if (createManifest) {
                        addToManifest(zipEntryName + " (" + mimeType + ") " + byteSize + " bytes.\r\n");
                    }

                    if (byteSize > 0) {
                        zippedFilesList.add(dataFile.getId());
                    }
                }
            }
            //} else if (createManifest) {
            //    addToManifest(fileName + " (" + mimeType + ") " + " skipped because the total size of the download bundle exceeded the limit of " + sizeLimit + " bytes.\r\n");
            //}
            return byteSize;
        }
        return 0L;
    }
    
    public void finalizeZipStream() throws IOException {
        boolean createManifest = fileManifest != null;
        
        if (zipOutputStream == null) {
            openZipStream();
        }
        
        if (createManifest) {
            String manifestEntry = MANIFEST_FILE_NAME; 
            while (fileNameList.contains(manifestEntry)) {
                manifestEntry = "0".concat(manifestEntry); 
            }
            
            ZipArchiveEntry e = new ZipArchiveEntry(manifestEntry);

            zipOutputStream.putArchiveEntry(e);
            zipOutputStream.write(fileManifest.getBytes());
            zipOutputStream.closeArchiveEntry();
        }

        zipOutputStream.finish();
        zipOutputStream.flush();
        zipOutputStream.close();
    }
    
    public void addToManifest(String manifestEntry) {
        this.fileManifest = this.fileManifest + manifestEntry; 
    }
    
    // check for and process duplicates:
    private String checkZipEntryName(String originalName) {
        String name = originalName;
        int fileSuffix = 1;
        int extensionIndex = originalName.lastIndexOf(".");

        while (fileNameList.contains(name)) {
            if (extensionIndex != -1) {
                name = originalName.substring(0, extensionIndex) + "_" + fileSuffix++ + originalName.substring(extensionIndex);
            } else {
                name = originalName + "_" + fileSuffix++;
            }
        }
        fileNameList.add(name);
        return name;
    }
}