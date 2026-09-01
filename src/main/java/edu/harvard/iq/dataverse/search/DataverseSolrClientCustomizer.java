package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import org.apache.solr.client.solrj.impl.SolrClientCustomizer;
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Customizer for Solr Jetty HttpClient to increase header size limits.
 * Required for SolrJ 10.0.0 which doesn't provide a direct API to configure the internal HttpClient.
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

            HttpClientTransport clientTransport = jettyClient.getTransport();
            if (clientTransport instanceof HttpClientTransportOverHTTP2 h2ClientTransport) {
                HTTP2Client h2Client = h2ClientTransport.getHTTP2Client();
                h2Client.setMaxRequestHeadersSize(maxHeaderSize);
                h2Client.setMaxRequestHeadersSize(maxHeaderSize);
                h2Client.setMaxHeaderBlockFragment(maxHeaderSize);
                logger.log(Level.INFO, "Successfully configured HTTP/2 header limits on {0}", h2Client.getClass().getName());
            }
        }
    }
}
