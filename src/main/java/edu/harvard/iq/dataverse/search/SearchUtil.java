package edu.harvard.iq.dataverse.search;

import edu.harvard.iq.dataverse.api.Util;
import edu.harvard.iq.dataverse.util.BundleUtil;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.StringTokenizer;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.common.SolrInputDocument;

public class SearchUtil {

    private static final Logger logger = Logger.getLogger(SearchUtil.class.getCanonicalName());
    
    /**
     * @param query The query string that might be mutated before feeding it
     * into Solr.
     * @return The query string that may have been mutated or null if null was
     * passed in.
     */
    public static String sanitizeQuery(String query) {
        if (query == null) {
            return null;
        }
        /**
         * In general, don't mutate the query - 
         * because we want to support advanced search queries like
         * "title:foo" we can't simply escape the whole query with
         * `ClientUtils.escapeQueryChars(query)`:
         *
         * http://lucene.apache.org/solr/4_6_0/solr-solrj/org/apache/solr/client/solrj/util/ClientUtils.html#escapeQueryChars%28java.lang.String%29
         */

        query = query.replaceAll("doi:", "doi\\\\:")
                .replaceAll("hdl:", "hdl\\\\:")
                .replaceAll("datasetPersistentIdentifier:doi:", "datasetPersistentIdentifier:doi\\\\:")
                .replaceAll("datasetPersistentIdentifier:hdl:", "datasetPersistentIdentifier:hdl\\\\:");
        return query;
    }

    public static SolrInputDocument createSolrDoc(DvObjectSolrDoc dvObjectSolrDoc) {
        if (dvObjectSolrDoc == null) {
            return null;
        }
        SolrInputDocument solrInputDocument = new SolrInputDocument();
        solrInputDocument.addField(SearchFields.ID, dvObjectSolrDoc.getSolrId() + IndexServiceBean.discoverabilityPermissionSuffix);
        solrInputDocument.addField(SearchFields.DEFINITION_POINT, dvObjectSolrDoc.getSolrId());
        solrInputDocument.addField(SearchFields.DEFINITION_POINT_DVOBJECT_ID, dvObjectSolrDoc.getDvObjectId());
        solrInputDocument.addField(SearchFields.DISCOVERABLE_BY, dvObjectSolrDoc.getPermissions());
        

        if (dvObjectSolrDoc.getFTPermissions() != null) {
            if (dvObjectSolrDoc.getFTPermissions().size() > 0) {
                solrInputDocument.addField(SearchFields.FULL_TEXT_SEARCHABLE_BY, dvObjectSolrDoc.getFTPermissions());
            } else {
                solrInputDocument.addField(SearchFields.FULL_TEXT_SEARCHABLE_BY, dvObjectSolrDoc.getPermissions());
            }
        }
        return solrInputDocument;
    }

    public static String getTimestampOrNull(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        /**
         * @todo Is seconds enough precision?
         */
        return Util.getDateTimeFormat().format(timestamp);
    }

    public static SortBy getSortBy(String sortField, String sortOrder) throws Exception {

        if (StringUtils.isBlank(sortField)) {
            sortField = SearchFields.RELEVANCE;
        } else if (sortField.equals("name")) {
            // "name" sounds better than "name_sort" so we convert it here so users don't have to pass in "name_sort"
            sortField = SearchFields.NAME_SORT;
        } else if (sortField.equals("date")) {
            // "date" sounds better than "release_or_create_date_dt"
            sortField = SearchFields.RELEASE_OR_CREATE_DATE;
        }

        if (StringUtils.isBlank(sortOrder)) {
            if (StringUtils.isNotBlank(sortField)) {
                // default sorting per field if not specified
                if (sortField.equals(SearchFields.RELEVANCE)) {
                    sortOrder = SortBy.DESCENDING;
                } else if (sortField.equals(SearchFields.NAME_SORT)) {
                    sortOrder = SortBy.ASCENDING;
                } else if (sortField.equals(SearchFields.RELEASE_OR_CREATE_DATE)) {
                    sortOrder = SortBy.DESCENDING;
                } else {
                    // asc for alphabetical by default despite GitHub using desc by default:
                    // "The sort order if sort parameter is provided. One of asc or desc. Default: desc"
                    // http://developer.github.com/v3/search/
                    sortOrder = SortBy.ASCENDING;
                }
            }
        }

        List<String> allowedSortOrderValues = SortBy.allowedOrderStrings();
        if (!allowedSortOrderValues.contains(sortOrder)) {
            throw new Exception("The 'order' parameter was '" + sortOrder + "' but expected one of " + allowedSortOrderValues + ". (The 'sort' parameter was/became '" + sortField + "'.)");
        }

        return new SortBy(sortField, sortOrder);
    }

