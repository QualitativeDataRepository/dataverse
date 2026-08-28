package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.settings.JvmSettings;
import org.apache.solr.client.solrj.impl.SolrClientCustomizer;
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient;
import org.eclipse.jetty.client.HttpClient;

/**
 * Customizer for Solr Jetty HttpClient to increase header size limits.
 * Required for SolrJ 10.0.0 which doesn't provide a direct API to configure the internal HttpClient.
 * See https://github.com/IQSS/dataverse/issues/10667
 */
public class DataverseSolrClientCustomizer implements SolrClientCustomizer<HttpJettySolrClient> {

    @Override
    public void setup(HttpJettySolrClient client) {
        HttpClient jettyClient = client.getHttpClient();
        if (jettyClient != null) {
            // Lookup the max header size from JVM settings, defaulting to 102400 (100KB)
            int maxHeaderSize = JvmSettings.SOLR_MAX_HEADER_SIZE.lookupOptional(Integer.class).orElse(102400);
            jettyClient.setMaxResponseHeadersSize(maxHeaderSize);
            jettyClient.setMaxRequestHeadersSize(maxHeaderSize);
        }
    }
}
