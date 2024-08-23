package edu.harvard.iq.dataverse.engine.command.impl;

import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.engine.command.CommandContext;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.RequiredPermissions;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.exception.IllegalCommandException;
import edu.harvard.iq.dataverse.export.ExportService;
import io.gdcc.spi.export.ExportException;
import edu.harvard.iq.dataverse.util.BundleUtil;
import edu.harvard.iq.dataverse.util.DatasetFieldUtil;
import edu.harvard.iq.dataverse.workflows.WorkflowComment;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.TermsOfUseAndAccess;
import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.FileMetadata;
import edu.harvard.iq.dataverse.DataFileCategory;
import edu.harvard.iq.dataverse.DatasetVersionDifference;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author qqmyers
 * 
 *         Adapted from UpdateDatasetVersionCommand
 */
@RequiredPermissions(Permission.EditDataset)
public class CuratePublishedDatasetVersionCommand extends AbstractDatasetCommand<Dataset> {

    private static final Logger logger = Logger.getLogger(CuratePublishedDatasetVersionCommand.class.getCanonicalName());
    final private boolean validateLenient = false;

    public CuratePublishedDatasetVersionCommand(Dataset theDataset, DataverseRequest aRequest) {
        super(aRequest, theDataset);
    }

    public boolean isValidateLenient() {
        return validateLenient;
    }

