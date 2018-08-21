package edu.harvard.iq.dataverse.export;

import com.google.auto.service.AutoService;
import com.google.gson.JsonParser;

import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.export.spi.Exporter;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import edu.harvard.iq.dataverse.export.ExportException;
import edu.harvard.iq.dataverse.util.BagGenerator;
import edu.harvard.iq.dataverse.Dataset;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import javax.ejb.EJB;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;

import com.google.gson.JsonObject;


public class BagIt_Export {

	@EJB
	SettingsServiceBean settingsService;

	private static final Logger logger = Logger.getLogger(BagIt_Export.class.getCanonicalName());

	public static final String NAME = "BagIt";

	public static void exportDatasetVersionAsBag(DatasetVersion version, SettingsServiceBean settingsService, OutputStream outputStream)
			throws Exception {

			Dataset dataset = version.getDataset();
			InputStream mapInputStream = ExportService.getInstance(settingsService).getExport(dataset,
					OAI_OREExporter.NAME);
			JsonParser jsonParser = new JsonParser();
			JsonObject oremap = (JsonObject) jsonParser.parse(new InputStreamReader(mapInputStream, "UTF-8"));

			BagGenerator bagger = new BagGenerator(oremap);
			bagger.setIgnoreHashes(false); //true would force sha256 computation
			bagger.generateBag(outputStream);
	}
}
