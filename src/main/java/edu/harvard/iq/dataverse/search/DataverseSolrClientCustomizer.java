package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import org.apache.solr.client.solrj.impl.SolrClientCustomizer;
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient;
import org.eclipse.jetty.client.HttpClient;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Customizer for Solr Jetty HttpClient to increase header size limits.
 * Required for SolrJ 10.0.0 which doesn't provide a direct API to configure the internal HttpClient.
 * See https://github.com/IQSS/dataverse/issues/10667
 */
public class DataverseSolrClientCustomizer implements SolrClientCustomizer<HttpJettySolrClient> {
    private static final Logger logger = Logger.getLogger(DataverseSolrClientCustomizer.class.getCanonicalName());

    @Override
    public void setup(HttpJettySolrClient client) {
        HttpClient jettyClient = client.getHttpClient();
        if (jettyClient != null) {
            // Lookup the max header size from JVM settings, defaulting to 102400 (100KB)
            int maxHeaderSize = JvmSettings.SOLR_MAX_HEADER_SIZE.lookupOptional(Integer.class).orElse(102400);
            logger.log(Level.INFO, "Customizing Solr Jetty HttpClient with maxHeaderSize={0}", maxHeaderSize);

            jettyClient.setMaxResponseHeadersSize(maxHeaderSize);
            jettyClient.setMaxRequestHeadersSize(maxHeaderSize);

            // Configure HTTP/2 transport if present (using reflection to avoid compile-time dependency on runtime-only transport jar)
            Object transport = jettyClient.getTransport();
            if (transport != null) {
                try {
                    Class<?> transportClass = transport.getClass();
                    if (transportClass.getName().equals("org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2")) {
                        Method getH2ClientMethod = transportClass.getMethod("getHTTP2Client");
                        Object h2Client = getH2ClientMethod.invoke(transport);
                        if (h2Client != null) {
                            // In Jetty 12, HTTP2Client has these methods to control HPACK and header limits
                            boolean success = false;

                            // Try multiple possible method names for header limits in various Jetty versions
                            // Some versions use setInitialMaxHeaderListSize, others use setMaxRequestHeadersSize/setMaxResponseHeadersSize
                            String[] methodNames = {
                                "setInitialMaxHeaderListSize",
                                "setMaxRequestHeadersSize",
                                "setMaxResponseHeadersSize",
                                "setMaxHeaderBlockFragment"
                            };

                            for (String methodName : methodNames) {
                                try {
                                    Method m = h2Client.getClass().getMethod(methodName, int.class);
                                    m.invoke(h2Client, maxHeaderSize);
                                    logger.log(Level.FINE, "Set {0} on HTTP/2 client", methodName);
                                    success = true;
                                } catch (NoSuchMethodException e) {
                                    // Method not found, try next one
                                }
                            }

                            if (success) {
                                logger.log(Level.INFO, "Successfully configured HTTP/2 header limits on {0}", h2Client.getClass().getName());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to configure HTTP/2 header limits via reflection", e);
                }
            }
        }
    }
}