    @Override
    public Dataset execute(CommandContext ctxt) throws CommandException {
        if (!getUser().isSuperuser()) {
            throw new IllegalCommandException("Only superusers can curate published dataset versions", this);
        }
        Dataset savedDataset = null;
        try {
            setDataset(ctxt.em().merge(getDataset()));
        ctxt.permissions().checkEditDatasetLock(getDataset(), getRequest(), this);
        // Invariant: Dataset has no locks preventing the update
        DatasetVersion updateVersion = getDataset().getLatestVersionForCopy();
DatasetVersion testVersion = getDataset().getLatestVersion();
logger.info("Update ID: " + updateVersion.getId());
logger.info("Test ID: " + testVersion.getId());
        DatasetVersion newVersion = getDataset().getOrCreateEditVersion();
        logger.info("New ID: " + newVersion.getId());
        // Copy metadata from draft version to latest published version
        updateVersion.setDatasetFields(newVersion.initDatasetFields());

        

        // final DatasetVersion editVersion = getDataset().getEditVersion();
        DatasetFieldUtil.tidyUpFields(updateVersion.getDatasetFields(), true);

        // Merge the new version into our JPA context
        updateVersion = ctxt.em().merge(updateVersion);

        TermsOfUseAndAccess oldTerms = updateVersion.getTermsOfUseAndAccess();
        TermsOfUseAndAccess newTerms = newVersion.getTermsOfUseAndAccess();
        newTerms.setDatasetVersion(updateVersion);
        updateVersion.setTermsOfUseAndAccess(newTerms);
        //Put old terms on version that will be deleted....
        newVersion.setTermsOfUseAndAccess(oldTerms);
        oldTerms.setDatasetVersion(newVersion);
        
        //Validate metadata and TofA conditions
        validateOrDie(updateVersion, isValidateLenient());
        
        //Also set the fileaccessrequest boolean on the dataset to match the new terms
        getDataset().setFileAccessRequest(updateVersion.getTermsOfUseAndAccess().isFileAccessRequest());
        List<WorkflowComment> newComments = newVersion.getWorkflowComments();
        if (newComments!=null && newComments.size() >0) {
            for(WorkflowComment wfc: newComments) {
                wfc.setDatasetVersion(updateVersion);
            }
            updateVersion.getWorkflowComments().addAll(newComments);
        }

        
        // we have to merge to update the database but not flush because
        // we don't want to create two draft versions!
       // Dataset tempDataset = ctxt.em().merge(getDataset());
        Dataset tempDataset = getDataset();
        updateVersion = tempDataset.getLatestVersionForCopy();
        
        logger.info("Version to be updated dsf size: " + updateVersion.getDatasetFields().size());
        updateVersion.getDatasetFields().forEach(dsf -> logger.info("UV FieldId: " + dsf.getId()));
        // Look for file metadata changes and update published metadata if needed
        List<FileMetadata> pubFmds = updateVersion.getFileMetadatas();
        int pubFileCount = pubFmds.size();
        int newFileCount = tempDataset.getOrCreateEditVersion().getFileMetadatas().size();
        /* The policy for this command is that it should only be used when the change is a 'minor update' with no file changes.
         * Nominally we could call .isMinorUpdate() for that but we're making the same checks as we go through the update here. 
         */
        if (pubFileCount != newFileCount) {
            logger.severe("Draft version of dataset: " + tempDataset.getId() + " has: " + newFileCount + " while last published version has " + pubFileCount);
            throw new IllegalCommandException(BundleUtil.getStringFromBundle("datasetversion.update.failure"), this);
        }
        Long thumbId = null;
        if(tempDataset.getThumbnailFile()!=null) {
            thumbId = tempDataset.getThumbnailFile().getId();
        };
        for (FileMetadata publishedFmd : pubFmds) {
            DataFile dataFile = publishedFmd.getDataFile();
            FileMetadata draftFmd = dataFile.getLatestFileMetadata();
            boolean metadataUpdated = false;
            if (draftFmd == null || draftFmd.getDatasetVersion().equals(updateVersion)) {
                if (draftFmd == null) {
                    logger.severe("Unable to find latest FMD for file id: " + dataFile.getId());
                } else {
                    logger.severe("No filemetadata for file id: " + dataFile.getId() + " in draft version");
                }
                throw new IllegalCommandException(BundleUtil.getStringFromBundle("datasetversion.update.failure"), this);
            } else {

                metadataUpdated = DatasetVersionDifference.compareFileMetadatas(publishedFmd, draftFmd);
                publishedFmd.setLabel(draftFmd.getLabel());
                publishedFmd.setDescription(draftFmd.getDescription());
                publishedFmd.setCategories(draftFmd.getCategories());
                publishedFmd.setRestricted(draftFmd.isRestricted());
                dataFile.setRestricted(draftFmd.isRestricted());
                publishedFmd.setProvFreeForm(draftFmd.getProvFreeForm());
                publishedFmd.copyVariableMetadata(draftFmd.getVariableMetadatas());
                publishedFmd.copyVarGroups(draftFmd.getVarGroups());

            }
            if (metadataUpdated) {
                dataFile.setModificationTime(getTimestamp());
            }
            // Now delete filemetadata from draft version before deleting the version itself
            FileMetadata mergedFmd = ctxt.em().merge(draftFmd);
            ctxt.em().remove(mergedFmd);
            // including removing metadata from the list on the datafile
            draftFmd.getDataFile().getFileMetadatas().remove(draftFmd);
            tempDataset.getOrCreateEditVersion().getFileMetadatas().remove(draftFmd);
            // And any references in the list held by categories
            for (DataFileCategory cat : tempDataset.getCategories()) {
                cat.getFileMetadatas().remove(draftFmd);
            }
            //And any thumbnail reference
            if(publishedFmd.getDataFile().getId()==thumbId) {
                tempDataset.setThumbnailFile(publishedFmd.getDataFile());
            }
        }
        if(logger.isLoggable(Level.FINE)) {
            for(FileMetadata fmd: updateVersion.getFileMetadatas()) {
                logger.fine("Id: " + fmd.getId() + " label: " + fmd.getLabel());
            }
        }
        // Update modification time on the published version and the dataset
        updateVersion.setLastUpdateTime(getTimestamp());
        tempDataset.setModificationTime(getTimestamp());
        updateVersion = ctxt.em().merge(updateVersion);
        ctxt.em().merge(newVersion);
        savedDataset = ctxt.em().merge(tempDataset);

        // Flush before calling DeleteDatasetVersion which calls
        // PrivateUrlServiceBean.getPrivateUrlFromDatasetId() that will query the DB and
        // fail if our changes aren't there
        ctxt.em().flush();
        logger.info("Version to be updated dsf size: " + savedDataset.getLatestVersionForCopy().getDatasetFields().size());
        logger.info("Version to be deleted toua id: " + savedDataset.getLatestVersion().getTermsOfUseAndAccess().getId());
        // Now delete draft version
        DeleteDatasetVersionCommand cmd;

        cmd = new DeleteDatasetVersionCommand(getRequest(), savedDataset);
        cmd.execute(ctxt);
        logger.info("AM Version to be updated dsf size: " + savedDataset.getLatestVersionForCopy().getDatasetFields().size());
        logger.info("AM Version id: " + savedDataset.getLatestVersionForCopy().getDatasetFields().size());
        // And update metadata at PID provider
        try {
            ctxt.engine().submit(
                new UpdateDvObjectPIDMetadataCommand(savedDataset, getRequest()));
        } catch (CommandException ex) {
            //Make this non-fatal as after the DeleteDatasetVersionCommand, we can't roll back - for some reason no datasetfields remain in the DB
            //(The old version doesn't need them and the new version doesn't get updated to include them?)
            logger.log(Level.WARNING, "Curate Published DatasetVersion: exception while updating PID metadata:{0}", ex.getMessage());
        }
        // Update so that getDataset() in updateDatasetUser will get the up-to-date copy
        // (with no draft version)
        setDataset(savedDataset);
        updateDatasetUser(ctxt);
        }
        catch (Throwable t) {
            logger.severe("Something went wrong: " + t.getLocalizedMessage());
            t.printStackTrace();
        }
        return savedDataset;
    }

    @Override
    public boolean onSuccess(CommandContext ctxt, Object r) {
        boolean retVal = true;
        Dataset d = (Dataset) r;
        
        ctxt.index().asyncIndexDataset(d, true);
        
        // And the exported metadata files
        try {
            ExportService instance = ExportService.getInstance();
            instance.exportAllFormats(d);
        } catch (ExportException ex) {
            // Just like with indexing, a failure to export is not a fatal condition.
            retVal = false;
            logger.log(Level.WARNING, "Curate Published DatasetVersion: exception while exporting metadata files:{0}", ex.getMessage());
        }
        return retVal;
    }
}
