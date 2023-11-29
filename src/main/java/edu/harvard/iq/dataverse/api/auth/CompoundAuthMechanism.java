package edu.harvard.iq.dataverse.api.auth;

import edu.harvard.iq.dataverse.authorization.users.GuestUser;
import edu.harvard.iq.dataverse.authorization.users.User;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author Guillermo Portas
 * Compound authentication mechanism that attempts to authenticate a user through the different authentication mechanisms (ordered by priority) of which it is composed.
 * If no user is returned from any of the inner authentication mechanisms, a Guest user is returned.
 */
public class CompoundAuthMechanism implements AuthMechanism {

    private final List<AuthMechanism> authMechanisms = new ArrayList<>();

    private static final Logger logger = Logger.getLogger(CompoundAuthMechanism.class.getCanonicalName());

    @Inject
    public CompoundAuthMechanism(ApiKeyAuthMechanism apiKeyAuthMechanism, WorkflowKeyAuthMechanism workflowKeyAuthMechanism, SignedUrlAuthMechanism signedUrlAuthMechanism, SessionCookieAuthMechanism sessionCookieAuthMechanism, BearerTokenAuthMechanism bearerTokenAuthMechanism) {
        // Auth mechanisms should be ordered by priority here
        add(apiKeyAuthMechanism, workflowKeyAuthMechanism, signedUrlAuthMechanism, sessionCookieAuthMechanism,bearerTokenAuthMechanism);
    }

    public CompoundAuthMechanism(AuthMechanism... authMechanisms) {
        add(authMechanisms);
    }

    public void add(AuthMechanism... authMechanisms) {
        this.authMechanisms.addAll(Arrays.asList(authMechanisms));
    }

    @Override
    public User findUserFromRequest(ContainerRequestContext containerRequestContext) throws WrappedAuthErrorResponse {
        User user = null;
        for (AuthMechanism authMechanism : authMechanisms) {
            User userFromRequest = authMechanism.findUserFromRequest(containerRequestContext);
            if (userFromRequest != null) {
                user = userFromRequest;
                break;
            }
        }
        if (user == null) {
            user = GuestUser.get();
        }
        return user;
    }
}
