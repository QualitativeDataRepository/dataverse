package edu.harvard.iq.dataverse.engine.command.impl;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetLock;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.DatasetVersionUser;
import edu.harvard.iq.dataverse.DataverseRoleServiceBean;
import edu.harvard.iq.dataverse.DvObject;
import edu.harvard.iq.dataverse.PermissionServiceBean;
import edu.harvard.iq.dataverse.RoleAssignment;
import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.DataverseRole;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.authorization.users.User;
import edu.harvard.iq.dataverse.engine.NoOpTestEntityManager;
import edu.harvard.iq.dataverse.engine.TestCommandContext;
import edu.harvard.iq.dataverse.engine.TestDataverseEngine;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.mocks.MocksFactory;
import edu.harvard.iq.dataverse.search.IndexServiceBean;
import edu.harvard.iq.dataverse.util.BundleUtil;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.util.testing.JvmSetting;
import edu.harvard.iq.dataverse.util.testing.LocalJvmSettings;
import edu.harvard.iq.dataverse.workflows.WorkflowComment;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@LocalJvmSettings
public class ReturnDatasetToAuthorCommandTest {

    private Dataset dataset;
    private DataverseRequest dataverseRequest;
    private TestDataverseEngine testEngine;

    @BeforeEach
    public void setUp() {
        dataset = new Dataset();

        HttpServletRequest aHttpServletRequest = null;
        dataverseRequest = new DataverseRequest(MocksFactory.makeAuthenticatedUser("First", "Last"), aHttpServletRequest);

        testEngine = new TestDataverseEngine(new TestCommandContext() {
            @Override
            public AuthenticationServiceBean authentication() {
                return new AuthenticationServiceBean() {
                    @Override
                    public AuthenticatedUser getAuthenticatedUser(String id) {
                        return MocksFactory.makeAuthenticatedUser("First", "Last");
                    }
                };
            }

            @Override
            public IndexServiceBean index() {
                return new IndexServiceBean() {
                    @Override
                    public void asyncIndexDataset(Dataset dataset, boolean doNormalSolrDocCleanUp) {
                    }
                };
            }

            @Override
            public EntityManager em() {
                return new NoOpTestEntityManager();
            }

            @Override
            public DatasetServiceBean datasets() {
                return new DatasetServiceBean() {
                    {
                        em = new NoOpTestEntityManager();
                    }
                        
                    @Override
                    public DatasetVersionUser getDatasetVersionUser(DatasetVersion version, User user) {
                        return null;
                    }
                    @Override 
                    public WorkflowComment addWorkflowComment(WorkflowComment comment){
                        return comment;
                    }
                    
                    @Override
                    public void removeDatasetLocks(Dataset dataset, DatasetLock.Reason aReason) {
                        
                    }
                };
            }

            @Override
            public DataverseRoleServiceBean roles() {
                return new DataverseRoleServiceBean() {

                    @Override
                    public DataverseRole findBuiltinRoleByAlias(String alias) {
                        return new DataverseRole();
                    }

                    @Override
                    public RoleAssignment save(RoleAssignment assignment) {
                        // no-op
                        return assignment;
                    }

                };
            }

            @Override
            public PermissionServiceBean permissions() {
                return new PermissionServiceBean() {
                    @Override
                    public List<AuthenticatedUser> getUsersWithPermissionOn(Permission permission, DvObject dvo) {
                        // We only need permissions for notifications, which we are testing in InReviewWorkflowIT.
                        return Collections.emptyList();
                    }
                };
            }

        }
        );
    }


    /*
            if (theDataset == null) {
            throw new IllegalCommandException("Can't return to author. Dataset is null.", this);
        } 
                
        if (!theDataset.getLatestVersion().isInReview()) {
            throw new IllegalCommandException("Latest version of dataset " + theDataset.getIdentifier() + " is not In Review. Only such versions my be returned to author.", this);
        } 
        
        if (theDataset.getLatestVersion().getReturnReason() == null || theDataset.getLatestVersion().getReturnReason().isEmpty()){
            throw new IllegalCommandException("You must enter a reason for returning a dataset to its author.", this);
        }
     */
    @Test
    @JvmSetting(key = JvmSettings.FEATURE_FLAG, value = "false", varArgs = "disable-return-to-author-reason")
    void testDatasetNull() {
        assertThrows(IllegalArgumentException.class,
            () -> new ReturnDatasetToAuthorCommand(dataverseRequest, null, ""));
    }

