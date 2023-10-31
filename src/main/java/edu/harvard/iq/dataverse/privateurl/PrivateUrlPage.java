package edu.harvard.iq.dataverse.privateurl;

import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.DataFileServiceBean;
import edu.harvard.iq.dataverse.DataverseSession;
import edu.harvard.iq.dataverse.authorization.users.PrivateUrlUser;
import java.io.Serializable;
import java.util.logging.Logger;
import javax.ejb.EJB;
import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * Backing bean for JSF page. Sets session to {@link PrivateUrlUser}. 
 */
@ViewScoped
@Named("PrivateUrlPage")
public class PrivateUrlPage implements Serializable {

    private static final Logger logger = Logger.getLogger(PrivateUrlPage.class.getCanonicalName());

    @EJB
    PrivateUrlServiceBean privateUrlService;
    @EJB
    DataFileServiceBean dataFileService;
    @Inject
    DataverseSession session;

    /**
     * The unique string used to look up a PrivateUrlUser and the associated
     * draft dataset version to redirect the user to.
     */
    String token;
    String filePid;
    String fileId;
    
    public String init() {
        try {
            DataFile file = null;
            if(fileId!= null) {
                file = dataFileService.find(fileId);
            } else if(filePid!= null) {
                file = dataFileService.findByGlobalId(filePid);
            }
            PrivateUrlRedirectData privateUrlRedirectData = privateUrlService.getPrivateUrlRedirectDataFromToken(token, file);
            String draftDatasetPageToBeRedirectedTo = privateUrlRedirectData.getDraftDatasetPageToBeRedirectedTo() + "&faces-redirect=true";
            PrivateUrlUser privateUrlUser = privateUrlRedirectData.getPrivateUrlUser();
            session.setUser(privateUrlUser);
            logger.info("Redirecting PrivateUrlUser '" + privateUrlUser.getIdentifier() + "' to " + draftDatasetPageToBeRedirectedTo);
            return draftDatasetPageToBeRedirectedTo;
        } catch (Exception ex) {
            logger.info("Exception processing Private URL token '" + token + "':" + ex);
            return "/404.xhtml";
        }
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
    
    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }
    
    @Deprecated
    public String getFile() {
        return filePid;
    }

    @Deprecated
    public void setFile(String file) {
        this.filePid = file;
    }

    public String getFilePid() {
        return filePid;
    }

    public void setFilePid(String filePid) {
        this.filePid = filePid;
    }

}
