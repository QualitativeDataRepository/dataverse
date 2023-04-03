package edu.harvard.iq.dataverse.authorization;

import edu.harvard.iq.dataverse.authorization.providers.oauth2.AbstractOAuth2AuthenticationProvider;
import edu.harvard.iq.dataverse.authorization.providers.oauth2.oidc.OIDCAuthProvider;
import edu.harvard.iq.dataverse.util.ClockUtil;
import edu.harvard.iq.dataverse.util.StringUtil;
import edu.harvard.iq.dataverse.util.SystemConfig;

import static edu.harvard.iq.dataverse.util.StringUtil.toOption;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import javax.ejb.EJB;
import javax.inject.Inject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.nimbusds.openid.connect.sdk.Prompt;

public class AuthFilter implements Filter {

    private static final Logger logger = Logger.getLogger(AuthFilter.class.getCanonicalName());

    @EJB
    SystemConfig systemConfig;

    @Inject
    AuthenticationServiceBean authenticationSvc;

    @Inject
    @ClockUtil.LocalTime
    Clock clock;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        logger.info(AuthFilter.class.getName() + "initialized. filterConfig.getServletContext().getServerInfo(): " + filterConfig.getServletContext().getServerInfo());

        try {
            String glassfishLogsDirectory = "logs";

            FileHandler logFile = new FileHandler(".." + File.separator + glassfishLogsDirectory + File.separator + "authfilter.log");
            SimpleFormatter formatterTxt = new SimpleFormatter();
            logFile.setFormatter(formatterTxt);
            logger.addHandler(logFile);
        } catch (IOException ex) {
            Logger.getLogger(AuthFilter.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SecurityException ex) {
            Logger.getLogger(AuthFilter.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        HttpSession httpSession = httpServletRequest.getSession(false);
        String path = httpServletRequest.getRequestURI();
        if (path != null) {
            logger.info("Path: " + path);
        }

        if (path.equals("/") || path.endsWith(".xhtml") && !(path.endsWith("logout.xhtml") || path.contains("javax.faces.resource") || path.contains("/oauth2/callback"))) {

            if ((httpSession != null)) {
                logger.info("check OIDC: " + httpSession.getAttribute("passiveChecked"));
                synchronized (this) {
                    if (httpSession.getAttribute("passiveChecked") == null) {
                        logger.info("really check OIDC");
                        AbstractOAuth2AuthenticationProvider idp = authenticationSvc.getOAuth2Provider("oidc-keycloak");
                        OIDCAuthProvider oidcidp = (OIDCAuthProvider) idp;
                        String state = createState(oidcidp, toOption("https://dv.dev-aws.qdr.org/"));
                        String redirectUrl = oidcidp.buildAuthzUrl(state, systemConfig.getOAuth2CallbackUrl(), Prompt.Type.NONE, -1);
                        logger.info(redirectUrl);
                        HttpServletResponse httpServletResponse = (HttpServletResponse) response;
                        httpSession.setAttribute("passiveChecked", true);

                        String remoteAddr = httpServletRequest.getRemoteAddr();
                        String requestUri = httpServletRequest.getRequestURI();
                        String userAgent = httpServletRequest.getHeader("User-Agent");

                        String separator = "|";

                        StringBuilder sb = new StringBuilder();
                        for (String string : Arrays.asList(remoteAddr, requestUri, userAgent)) {
                            sb.append(string + separator);
                        }

                        logger.info(sb.toString());

                        httpServletResponse.sendRedirect(redirectUrl);
                        return;

                    }
                }
            }
        }

        filterChain.doFilter(servletRequest, response);
    }

    @Override
    public void destroy() {
        throw new UnsupportedOperationException("Not supported yet.");
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
