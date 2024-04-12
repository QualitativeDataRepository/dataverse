package edu.harvard.iq.dataverse.pidproviders.doi;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.text.StringEscapeUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetAuthor;
import edu.harvard.iq.dataverse.DatasetRelPublication;
import edu.harvard.iq.dataverse.DvObject;
import edu.harvard.iq.dataverse.pidproviders.AbstractPidProvider;

public class XmlMetadataTemplate {

    private static final Logger logger = Logger.getLogger("edu.harvard.iq.dataverse.DataCiteMetadataTemplate");
    private static String template;

    static {
        try (InputStream in = XmlMetadataTemplate.class.getResourceAsStream("datacite_metadata_template.xml")) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "datacite metadata template load error");
            logger.log(Level.SEVERE, "String " + e.toString());
            logger.log(Level.SEVERE, "localized message " + e.getLocalizedMessage());
            logger.log(Level.SEVERE, "cause " + e.getCause());
            logger.log(Level.SEVERE, "message " + e.getMessage());
        }
    }

    private String xmlMetadata;
    private String identifier;
    private List<String> datafileIdentifiers;
    private List<String> creators;
    private String title;
    private String publisher;
    private String publisherYear;
    private List<DatasetAuthor> authors;
    private String description;
    private List<String[]> contacts;
    private List<String[]> producers;

    public List<String[]> getProducers() {
        return producers;
    }

    public void setProducers(List<String[]> producers) {
        this.producers = producers;
    }

    public List<String[]> getContacts() {
        return contacts;
    }

    public void setContacts(List<String[]> contacts) {
        this.contacts = contacts;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<DatasetAuthor> getAuthors() {
        return authors;
    }

    public void setAuthors(List<DatasetAuthor> authors) {
        this.authors = authors;
    }

    public XmlMetadataTemplate() {
    }

    public List<String> getDatafileIdentifiers() {
        return datafileIdentifiers;
    }

    public void setDatafileIdentifiers(List<String> datafileIdentifiers) {
        this.datafileIdentifiers = datafileIdentifiers;
    }

    public XmlMetadataTemplate(String xmlMetaData) {
        this.xmlMetadata = xmlMetaData;
        Document doc = Jsoup.parseBodyFragment(xmlMetaData);
        Elements identifierElements = doc.select("identifier");
        if (identifierElements.size() > 0) {
            identifier = identifierElements.get(0).html();
        }
        Elements creatorElements = doc.select("creatorName");
        creators = new ArrayList<>();
        for (Element creatorElement : creatorElements) {
            creators.add(creatorElement.html());
        }
        Elements titleElements = doc.select("title");
        if (titleElements.size() > 0) {
            title = titleElements.get(0).html();
        }
        Elements publisherElements = doc.select("publisher");
        if (publisherElements.size() > 0) {
            publisher = publisherElements.get(0).html();
        }
        Elements publisherYearElements = doc.select("publicationYear");
        if (publisherYearElements.size() > 0) {
            publisherYear = publisherYearElements.get(0).html();
        }
    }

    public String generateXML(DvObject dvObject) {
        // Can't use "UNKNOWN" here because DataCite will respond with "[facet
        // 'pattern'] the value 'unknown' is not accepted by the pattern '[\d]{4}'"
        String publisherYearFinal = "9999";
        // FIXME: Investigate why this.publisherYear is sometimes null now that pull
        // request #4606 has been merged.
        if (this.publisherYear != null) {
            // Added to prevent a NullPointerException when trying to destroy datasets when
            // using DataCite rather than EZID.
            publisherYearFinal = this.publisherYear;
        }
        xmlMetadata = template.replace("${identifier}", getIdentifier().trim()).replace("${title}", this.title)
                .replace("${publisher}", this.publisher).replace("${publisherYear}", publisherYearFinal)
                .replace("${description}", this.description);

        StringBuilder creatorsElement = new StringBuilder();
        if (authors != null && !authors.isEmpty()) {
            for (DatasetAuthor author : authors) {
                creatorsElement.append("<creator><creatorName>");
                creatorsElement.append(StringEscapeUtils.escapeXml10(author.getName().getDisplayValue()));
                creatorsElement.append("</creatorName>");

                if (author.getIdType() != null && author.getIdValue() != null && !author.getIdType().isEmpty()
                        && !author.getIdValue().isEmpty() && author.getAffiliation() != null
                        && !author.getAffiliation().getDisplayValue().isEmpty()) {

                    if (author.getIdType().equals("ORCID")) {
                        creatorsElement.append(
                                "<nameIdentifier schemeURI=\"https://orcid.org/\" nameIdentifierScheme=\"ORCID\">"
                                        + author.getIdValue() + "</nameIdentifier>");
                    }
                    if (author.getIdType().equals("ISNI")) {
                        creatorsElement.append(
                                "<nameIdentifier schemeURI=\"http://isni.org/isni/\" nameIdentifierScheme=\"ISNI\">"
                                        + author.getIdValue() + "</nameIdentifier>");
                    }
                    if (author.getIdType().equals("LCNA")) {
                        creatorsElement.append(
                                "<nameIdentifier schemeURI=\"http://id.loc.gov/authorities/names/\" nameIdentifierScheme=\"LCNA\">"
                                        + author.getIdValue() + "</nameIdentifier>");
                    }
                }
                if (author.getAffiliation() != null && !author.getAffiliation().getDisplayValue().isEmpty()) {
                    creatorsElement
                            .append("<affiliation>" + StringEscapeUtils.escapeXml10(author.getAffiliation().getDisplayValue()) + "</affiliation>");
                }
                creatorsElement.append("</creator>");
            }

        } else {
            creatorsElement.append("<creator><creatorName>").append(AbstractPidProvider.UNAVAILABLE)
                    .append("</creatorName></creator>");
        }

        xmlMetadata = xmlMetadata.replace("${creators}", creatorsElement.toString());

        StringBuilder contributorsElement = new StringBuilder();
        if (this.getContacts() != null) {
            for (String[] contact : this.getContacts()) {
                if (!contact[0].isEmpty()) {
                    contributorsElement.append("<contributor contributorType=\"ContactPerson\"><contributorName>"
                            + StringEscapeUtils.escapeXml10(contact[0]) + "</contributorName>");
                    if (!contact[1].isEmpty()) {
                        contributorsElement.append("<affiliation>" + StringEscapeUtils.escapeXml10(contact[1]) + "</affiliation>");
                    }
                    contributorsElement.append("</contributor>");
                }
            }
        }

        if (this.getProducers() != null) {
            for (String[] producer : this.getProducers()) {
                contributorsElement.append("<contributor contributorType=\"Producer\"><contributorName>" + StringEscapeUtils.escapeXml10(producer[0])
                        + "</contributorName>");
                if (!producer[1].isEmpty()) {
                    contributorsElement.append("<affiliation>" + StringEscapeUtils.escapeXml10(producer[1]) + "</affiliation>");
                }
                contributorsElement.append("</contributor>");
            }
        }

        String relIdentifiers = generateRelatedIdentifiers(dvObject);

        xmlMetadata = xmlMetadata.replace("${relatedIdentifiers}", relIdentifiers);

        xmlMetadata = xmlMetadata.replace("{$contributors}", contributorsElement.toString());
        return xmlMetadata;
    }

    private String generateRelatedIdentifiers(DvObject dvObject) {

        StringBuilder sb = new StringBuilder();
        if (dvObject.isInstanceofDataset()) {
            Dataset dataset = (Dataset) dvObject;
            List<DatasetRelPublication> relatedPublications = dataset.getLatestVersionForCopy().getRelatedPublications();
            if (!relatedPublications.isEmpty()) {
                for (DatasetRelPublication relatedPub : relatedPublications) {
                    String pubIdType = relatedPub.getIdType();
                    String identifier = relatedPub.getIdNumber();
                    /*
                     * Note - with identifier and url fields, it's not clear that there's a single
                     * way those two fields are used for all identifier types In QDR, at this time,
                     * doi and isbn types always have the raw number in the identifier field,
                     * whereas there are examples where URLs are in the identifier or url fields.
                     * The code here addresses those practices and is not generic.
                     */
                    if(pubIdType!=null) {
                    switch (pubIdType) {
                    case "doi":
                        if (identifier != null && identifier.length() != 0) {
                            appendIdentifier(sb, "DOI", "IsSupplementTo", "doi:" + identifier);
                        }
                        break;
                    case "isbn":
                        if (identifier != null && identifier.length() != 0) {
                            appendIdentifier(sb, "ISBN", "IsSupplementTo", "ISBN:" + identifier);
                        }
                        break;
                    case "url":
                        if (identifier != null && identifier.length() != 0) {
                            appendIdentifier(sb, "URL", "IsSupplementTo", identifier);
                        } else {
                            String pubUrl = relatedPub.getUrl();
                            if (pubUrl != null && pubUrl.length() > 0) {
                                appendIdentifier(sb, "URL", "IsSupplementTo", pubUrl);
                            }
                        }
                        break;
                    default:
                        if (identifier != null && identifier.length() != 0) {
                            if (pubIdType.equalsIgnoreCase("arXiv")) {
                                pubIdType = "arXiv";
                            } else if (pubIdType.equalsIgnoreCase("handle")) {
                                //Initial cap required for handle
                                pubIdType="Handle";
                            } else if (!pubIdType.equals("bibcode")) {
                                pubIdType = pubIdType.toUpperCase();
                            }
                            // For all others, do a generic attempt to match the identifier type to the
                            // datacite schema and send the raw identifier as the value
                            appendIdentifier(sb, pubIdType, "IsSupplementTo", identifier);
                        }
                        break;
                    }
                    
                } else {
                    logger.info(relatedPub.getIdNumber() + relatedPub.getUrl() + relatedPub.getTitle());
                }
                }
            }

            if (!dataset.getFiles().isEmpty() && !(dataset.getFiles().get(0).getIdentifier() == null)) {

                datafileIdentifiers = new ArrayList<>();
                for (DataFile dataFile : dataset.getFiles()) {
                    if (dataFile.getGlobalId() != null) {
                        appendIdentifier(sb, "DOI", "HasPart", dataFile.getGlobalId().asString());
                    }
                }

                if (!sb.toString().isEmpty()) {
                    sb.append("</relatedIdentifiers>");
                }
            }
        } else if (dvObject.isInstanceofDataFile()) {
            DataFile df = (DataFile) dvObject;
            appendIdentifier(sb, "DOI", "IsPartOf", df.getOwner().getGlobalId().asString());
            if (sb.length() != 0) {
                //Should always be true
                sb.append("</relatedIdentifiers>");
            }
        }
        return sb.toString();
    }
    
    private void appendIdentifier(StringBuilder sb, String idType, String relationType, String identifier) {
        if (sb.toString().isEmpty()) {
            sb.append("<relatedIdentifiers>");
        }
        sb.append("<relatedIdentifier relatedIdentifierType=\"" + idType + "\" relationType=\"" + relationType + "\">" + identifier + "</relatedIdentifier>");
    }


    public void generateFileIdentifiers(DvObject dvObject) {

        if (dvObject.isInstanceofDataset()) {
            Dataset dataset = (Dataset) dvObject;

            if (!dataset.getFiles().isEmpty() && !(dataset.getFiles().get(0).getIdentifier() == null)) {

                datafileIdentifiers = new ArrayList<>();
                for (DataFile dataFile : dataset.getFiles()) {
                    datafileIdentifiers.add(dataFile.getIdentifier());
                    int x = xmlMetadata.indexOf("</relatedIdentifiers>") - 1;
                    xmlMetadata = xmlMetadata.replace("{relatedIdentifier}", dataFile.getIdentifier());
                    xmlMetadata = xmlMetadata.substring(0, x) + "<relatedIdentifier relatedIdentifierType=\"hasPart\" "
                            + "relationType=\"doi\">${relatedIdentifier}</relatedIdentifier>"
                            + template.substring(x, template.length() - 1);

                }

            } else {
                xmlMetadata = xmlMetadata.replace(
                        "<relatedIdentifier relatedIdentifierType=\"hasPart\" relationType=\"doi\">${relatedIdentifier}</relatedIdentifier>",
                        "");
            }
        }
    }

    public static String getTemplate() {
        return template;
    }

    public static void setTemplate(String template) {
        XmlMetadataTemplate.template = template;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public List<String> getCreators() {
        return creators;
    }

    public void setCreators(List<String> creators) {
        this.creators = creators;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public String getPublisherYear() {
        return publisherYear;
    }

    public void setPublisherYear(String publisherYear) {
        this.publisherYear = publisherYear;
    }

}