    public static String determineFinalQuery(String userSuppliedQuery) {
        String wildcardQuery = "*";
        if (userSuppliedQuery == null) {
            return wildcardQuery;
        } else if (userSuppliedQuery.isEmpty()) {
            return wildcardQuery;
        } else {
            return userSuppliedQuery;
        }
    }

    public static String constructQuery(String solrField, String userSuppliedQuery) {
       return constructQuery(solrField, userSuppliedQuery, false);
    }
    
    public static String constructQuery(String solrField, String userSuppliedQuery, boolean addQuotes) {

        StringBuilder queryBuilder = new StringBuilder();
        String delimiter = "[\"]+";

        List<String> queryStrings = new ArrayList<>();

        if (userSuppliedQuery != null && !userSuppliedQuery.equals("")) {
            if (userSuppliedQuery.contains("\"")) {
                String[] tempString = userSuppliedQuery.split(delimiter);
                for (int i = 1; i < tempString.length; i++) {
                    if (!tempString[i].equals(" ") && !tempString[i].isEmpty()) {
                        queryStrings.add(solrField + ":" + "\"" + tempString[i].trim() + "\"");
                    }
                }
            } else {
                StringTokenizer st = new StringTokenizer(userSuppliedQuery);
                while (st.hasMoreElements()) {
                    String nextElement = (String) st.nextElement();
                    //Entries such as URIs will get tokenized into individual words by solr unless they are in quotes
                    if(addQuotes) {
                        nextElement = "\"" + nextElement + "\"";
                    }
                    queryStrings.add(solrField + ":" + nextElement);
                }
            }
        }

        if (queryStrings.size() > 1) {
            queryBuilder.append("(");
        }

        for (int i = 0; i < queryStrings.size(); i++) {
            if (i > 0) {
                queryBuilder.append(" ");
            }
            queryBuilder.append(queryStrings.get(i));
        }

        if (queryStrings.size() > 1) {
            queryBuilder.append(")");
        }

        return queryBuilder.toString().trim();
    }
    
    public static String constructQuery(List<String> queryStrings, boolean isAnd) {
        return constructQuery(queryStrings, isAnd, true);
    }
    
    public static String constructQuery(List<String> queryStrings, boolean isAnd, boolean surroundWithParens) {
        StringBuilder queryBuilder = new StringBuilder();

        int count = 0;
        for (String string : queryStrings) {
            if (!StringUtils.isBlank(string)) {
                if (++count > 1) {
                    queryBuilder.append(isAnd ? " AND " : " OR ");
                }
                queryBuilder.append(string);
            }
        }

        if (surroundWithParens && count > 1) {
            queryBuilder.insert(0, "(");
            queryBuilder.append(")");
        }

        return queryBuilder.toString().trim();
    }