    @Test
    public void testReleasedDataset() {
        dataset.getLatestVersion().setVersionState(DatasetVersion.VersionState.RELEASED);
//        dataset.getLatestVersion().setInReview(true);
        String expected = "This data project cannot be return to the author(s) because the latest version is not In Review. The author(s) needs to click Submit for Review first.";
        String actual = null;
        Dataset updatedDataset = null;
        try {
            updatedDataset = testEngine.submit(new ReturnDatasetToAuthorCommand(dataverseRequest, dataset, "Update Your Files, Dummy"));
        } catch (CommandException ex) {
            actual = ex.getMessage();
        }
        assertEquals(expected, actual);

    }

    @Test
    public void testNotInReviewDataset() {
        dataset.getLatestVersion().setVersionState(DatasetVersion.VersionState.DRAFT);
//        dataset.getLatestVersion().setInReview(false);
        String expected = "This data project cannot be return to the author(s) because the latest version is not In Review. The author(s) needs to click Submit for Review first.";
        String actual = null;
        Dataset updatedDataset = null;
        try {
            updatedDataset = testEngine.submit(new ReturnDatasetToAuthorCommand(dataverseRequest, dataset, "Update Your Files, Dummy"));
        } catch (CommandException ex) {
            actual = ex.getMessage();
        }
        assertEquals(expected, actual);
    }

    @Test
    @JvmSetting(key = JvmSettings.FEATURE_FLAG, value = "false", varArgs = "disable-return-to-author-reason")
    public void testEmptyOrNullComment(){
        dataset.getLatestVersion().setVersionState(DatasetVersion.VersionState.DRAFT);
        Dataset updatedDataset = null;
        String expected = BundleUtil.getStringFromBundle("dataset.reject.commentNull");
        String actual = null;
        try {
            testEngine.submit( new AddLockCommand(dataverseRequest, dataset,
                    new DatasetLock(DatasetLock.Reason.InReview, dataverseRequest.getAuthenticatedUser())));

            assertThrowsExactly(IllegalArgumentException.class,
                    () -> new ReturnDatasetToAuthorCommand(dataverseRequest, dataset, null), expected);
            assertThrowsExactly(IllegalArgumentException.class,
                    () -> new ReturnDatasetToAuthorCommand(dataverseRequest, dataset, ""), expected);
            updatedDataset = testEngine.submit(new ReturnDatasetToAuthorCommand(dataverseRequest, dataset, ""));
        } catch (IllegalArgumentException | CommandException ex) {
            actual = ex.getMessage();
        }
        assertEquals(expected, actual);
    }

    /** Test the disable reason flag
     * @throws Exception when the test is in error.
     */
    @Test
    @JvmSetting(key = JvmSettings.FEATURE_FLAG, value = "true", varArgs = "disable-return-to-author-reason")
    public void testEmptyOrNullCommentWhenDisabled() throws Exception {
        dataset.getLatestVersion().setVersionState(DatasetVersion.VersionState.DRAFT);
        Dataset updatedDataset = null;

        testEngine.submit(new AddLockCommand(dataverseRequest, dataset,
                new DatasetLock(DatasetLock.Reason.InReview, dataverseRequest.getAuthenticatedUser())));

        updatedDataset = testEngine.submit(new ReturnDatasetToAuthorCommand(dataverseRequest, dataset, null));
        assertNotNull(updatedDataset);
        testEngine.submit(new AddLockCommand(dataverseRequest, dataset,
                new DatasetLock(DatasetLock.Reason.InReview, dataverseRequest.getAuthenticatedUser())));
        updatedDataset = testEngine.submit(new ReturnDatasetToAuthorCommand(dataverseRequest, dataset, ""));
        assertNotNull(updatedDataset);
    }
    
    
   @Test
    public void testAllGood() {
       dataset.getLatestVersion().setVersionState(DatasetVersion.VersionState.DRAFT);
       Dataset updatedDataset = null;
       try {
           testEngine.submit( new AddLockCommand(dataverseRequest, dataset, 
                              new DatasetLock(DatasetLock.Reason.InReview, dataverseRequest.getAuthenticatedUser())));
           updatedDataset = testEngine.submit(new ReturnDatasetToAuthorCommand(dataverseRequest, dataset, "Update Your Files, Dummy"));
       } catch (CommandException ex) {
            System.out.println("Error updating data project: " + ex.getMessage() );
       }
        assertNotNull(updatedDataset);
    }

}
