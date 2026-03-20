package edu.harvard.iq.dataverse.engine.command.impl;

import edu.harvard.iq.dataverse.DataCitation;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetFieldConstant;
import edu.harvard.iq.dataverse.DatasetLock.Reason;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.SettingsWrapper;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.ApiToken;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.engine.command.AbstractCommand;
import edu.harvard.iq.dataverse.engine.command.CommandContext;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.RequiredPermissions;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.pidproviders.doi.datacite.DOIDataCiteRegisterService;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean.Key;
import edu.harvard.iq.dataverse.util.ListSplitUtil;
import edu.harvard.iq.dataverse.util.bagit.BagGenerator;
import edu.harvard.iq.dataverse.util.bagit.OREMap;
import edu.harvard.iq.dataverse.workflow.step.Failure;
import edu.harvard.iq.dataverse.util.json.JsonLDTerm;
import edu.harvard.iq.dataverse.workflow.step.WorkflowStepResult;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.json.JsonObject;
import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.security.DigestInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@RequiredPermissions(Permission.PublishDataset)
public abstract class AbstractSubmitToArchiveCommand extends AbstractCommand<DatasetVersion> {

    protected final DatasetVersion version;
    protected final Map<String, String> requestedSettings = new HashMap<String, String>();
    protected String spaceName = null;
    protected boolean success=false;
    private static final Logger logger = Logger.getLogger(AbstractSubmitToArchiveCommand.class.getName());
    private static final int MAX_ZIP_WAIT = 20000;
    private static final int DEFAULT_THREADS = 2;
    