    /**
     * @return Null if supplied point is null or whitespace.
     * @throws IllegalArgumentException If the lat/long is not separated by a
     * comma.
     * @throws NumberFormatException If the lat/long values are not numbers.
     */
    public static String getGeoPoint(String userSuppliedGeoPoint) throws IllegalArgumentException, NumberFormatException {
        if (userSuppliedGeoPoint == null || userSuppliedGeoPoint.isBlank()) {
            return null;
        }
        String[] parts = userSuppliedGeoPoint.split(",");
        // We'll supply our own errors but Solr gives a decent one:
        // "Point must be in 'lat, lon' or 'x y' format: 42.3;-71.1"
        if (parts.length != 2) {
            String msg = "Must contain a single comma to separate latitude and longitude.";
            throw new IllegalArgumentException(msg);
        }
        float latitude = Float.parseFloat(parts[0]);
        float longitude = Float.parseFloat(parts[1]);
        return latitude + "," + longitude;
    }

    /**
     * @return Null if supplied radius is null or whitespace.
     * @throws NumberFormatException If the radius is not a positive number.
     */
    public static String getGeoRadius(String userSuppliedGeoRadius) throws NumberFormatException {
        if (userSuppliedGeoRadius == null || userSuppliedGeoRadius.isBlank()) {
            return null;
        }
        float radius = 0;
        try {
            radius = Float.parseFloat(userSuppliedGeoRadius);
        } catch (NumberFormatException ex) {
            String msg = "Non-number radius supplied.";
            throw new NumberFormatException(msg);
        }
        if (radius <= 0) {
            String msg = "The supplied radius must be greater than zero.";
            throw new NumberFormatException(msg);
        }
        return userSuppliedGeoRadius;
    }

