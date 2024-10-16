package edu.harvard.iq.dataverse.authorization;

import edu.harvard.iq.dataverse.authorization.providers.oauth2.AbstractOAuth2AuthenticationProvider;
import edu.harvard.iq.dataverse.authorization.providers.oauth2.oidc.OIDCAuthProvider;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.util.ClockUtil;
import edu.harvard.iq.dataverse.util.StringUtil;
import edu.harvard.iq.dataverse.util.SystemConfig;

import static edu.harvard.iq.dataverse.util.StringUtil.toOption;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;

import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.HttpMethod;

import com.nimbusds.openid.connect.sdk.Prompt;

public class AuthFilter implements Filter {

    private static final Logger logger = Logger.getLogger(AuthFilter.class.getCanonicalName());

    @EJB
    SystemConfig systemConfig;
    
    @EJB
    SettingsServiceBean settingsService;
    
    @Inject
    AuthenticationServiceBean authenticationSvc;

    @Inject
    @ClockUtil.LocalTime
    Clock clock;
    

    //QDR setting for the Drupal URL
    private String drupalUrl;
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        logger.fine(AuthFilter.class.getName() + "initialized. filterConfig.getServletContext().getServerInfo(): " + filterConfig.getServletContext().getServerInfo());
        drupalUrl = settingsService.getValueForKey(SettingsServiceBean.Key.QDRDrupalSiteURL);
        logger.fine("Setting Drupal URl to : " + drupalUrl);
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        synchronized (this) {
            HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
            HttpSession httpSession = httpServletRequest.getSession(false);
            String path = httpServletRequest.getRequestURI();
            String uaHeader = httpServletRequest.getHeader("user-agent");
            //Nagios uses a user-agent starting with check_http and we don't want to do a passive login check in that case.
            boolean isCheck = (uaHeader != null) && (uaHeader.contains("check_http") || StringUtils.containsIgnoreCase(uaHeader, "bot") || StringUtils.containsIgnoreCase(uaHeader, "google"));
            //boolean hasAuthToken = httpServletRequest.getParameter("key") != null) || (httpServletRequest.getParameter("token")!= null)  || httpServletRequest.getHeader('X-Dataverse-key');
            //~QDR specific - a means to reset the passiveChecked flag so the next access will try passive login again
            //If the origin were configurable, this might be useful in general
            boolean ssoResetPath = path.equals("/ssoreset");
            if(!isCheck && ssoResetPath) {
                ((HttpServletResponse) response).addHeader("Access-Control-Allow-Origin", drupalUrl);
                ((HttpServletResponse) response).addHeader("Access-Control-Allow-Methods", "GET");
                ((HttpServletResponse) response).addHeader("Access-Control-Allow-Credentials", "true");

                logger.fine("passiveChecked flag check");
                if ((httpSession != null) && (httpSession.getAttribute("passiveChecked") != null)) {
                    logger.fine("resetting passiveChecked flag");
                    httpSession.removeAttribute("passiveChecked");
                }
                if(httpSession==null) {
                    logger.fine("No session");
                } else if (httpSession.getAttribute("passiveChecked") != null) {
                    logger.warning("passiveChecked flag still set");
                }
                //After resetting, just return with no content
                HttpServletResponse httpServletResponse = (HttpServletResponse) response;
                httpServletResponse.setStatus(200);
                return;
            }
            if ((httpServletRequest.getMethod() == HttpMethod.GET) && !isCheck && (path.equals("/") || path.endsWith(".xhtml") && !(path.endsWith("logout.xhtml")|| path.endsWith("privateurl.xhtml") || path.contains("jakarta.faces.resource") || path.contains("/oauth2/callback")))) {
                logger.fine("Path: " + path);
                String sso = httpServletRequest.getParameter("sso");
                //Going to /
                if ((httpSession == null) || (httpSession.getAttribute("passiveChecked") == null) || (sso != null)) {
                    if (httpSession != null) {
                        logger.fine("check OIDC: " + httpSession.getAttribute("passiveChecked"));
                    }
                    logger.fine("really check OIDC");
                    AbstractOAuth2AuthenticationProvider idp = authenticationSvc.getOAuth2Provider("oidc-keycloak");
                    OIDCAuthProvider oidcidp = (OIDCAuthProvider) idp;
                    // Create URL for the final destination after successful login
                    // Drop sso parameter if present
                    String qp = httpServletRequest.getQueryString();
                    if (qp != null) {
                        qp = qp.replaceFirst("[&]*sso=true", "");
                    }
                    String finalDestination = (qp == null || qp.isBlank()) ? httpServletRequest.getRequestURL().toString() : httpServletRequest.getRequestURL().append("?").append(qp).toString();

                    String state = createState(oidcidp, toOption(finalDestination));
                    String redirectUrl = oidcidp.buildAuthzUrl(state, systemConfig.getOAuth2CallbackUrl(), Prompt.Type.NONE, -1);
                    logger.fine(redirectUrl);
                    HttpServletResponse httpServletResponse = (HttpServletResponse) response;
                    if (httpSession == null) {
                        logger.fine("check OIDC: no session");
                        httpSession = httpServletRequest.getSession(true);
                    }
                    httpSession.setAttribute("passiveChecked", true);
                    String remoteAddr = httpServletRequest.getRemoteAddr();
                    String requestUri = httpServletRequest.getRequestURI();
                    String userAgent = httpServletRequest.getHeader("User-Agent");

                    String separator = "|";

                    StringBuilder sb = new StringBuilder();
                    for (String string : Arrays.asList(remoteAddr, requestUri, userAgent)) {
                        sb.append(string + separator);
                    }

                    logger.fine(sb.toString());
                    httpServletResponse.sendRedirect(redirectUrl);
                    return;

                }
            }
        }

        filterChain.doFilter(servletRequest, response);
    }

    @Override
    public void destroy() {
        //throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Create a randomized unique state string to be used while crafting the
     * authorization request
     * 
     * @param idp
     * @param redirectPage
     * @return Random state string, composed from system time, random numbers and
     *         redirectPage parameter
     */
    private String createState(AbstractOAuth2AuthenticationProvider idp, Optional<String> redirectPage) {
        if (idp == null) {
            throw new IllegalArgumentException("idp cannot be null");
        }
        SecureRandom rand = new SecureRandom();

        String base = idp.getId() + "~" + this.clock.millis()
                + "~" + rand.nextInt(1000)
                + redirectPage.map(page -> "~" + page).orElse("");

        String encrypted = StringUtil.encrypt(base, idp.getClientSecret());
        final String state = idp.getId() + "~" + encrypted;
        return state;
    }

}
