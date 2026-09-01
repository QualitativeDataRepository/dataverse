package edu.harvard.iq.dataverse.search;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.ejb.Singleton;
import jakarta.inject.Named;
import java.util.logging.Logger;

/**
 *
 * @author landreev
 * 
 * This singleton is dedicated to initializing the Http2SolrClient, used by
 * the application to talk to the search engine, and serving it to all the
 * other classes that need it.
 * This ensures that we are using one client only - as recommended by the 
 * documentation. 
 * 
 * ToDo - investigate use of ConcurrentUpdateHttp2SolrClient for indexing.
 */
@Named
@Singleton
public class SolrClientService extends AbstractSolrClientService {
    private static final Logger logger = Logger.getLogger(SolrClientService.class.getCanonicalName());
    
    private SolrClient solrClient;
    
    @PostConstruct
    public void init() {
        HttpJettySolrClient client = new HttpJettySolrClient.Builder(getSolrUrl())
            .withDefaultCollection(getSolrCollection())
            .build();
        //new DataverseSolrClientCustomizer().setup(client);
        solrClient = client;
    }
    
    @PreDestroy
    public void close() {
        close(solrClient);
    }

    public SolrClient getSolrClient() {
        // Should never happen - but? 
        if (solrClient == null) {
            init(); 
        }
        return solrClient;
    }

    public void setSolrClient(SolrClient solrClient) {
        this.solrClient = solrClient;
    }
}