    /**
     * expandQuery
     * 
     * This method is only called when full-text indexing is on. It expands a simple
     * query to search for the query items in the default field, where all metadata
     * is indexed, and for the same query items in the full text field. For security
     * reasons, if the query is too complex to be parsed correctly by the current
     * implementation, the method throws an exception. The current implementation
     * does not parse any query with items restricted to specific fields (e.g.
     * title:"Test"), or with use of range queries.
     * 
     * @param query
     * @param joinNeeded
     * @param avoidJoin 
     * @return
     * @throws SearchException
     */
    public static String expandQuery(String query, boolean publicOnly, boolean allGroups, boolean avoidJoin) throws SearchException {
        // If it isn't 'find all'
        // Note that this query is used to populate the main Dataverse view and, without
        // this check, Dataverse assumes its a real search and displays the hit hints
        // instead of the normal summary
        StringBuilder ftQuery = new StringBuilder();
        if (!(query.equals("*")||query.equals("*:*"))) {
            // what about ~ * ? \ /
            // (\\"[^\\"]*\"|'[^']*'|[\\{\\[][^\\}\\]]*[\\}\\]] | [\\S]+)+
            // Split on any whitespace, but also grab any comma, do not split on comma only
            // (since comma only means the second term is still affected by any field:
            // prefix (in the original and when we expand below)
            // String[] parts = query.split(",*[\\s]+,*");
            // String[] parts = query.split("(\"[^\"]*\"|'[^']*'|[\\{\\[][^\\}\\]]*[\\}\\]]
            // | ,*[\\s]+,*)");

            boolean needSpace = false;
            /* Find:
             * 
             * A range query starting with an optional + or - with [ or { at the start and a } or ] at the end (can be mixed, e.g. {...])
             * A quoted phrase starting with an optional + or -
             * A text term that may include an initial : separated fieldname prefix and comma separated parts, but may not include phrases or ranges (which are treated by solr as new terms despite being in a comma-separated list)
             * See https://regexr.com/ to parse and test the patterns
             * 
             * This term is found by searching for strings of characters that don't include whitespace or "[{' or , (with ' being an ignored separator character for solr),
             * followed optionally by a comma and more characters that are not in the above list or a ":+ or - OR
             * a : and a range query OR
             * a : and a quoted string
             */
            Pattern termPattern = Pattern.compile("[+-]?[\\{\\[][^\\}\\]]*[\\}\\]](\\^\\d+)?|[+-]?\\\"[^\\\"]*\\\"(\\^\\d+)?|(([^\\s\"\\[\\{,\\(\\)\\\\]|[\\\\][\\[\\{\\(\\)\\\\+:])+(,?(([^\\s,\\[\\{\":+\\(\\)\\\\-]|\\\\[\\[\\{\\(\\)\\\\+:])|:[\\{\\[][^\\}\\]]*[\\}\\]]|:\\\"[^\\\"]*\\\"|:\\s*[\\(][^:]+[\\)])+)+)+|([^\\s\",\\(\\)\\\\]|\\\\[\\[\\{\\(\\)\\\\+:])+|[\\(\\)]");
            Matcher regexMatcher = termPattern.matcher(query);
            Pattern specialTokenPattern = Pattern.compile("\\(|\\)|OR|NOT|AND|&&|\\|\\||!|.*[^\\\\][^\\\\][:].*"); // add |\w:.* to allow single char fields
            Pattern forbiddenTokenPattern = Pattern.compile("\\\\|\\/|\\^|~|\\*|:");
            while (regexMatcher.find()) {

                String part = regexMatcher.group();
                logger.fine("Parsing found \"" + part + "\"");
                if (needSpace) {
                    ftQuery.append(" ");
                } else {
                    needSpace = true;
                }
                // Don't proceed if there are special characters that are not part of another term (
                if (forbiddenTokenPattern.matcher(part).matches()) {
                    throw new SearchException(BundleUtil.getStringFromBundle("dataverse.search.fullText.error"));
                }
                // If its a boolean logic entry or
                // If it has a : that is not part of an escaped doi or handle (e.g. doi\:), e.g.
                // it is field-specific
                    
                boolean joinNeeded = !publicOnly && !allGroups;
                if (!(specialTokenPattern.matcher(part).matches())) {
                    if (part.startsWith("+")) {
                        ftQuery.append(expandPart(part + " OR (+" + SearchFields.FULL_TEXT + ":" + part.substring(1), publicOnly, joinNeeded, avoidJoin));
                    } else if (part.startsWith("-")) {
                        ftQuery.append(expandPart(part + " OR (-" + SearchFields.FULL_TEXT + ":" + part.substring(1), publicOnly, joinNeeded, avoidJoin));
                    } else if (part.startsWith("!")) {
                        ftQuery.append(expandPart(part + " OR (!" + SearchFields.FULL_TEXT + ":" + part.substring(1), publicOnly, joinNeeded, avoidJoin));
                    } else {
                        ftQuery.append(expandPart(part + " OR (" + SearchFields.FULL_TEXT + ":" + part, publicOnly, joinNeeded, avoidJoin));
                    }
                } else {
                    if (part.contains(SearchFields.FULL_TEXT + ":")) {
                        // Any reference to the FULL_TEXT field has to be joined with the permission
                        // term
                        ftQuery.append(expandPart("(" + part, publicOnly, joinNeeded, avoidJoin));
                    } else {
                        if(!(part.equals("\\") || part.equals("/"))) {
                        ftQuery.append(part);
                        }
                    }
                }
            }
        } else {
            // * and *.* both match all documents
            ftQuery.append("*");
        }
        return ftQuery.toString();
    }

    private static Object expandPart(String part, boolean publicOnly, boolean joinNeeded, boolean avoidJoin) {
        String permClause = (avoidJoin  && publicOnly) ? SearchFields.ACCESS + ":" + SearchConstants.PUBLIC : "";
        if (joinNeeded) {
            if (!permClause.isEmpty()) {
                permClause = "(" + permClause + " OR " + "{!join from=" + SearchFields.DEFINITION_POINT + " to=id v=$q1})";
            } else {
                permClause = "{!join from=" + SearchFields.DEFINITION_POINT + " to=id v=$q1}";
            }
        }
        return "(" + part + (permClause.isEmpty() ? "))" : " AND " + permClause + "))");
    }
}
