package edu.harvard.iq.dataverse.makedatacount;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.engine.TestCommandContext;
import edu.harvard.iq.dataverse.engine.command.CommandContext;
import edu.harvard.iq.dataverse.externaltools.ExternalTool;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MakeDataCountUtilTest {

    @Test
    public void testParseSushi() {
        JsonObject report;
        try (FileReader reader = new FileReader("src/test/java/edu/harvard/iq/dataverse/makedatacount/sushi_sample_logs.json")) {
            report = Json.createReader(reader).readObject();
            List<DatasetMetrics> datasetMetrics = parseSushiReport(report);
        } catch (IOException ex) {
            System.out.print("IO exception: " + ex.getMessage());
        } catch (Exception e) {
            System.out.print("Unspecified Exception: " + e.getMessage());
        }
    }

    private List<DatasetMetrics> parseSushiReport(JsonObject report) {
        List<DatasetMetrics> datasetMetricsAll = new ArrayList<>();
        JsonArray reportDatasets = report.getJsonArray("report_datasets");
        for (JsonValue reportDataset : reportDatasets) {
            List<DatasetMetrics> datasetMetricsDataset = new ArrayList<>();
            String globalId = "doi:10.5072/FK2/2OV2YY"; // reportDataset.getValueType("dataset-id");
            Dataset ds = null;
            StringReader rdr = new StringReader(reportDataset.toString());
            JsonReader jrdr = Json.createReader(rdr);
            JsonObject obj = jrdr.readObject();
            String jsonGlobalId = "";
            String globalIdType = "";
            if (obj.containsKey("dataset-id")) {
                JsonArray dsIdArray = obj.getJsonArray("dataset-id");
                JsonObject idObj = dsIdArray.getJsonObject(0);
                System.out.print("idObj: " + idObj);
                jsonGlobalId = idObj.getString("value");
                globalIdType = idObj.getString("type");

            } else {
                System.out.print("Does Not Contain  dataset-id");
            }

            if (obj.containsKey("performance")) {
                JsonArray performance = obj.getJsonArray("performance");
                for (JsonObject perfObj : performance.getValuesAs(JsonObject.class)) {
                    String monthYear = "";
                    JsonObject period = perfObj.getJsonObject("period");
                    monthYear = period.getString("begin-date");
                    JsonArray instanceArray = perfObj.getJsonArray("instance");
                    for (JsonObject instObj : instanceArray.getValuesAs(JsonObject.class)) {
                        if (instObj.getString("metric-type").equals("total-dataset-investigations")) { 
                            List<String[]> totalInvestigations = new ArrayList<>();
                            if (instObj.containsKey("country-counts")) {
                                JsonObject countryCountObj = instObj.getJsonObject("country-counts");
                                totalInvestigations = getCountryCountArray(countryCountObj);                               
                            }
                            if(!totalInvestigations.isEmpty()){
                               for(String[] investigation: totalInvestigations){
                                   DatasetMetrics dm = new DatasetMetrics();
                                   dm.setDataset(ds);
                                   dm.setCountryCode(investigation[0]);
                                   dm.setViewsTotal(new Long(investigation[1]));
                                   dm.setMonth(monthYear);
                                   datasetMetricsDataset.add(dm);
                               }
                            }
                        }
                        if (instObj.getString("metric-type").equals("unique-dataset-investigations")) { //unique-dataset-investigations
                            List<String[]> uniqueInvestigations = new ArrayList<>();
                            if (instObj.containsKey("country-counts")) {
                                JsonObject countryCountObj = instObj.getJsonObject("country-counts");
                                uniqueInvestigations = getCountryCountArray(countryCountObj);                               
                            }
                            List<DatasetMetrics> datasetMetricsUnique = new ArrayList<>();
                            if(!uniqueInvestigations.isEmpty()){
                               for(String[] investigation: uniqueInvestigations){
                                   DatasetMetrics dm = new DatasetMetrics();
                                   dm.setDataset(ds);
                                   dm.setCountryCode(investigation[0]);
                                   dm.setViewsUnique(new Long(investigation[1]));
                                   dm.setMonth(monthYear);
                                   datasetMetricsUnique.add(dm);
                               }
                            }                           
                           datasetMetricsDataset= addUpdateMetrics(datasetMetricsDataset, datasetMetricsUnique , "UniqueViews");
                        }
                        if (instObj.getString("metric-type").equals("total-dataset-requests")) { //unique-dataset-investigations
                            List<String[]> totalRequests = new ArrayList<>();
                            if (instObj.containsKey("country-counts")) {
                                JsonObject countryCountObj = instObj.getJsonObject("country-counts");
                                totalRequests = getCountryCountArray(countryCountObj);                               
                            }
                            List<DatasetMetrics> datasetMetricsRequestsTotal = new ArrayList<>();
                            if(!totalRequests.isEmpty()){
                               for(String[] investigation: totalRequests){
                                   DatasetMetrics dm = new DatasetMetrics();
                                   dm.setDataset(ds);
                                   dm.setCountryCode(investigation[0]);
                                   dm.setDownloadsTotal(new Long(investigation[1]));
                                   dm.setMonth(monthYear);
                                   datasetMetricsRequestsTotal.add(dm);
                               }
                            }                           
                           datasetMetricsDataset= addUpdateMetrics(datasetMetricsDataset, datasetMetricsRequestsTotal , "TotalRequests");
                        }
                        if (instObj.getString("metric-type").equals("unique-dataset-requests")) { //unique-dataset-investigations
                            List<String[]> uniqueRequests = new ArrayList<>();
                            if (instObj.containsKey("country-counts")) {
                                JsonObject countryCountObj = instObj.getJsonObject("country-counts");
                                uniqueRequests = getCountryCountArray(countryCountObj);                               
                            }
                            List<DatasetMetrics> datasetMetricsRequestsTotal = new ArrayList<>();
                            if(!uniqueRequests.isEmpty()){
                               for(String[] investigation: uniqueRequests){
                                   DatasetMetrics dm = new DatasetMetrics();
                                   dm.setDataset(ds);
                                   dm.setCountryCode(investigation[0]);
                                   dm.setDownloadsUnique(new Long(investigation[1]));
                                   dm.setMonth(monthYear);
                                   datasetMetricsRequestsTotal.add(dm);
                               }
                            }                           
                           datasetMetricsDataset= addUpdateMetrics(datasetMetricsDataset, datasetMetricsRequestsTotal , "UniqueRequests");
                        }
                    }
                }
            }
            datasetMetricsAll.addAll(datasetMetricsDataset);
        }
        return datasetMetricsAll;
    }
    
    private List<String[]> getCountryCountArray(JsonObject countryCountObj) {
        List<String[]> retList = new ArrayList<>();
        Set<String> keyValuePair = countryCountObj.keySet();
        for (String key : keyValuePair) {
            Integer value = countryCountObj.getInt(key);
            String countryCode = key;
            String[] datasetContributor = new String[]{countryCode, value.toString()};
            retList.add(datasetContributor);
        }
        return retList;
    }
    
    private List<DatasetMetrics> addUpdateMetrics(List<DatasetMetrics> currentList, List<DatasetMetrics> compareList, String countField){
        
        List<DatasetMetrics> toAdd = new ArrayList();
        
        for (DatasetMetrics testMetric : compareList) {
            
            boolean add = true;
            ListIterator<DatasetMetrics> iterator = currentList.listIterator();
            while (iterator.hasNext()) {
                DatasetMetrics next = iterator.next();
                if (next.getCountryCode().equals(testMetric.getCountryCode())) {
                    //Replace element
                    
                    if (countField.equals("UniqueViews")){
                       next.setViewsUnique(testMetric.getViewsUnique());
                    }
                    
                    if (countField.equals("TotalRequests")){
                       next.setDownloadsTotal(testMetric.getDownloadsTotal());
                    }
                    
                    if (countField.equals("UniqueRequests")){
                       next.setDownloadsUnique(testMetric.getDownloadsUnique());
                    }

                    iterator.set(next);
                    add = false;
                }
            }
            if(add){
               toAdd.add(testMetric);
            }
        }
        
        if(!toAdd.isEmpty()){
            currentList.addAll(toAdd);
        }
        
        return currentList;
    }
}