    public AbstractSubmitToArchiveCommand(DataverseRequest aRequest, DatasetVersion version) {
        super(aRequest, version.getDataset());
        this.version = version;
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public DatasetVersion execute(CommandContext ctxt) throws CommandException {
        
     // Check for locks while we're still in a transaction
        Dataset dataset = version.getDataset();
        if (dataset.getLockFor(Reason.finalizePublication) != null
                || dataset.getLockFor(Reason.FileValidationFailed) != null) {
            throw new CommandException("Dataset is locked and cannot be archived", this);
        }
        
        String settings = ctxt.settings().getValueForKey(SettingsServiceBean.Key.ArchiverSettings);
        List<String> settingsList = ListSplitUtil.split(settings);
        for (String settingName : settingsList) {
            Key setting = Key.parse(settingName);
            if (setting == null) {
                logger.warning("Invalid Archiver Setting: " + settingName);
            } else {
                requestedSettings.put(settingName, ctxt.settings().getValueForKey(setting));
            }
        }
        
        AuthenticatedUser user = getRequest().getAuthenticatedUser();
        ApiToken token = ctxt.authentication().findApiTokenByUser(user);
        if (token == null) {
            //No un-expired token
            token = ctxt.authentication().generateApiTokenForUser(user);
        }
        if (!preconditionsMet(version, token, requestedSettings)) {
            JsonObjectBuilder statusObjectBuilder = Json.createObjectBuilder();
            statusObjectBuilder.add(DatasetVersion.ARCHIVAL_STATUS, DatasetVersion.ARCHIVAL_STATUS_FAILURE);
            statusObjectBuilder.add(DatasetVersion.ARCHIVAL_STATUS_MESSAGE,
                    "Successful archiving of earlier versions is required.");
            version.setArchivalCopyLocation(statusObjectBuilder.build().toString());
            // Persist the failure status
            persistResult(ctxt, version);
        } else {

            String dataCiteXml = getDataCiteXml(version);
            OREMap oreMap = new OREMap(version, false);
            JsonObject ore = oreMap.getOREMap();
            Map<String, JsonLDTerm> terms = getJsonLDTerms(oreMap);
            performArchivingAndPersist(ctxt, version, dataCiteXml, ore, terms, token, requestedSettings);
        }
        return ctxt.datasetVersion().find(version.getId());
    }

    // While we have a transaction context, get the terms needed to create the baginfo file
    public static Map<String, JsonLDTerm> getJsonLDTerms(OREMap oreMap) {
        Map<String, JsonLDTerm> terms = new HashMap<String, JsonLDTerm>();
        terms.put(DatasetFieldConstant.datasetContact, oreMap.getContactTerm());
        terms.put(DatasetFieldConstant.datasetContactName, oreMap.getContactNameTerm());
        terms.put(DatasetFieldConstant.datasetContactEmail, oreMap.getContactEmailTerm());
        terms.put(DatasetFieldConstant.description, oreMap.getDescriptionTerm());
        terms.put(DatasetFieldConstant.descriptionText, oreMap.getDescriptionTextTerm());
        
        return terms;
    }

    /**
     * Note that this method may be called from the execute method above OR from a
     * workflow in which execute() is never called and therefore in which all
     * variables must be sent as method parameters. (Nominally version is set in the
     * constructor and could be dropped from the parameter list.)
     * @param ctxt 
     * 
     * @param version - the DatasetVersion to archive
     * @param token - an API Token for the user performing this action
     * @param requestedSettings - a map of the names/values for settings required by this archiver (sent because this class is not part of the EJB context (by design) and has no direct access to service beans).
     */
    public boolean preconditionsMet(DatasetVersion version, ApiToken token, Map<String, String> requestedSettings) {
        // Check if earlier versions must be archived first
        String requireEarlierArchivedValue = requestedSettings.get(SettingsServiceBean.Key.ArchiveOnlyIfEarlierVersionsAreArchived.toString());
        boolean requireEarlierArchived = Boolean.parseBoolean(requireEarlierArchivedValue);
        if (requireEarlierArchived) {
            logger.info("checking earlier versions");
            Dataset dataset = version.getDataset();
            List<DatasetVersion> versions = dataset.getVersions();

            boolean foundCurrent = false;

            // versions are ordered, all versions after the current one have lower
            // major/minor version numbers
            for (DatasetVersion versionInLoop : versions) {
                if (foundCurrent) {
                    // Once foundCurrent is true, we are looking at prior versions
                    // Check if this earlier version has been successfully archived
                    String archivalStatus = versionInLoop.getArchivalCopyLocationStatus();
                    if (archivalStatus == null || !archivalStatus.equals(DatasetVersion.ARCHIVAL_STATUS_SUCCESS)
//                                || !archivalStatus.equals(DatasetVersion.ARCHIVAL_STATUS_OBSOLETE)
                    ) {
                        return false;
                    }
                }
                if (versionInLoop.equals(version)) {
                    foundCurrent = true;
                }

            }
        }
        return true;
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public WorkflowStepResult performArchivingAndPersist(CommandContext ctxt, DatasetVersion version, String dataCiteXml, JsonObject ore, Map<String, JsonLDTerm> terms, ApiToken token, Map<String, String> requestedSetttings) {
        // This runs OUTSIDE any transaction
        BagGenerator.setNumConnections(getNumberOfBagGeneratorThreads());
        WorkflowStepResult wfsr = performArchiveSubmission(version, dataCiteXml, ore, terms, token, requestedSettings);
        persistResult(ctxt, version);
        return wfsr;
    }
    
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    private void persistResult(CommandContext ctxt, DatasetVersion versionWithStatus) {
        // New transaction just for this quick operation
        ctxt.datasetVersion().persistArchivalCopyLocation(versionWithStatus);
    }
    
    /**
     * This method is the only one that should be overwritten by other classes. Note
     * that this method may be called from the execute method above OR from a
     * workflow in which execute() is never called and therefore in which all
     * variables must be sent as method parameters. (Nominally version is set in the
     * constructor and could be dropped from the parameter list.)
     * 
     * @param version - the DatasetVersion to archive
     * @param dataCiteXml
     * @param ore  
     * @param terms 
     * @param token - an API Token for the user performing this action
     * @param requestedSettings - a map of the names/values for settings required by this archiver (sent because this class is not part of the EJB context (by design) and has no direct access to service beans).
     */
    abstract public WorkflowStepResult performArchiveSubmission(DatasetVersion version, String dataCiteXml, JsonObject ore, Map<String, JsonLDTerm> terms, ApiToken token, Map<String, String> requestedSetttings);


    protected int getNumberOfBagGeneratorThreads() {
        if (requestedSettings.get(BagGenerator.BAG_GENERATOR_THREADS) != null) {
            try {
                return Integer.valueOf(requestedSettings.get(BagGenerator.BAG_GENERATOR_THREADS));
            } catch (NumberFormatException nfe) {
                logger.warning("Can't parse the value of setting " + BagGenerator.BAG_GENERATOR_THREADS
                        + " as an integer - using default:" + DEFAULT_THREADS);
            }
        }
        return DEFAULT_THREADS;
    }

    @Override
    public String describe() {
        return super.describe() + "DatasetVersion: [" + version.getId() + " (v"
                + version.getFriendlyVersionNumber()+")]";
    }
    
    public String getDataCiteXml(DatasetVersion dv) {
        DataCitation dc = new DataCitation(dv);
        Map<String, String> metadata = dc.getDataCiteMetadata();
        return DOIDataCiteRegisterService.getMetadataFromDvObject(dv.getDataset().getGlobalId().asString(), metadata,
                dv.getDataset());
    }


    public static boolean isArchivable(Dataset dataset, SettingsWrapper settingsWrapper) {
        return true;
   }
   
   //Check if the chosen archiver imposes single-version-only archiving - in a View context
   public static boolean isSingleVersion(SettingsWrapper settingsWrapper) {
       return false;
  }
 
   //Check if the chosen archiver imposes single-version-only archiving - in the API
   public static boolean isSingleVersion(SettingsServiceBean settingsService) {
       return false;
  }

  /** Whether the archiver can delete existing archival files (and thus can retry when the existing files are incomplete/obsolete)
   * A static version supports calls via reflection while the instance method supports inheritance for use on actual command instances (see DatasetPage for both use cases).
   * @return
   */
  public static boolean supportsDelete() {
      return false;
  }

  public boolean canDelete() {
      return supportsDelete();
  }

  protected String getDataCiteFileName(String spaceName, DatasetVersion dv) {
    return spaceName + "_datacite.v" + dv.getFriendlyVersionNumber();
  }

  protected String getFileName(String spaceName, DatasetVersion dv) {
    return spaceName + ".v" + dv.getFriendlyVersionNumber();
  }

  protected String getSpaceName(Dataset dataset) {
    if (spaceName == null) {
        spaceName = dataset.getGlobalId().asString().replace(':', '-').replace('/', '-').replace('.', '-')
                .toLowerCase();
    }
    return spaceName;
  }
}
