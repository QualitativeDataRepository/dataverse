package edu.harvard.iq.dataverse.makedatacount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import edu.harvard.iq.dataverse.makedatacount.DatasetExternalCitationsServiceBean;
import edu.harvard.iq.dataverse.util.json.JsonUtil;
import jakarta.json.JsonArray;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.DatasetVersionUser;
import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;

public class DatasetExternalCitationsServiceBeanTest {

  private DatasetExternalCitationsServiceBean datasetExternalCitationsServiceBean;

  @BeforeEach
  public void setUp() {
    this.datasetExternalCitationsServiceBean = new DatasetExternalCitationsServiceBean();
    datasetExternalCitationsServiceBean.datasetService = mock(DatasetServiceBean.class);
  }

  @AfterEach
  public void tearDown() {
    this.datasetExternalCitationsServiceBean = null;
  }

  @Test
  public void testParsing() {
    
    Mockito.when(datasetExternalCitationsServiceBean.datasetService.findByGlobalId(matches(""))).thenAnswer(invocation -> { return new Dataset();});
    JsonArray events = JsonUtil.getJsonArray("["
            + "{"
            + "\"id\": \"ab45438a-5833-46f8-82af-79e0fe809b30\","
            + "\"type\": \"events\","
            + "\"attributes\": {"
            + "\"subj-id\": \"https://doi.org/10.5064/f6nju10i\","
            + "\"obj-id\": \"https://doi.org/10.1080/15305058.2018.1551223\","
            + "\"source-id\": \"datacite-crossref\","
            + "\"relation-type-id\": \"is-supplement-to\","
            + "\"total\": 1,"
            + "\"message-action\": \"create\","
            + "\"source-token\": \"28276d12-b320-41ba-9272-bb0adc3466ff\","
            + "\"license\": \"https://creativecommons.org/publicdomain/zero/1.0/\","
            + "\"occurred-at\": \"2021-03-26T19:34:28.000Z\","
            + "\"timestamp\": \"2021-03-26T19:36:03.273Z\""
            + "},"
            + "\"relationships\": {"
            + "\"subj\": {"
            + "\"data\": {"
            + "\"id\": \"https://doi.org/10.5064/f6nju10i\","
            + "\"type\": \"objects\""
            + "}"
            + "},"
            + "\"obj\": {"
            + "\"data\": {"
            + "\"id\": \"https://doi.org/10.1080/15305058.2018.1551223\","
            + "\"type\": \"objects\""
            + "}"
            + "}"
            + "}"
            + "},"
            + "{"
            + "\"id\": \"621fc6e0-8099-4044-8ed3-ad27c82f11ba\","
            + "\"type\": \"events\","
            + "\"attributes\": {"
            + "\"subj-id\": \"https://doi.org/10.5064/f6nju10i\","
            + "\"obj-id\": \"https://doi.org/10.5064/f6nju10i/m4fjyi\","
            + "\"source-id\": \"datacite-related\","
            + "\"relation-type-id\": \"has-part\","
            + "\"total\": 1,"
            + "\"message-action\": \"create\","
            + "\"source-token\": \"29a9a478-518f-4cbd-a133-a0dcef63d547\","
            + "\"license\": \"https://creativecommons.org/publicdomain/zero/1.0/\","
            + "\"occurred-at\": \"2021-03-26T19:34:28.000Z\","
            + "\"timestamp\": \"2021-03-26T19:36:03.641Z\""
            + "},"
            + "\"relationships\": {"
            + "\"subj\": {"
            + "\"data\": {"
            + "\"id\": \"https://doi.org/10.5064/f6nju10i\","
            + "\"type\": \"objects\""
            + "}"
            + "},"
            + "\"obj\": {"
            + "\"data\": {"
            + "\"id\": \"https://doi.org/10.5064/f6nju10i/m4fjyi\","
            + "\"type\": \"objects\""
            + "}"
            + "}"
            + "}"
            + "}]");
    List<DatasetExternalCitations> extCitations = datasetExternalCitationsServiceBean.parseCitations(events);
    assertEquals(1, extCitations.size());
  }
}