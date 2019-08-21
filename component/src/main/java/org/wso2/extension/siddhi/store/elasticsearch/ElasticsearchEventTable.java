/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.extension.siddhi.store.elasticsearch;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.log4j.Logger;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.Node;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.wso2.extension.siddhi.store.elasticsearch.exceptions.ElasticsearchEventTableException;
import org.wso2.extension.siddhi.store.elasticsearch.exceptions.ElasticsearchServiceException;
import org.wso2.extension.siddhi.store.elasticsearch.utils.ElasticsearchTableUtils;
import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.exception.ConnectionUnavailableException;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.table.record.AbstractRecordTable;
import org.wso2.siddhi.core.table.record.ExpressionBuilder;
import org.wso2.siddhi.core.table.record.RecordIterator;
import org.wso2.siddhi.core.util.SiddhiConstants;
import org.wso2.siddhi.core.util.collection.operator.CompiledCondition;
import org.wso2.siddhi.core.util.collection.operator.CompiledExpression;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.query.api.annotation.Annotation;
import org.wso2.siddhi.query.api.annotation.Element;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.TableDefinition;
import org.wso2.siddhi.query.api.util.AnnotationHelper;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.wso2.extension.siddhi.store.elasticsearch.utils.ElasticsearchTableConstants.*;

/**
 * This class contains the Event table implementation for Elasticsearch indexing document as underlying data storage.
 */
@Extension(
        name = "elasticsearch",
        namespace = "store",
        description = "Elasticsearch store implementation uses Elasticsearch indexing document for underlying " +
                "data storage. The events are converted to Elasticsearch index documents when the events are " +
                "inserted into the elasticsearch store. Elasticsearch indexing documents are converted to events when" +
                " the documents are read from Elasticsearch indexes. The internal store is connected to the " +
                "Elastisearch server via the Elasticsearch Java High Level REST Client library.",
        parameters = {
                @Parameter(name = "hostname",
                        description = "The hostname of the Elasticsearch server.",
                        type = {DataType.STRING}, optional = true, defaultValue = "localhost"),
                @Parameter(name = "port",
                        description = "The port of the Elasticsearch server.",
                        type = {DataType.INT}, optional = true, defaultValue = "9200"),
                @Parameter(name = "scheme",
                        description = "The scheme type of the Elasticsearch server connection.",
                        type = {DataType.STRING}, optional = true, defaultValue = "http"),
                @Parameter(name = "elasticsearch.member.list",
                        description = "The list of elasticsearch host names. in comma separated manner" +
                                "https://hostname1:9200,https://hostname2:9200",
                        type = {DataType.STRING}, optional = true,
                        defaultValue = "null"),
                @Parameter(name = "username",
                        description = "The username for the Elasticsearch server connection.",
                        type = {DataType.STRING}, optional = true, defaultValue = "elastic"),
                @Parameter(name = "password",
                        description = "The password for the Elasticsearch server connection.",
                        type = {DataType.STRING}, optional = true, defaultValue = "changeme"),
                @Parameter(name = "index.name",
                        description = "The name of the Elasticsearch index.",
                        type = {DataType.STRING}, optional = true,
                        defaultValue = "The table name defined in the Siddhi App query."),
                @Parameter(name = "payload.index.of.index.name",
                        description = "The payload which is used to create the index. This can be used if the user " +
                                "needs to create index names dynamically",
                        type = {DataType.INT}, optional = true,
                        defaultValue = "-1"),
                @Parameter(name = "index.alias",
                        description = "The alias of the Elasticsearch index.",
                        type = {DataType.STRING}, optional = true,
                        defaultValue = "null"),
                @Parameter(name = "index.number.of.shards",
                        description = "The number of shards allocated for the index in the Elasticsearch server.",
                        type = {DataType.INT}, optional = true, defaultValue = "3"),
                @Parameter(name = "index.number.of.replicas",
                        description = "The number of replicas for the index in the Elasticsearch server.",
                        type = {DataType.INT}, optional = true, defaultValue = "2"),
                @Parameter(name = "bulk.actions",
                        description = "The number of actions to be added to flush a new bulk request. " +
                                "Use -1 to disable it",
                        type = {DataType.INT}, optional = true, defaultValue = "1"),
                @Parameter(name = "bulk.size",
                        description = "The size of size of actions currently added to the bulk request to flush a " +
                                "new bulk request in MB. Use -1 to disable it",
                        type = {DataType.LONG}, optional = true, defaultValue = "1"),
                @Parameter(name = "concurrent.requests",
                        description = "The number of concurrent requests allowed to be executed. Use 0 to only allow " +
                                "the execution of a single request",
                        type = {DataType.INT}, optional = true, defaultValue = "0"),
                @Parameter(name = "flush.interval",
                        description = "The flush interval flushing any BulkRequest pending if the interval passes.",
                        type = {DataType.LONG}, optional = true, defaultValue = "10"),
                @Parameter(name = "backoff.policy.retry.no",
                        description = "The number of retries until backoff " +
                                "(The backoff policy defines how the bulk processor should handle retries of bulk " +
                                "requests internally in case they have failed due to resource constraints " +
                                "(i.e. a thread pool was full)).",
                        type = {DataType.INT}, optional = true, defaultValue = "3"),
                @Parameter(name = "backoff.policy.wait.time",
                        description = "The constant back off policy that initially waits until the next retry " +
                                "in seconds.",
                        type = {DataType.LONG}, optional = true, defaultValue = "1"),
                @Parameter(name = "ssl.enabled",
                        description = "SSL is enabled or not.",
                        type = {DataType.BOOL}, optional = true,
                        defaultValue = "null"),
                @Parameter(name = "trust.store.type",
                        description = "Trust store type.",
                        type = {DataType.STRING}, optional = true,
                        defaultValue = "jks"),
                @Parameter(name = "trust.store.path",
                        description = "Trust store path.",
                        type = {DataType.STRING}, optional = true,
                        defaultValue = "null"),
                @Parameter(name = "trust.store.pass",
                        description = "Trust store password.",
                        type = {DataType.STRING}, optional = true,
                        defaultValue = "wso2carbon"),
                @Parameter(name = "backoff.policy",
                        description = "Provides a backoff policy(eg: constantBackoff, exponentialBackoff, disable) " +
                                "for bulk requests, whenever a bulk request is rejected due to resource constraints. " +
                                "Bulk processor will wait before the operation is retried internally.",
                        type = {DataType.STRING}, optional = true,
                        defaultValue = "constantBackoff"),
                @Parameter(name = "backoff.policy.retry.no",
                        description = "The maximum number of retries. Must be a non-negative number.",
                        type = {DataType.INT}, optional = true,
                        defaultValue = "3"),
                @Parameter(name = "backoff.policy.wait.time",
                        description = "The delay defines how long to wait between retry attempts. Must not be null.",
                        type = {DataType.INT}, optional = true,
                        defaultValue = "1"),
                @Parameter(name = "httpclient.connection.timeout",
                        description = "The timeout in milliseconds until a connection is established. A timeout value" +
                                " of zero is interpreted as an infinite timeout.",
                        type = {DataType.INT}, optional = true,
                        defaultValue = "1000ms"),
                @Parameter(name = "httpclient.socket.timeout",
                        description = "The delay defines how long to wait between retry attempts. Must not be null.",
                        type = {DataType.INT}, optional = true,
                        defaultValue = "30000ms"),
                @Parameter(name = "connection.keepalive.duration",
                        description = "The timeout in milliseconds used for http connection keep-alive",
                        type = {DataType.LONG}, optional = true,
                        defaultValue = "-1")
        },

        examples = {
                @Example(
                        syntax = "@Store(type=\"elasticsearch\", host=\"localhost\", " +
                                "username=\"elastic\", password=\"changeme\", index.name=\"MyStockTable\", " +
                                "field.length=\"symbol:100\", bulk.actions=\"5000\", bulk.size=\"1\", " +
                                "concurrent.requests=\"2\", flush.interval=\"1\", backoff.policy.retry.no=\"3\", " +
                                "backoff.policy.wait.time=\"1\")\n" +
                                "@PrimaryKey(\"symbol\")" +
                                "define table StockTable (symbol string, price float, volume long);",
                        description = "This example creates an index named 'MyStockTable' in the Elasticsearch " +
                                "server if it does not already exist (with three attributes named 'symbol', 'price'," +
                                " and 'volume' of the types 'string', 'float' and 'long' respectively). " +
                                "The connection is made as specified by the parameters configured for the '@Store' " +
                                "annotation. The 'symbol' attribute is considered a unique field and an Elasticsearch" +
                                " index document ID is generated for it."
                ),
                @Example(
                        syntax = "@Store(type=\"elasticsearch\", host=\"localhost\", " +
                                "username=\"elastic\", password=\"changeme\", index.name=\"MyStockTable\", " +
                                "field.length=\"symbol:100\", bulk.actions=\"5000\", bulk.size=\"1\", " +
                                "concurrent.requests=\"2\", flush.interval=\"1\", backoff.policy.retry.no=\"3\", " +
                                "backoff.policy.wait.time=\"1\", ssl.enabled=\"true\", trust.store.type=\"jks\", " +
                                "trust.store.path=\"/User/wso2/wso2sp/resources/security/client-truststore.jks\", " +
                                "trust.store.pass=\"wso2carbon\")\n" +
                                "@PrimaryKey(\"symbol\")" +
                                "define table StockTable (symbol string, price float, volume long);",
                        description = "This example uses SSL to connect to Elasticsearch."
                ),
                @Example(
                        syntax = "@Store(type=\"elasticsearch\", " +
                                "elasticsearch.member.list=\"https://hostname1:9200,https://hostname2:9200\", " +
                                "username=\"elastic\", password=\"changeme\", index.name=\"MyStockTable\", " +
                                "field.length=\"symbol:100\", bulk.actions=\"5000\", bulk.size=\"1\", " +
                                "concurrent.requests=\"2\", flush.interval=\"1\", backoff.policy.retry.no=\"3\", " +
                                "backoff.policy.wait.time=\"1\")\n" +
                                "@PrimaryKey(\"symbol\")" +
                                "define table StockTable (symbol string, price float, volume long);",
                        description = "This example defined several elasticsearch members to publish data using " +
                                "elasticsearch.member.list parameter."
                )
        }
)

// for more information refer https://wso2.github.io/siddhi/documentation/siddhi-4.0/#event-table-types

public class ElasticsearchEventTable extends AbstractRecordTable {

    private static final Logger logger = Logger.getLogger(ElasticsearchEventTable.class);
    private RestHighLevelClient restHighLevelClient;
    private List<Attribute> attributes;
    private List<String> primaryKeys;
    private String hostname = DEFAULT_HOSTNAME;
    private ThreadLocal<String> indexName = new ThreadLocal<>();
    private String indexAlias;
    private int port = DEFAULT_PORT;
    private String scheme = DEFAULT_SCHEME;
    private String userName = DEFAULT_USER_NAME;
    private String password = DEFAULT_PASSWORD;
    private int numberOfShards = DEFAULT_NUMBER_OF_SHARDS;
    private int numberOfReplicas = DEFAULT_NUMBER_OF_REPLICAS;
    private BulkProcessor bulkProcessor;
    private int bulkActions = DEFAULT_BULK_ACTIONS;
    private long bulkSize = DEFAULT_BULK_SIZE_IN_MB;
    private int concurrentRequests = DEFAULT_CONCURRENT_REQUESTS;
    private long flushInterval = DEFAULT_FLUSH_INTERVAL;
    private String backoffPolicy = DEFAULT_BACKOFF_POLICY;
    private int backoffPolicyRetryNo = DEFAULT_BACKOFF_POLICY_RETRY_NO;
    private long backoffPolicyWaitTime = DEFAULT_BACKOFF_POLICY_WAIT_TIME;
    private int ioThreadCount = DEFAULT_IO_THREAD_COUNT;
    private String trustStorePass = DEFAULT_TRUSTSTORE_PASS;
    private String trustStorePath;
    private String trustStoreType = DEFAULT_TRUSTSTORE_TYPE;
    private boolean sslEnabled = DEFAULT_SSL_ENABLED;
    private int payloadIndexOfIndexName = DEFAULT_PAYLOAD_INDEX_OF_INDEX_NAME;
    private int connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
    private int socketTimeout = DEFAULT_SOCKET_TIMEOUT;
    private long connectionKeepAliveDuration = DEFAULT_CONNECTION_KEEPALIVE_DURATION;
    private String listOfHostnames;
    private Map<String, String> typeMappings = new HashMap<>();

    /**
     * Initializing the Record Table
     *
     * @param tableDefinition definition of the table with annotations if any
     * @param configReader    this hold the {@link AbstractRecordTable} configuration reader.
     */
    @Override
    protected void init(TableDefinition tableDefinition, ConfigReader configReader) {
        this.attributes = tableDefinition.getAttributeList();
        Annotation storeAnnotation = AnnotationHelper.getAnnotation(SiddhiConstants.ANNOTATION_STORE, tableDefinition
                .getAnnotations());
        Annotation primaryKeyAnnotation = AnnotationHelper.getAnnotation(SiddhiConstants.ANNOTATION_PRIMARY_KEY,
                tableDefinition.getAnnotations());
        if (primaryKeyAnnotation != null) {
            this.primaryKeys = new ArrayList<>();
            List<Element> primaryKeyElements = primaryKeyAnnotation.getElements();
            primaryKeyElements.forEach(element -> {
                this.primaryKeys.add(element.getValue().trim());
            });
        }
        if (storeAnnotation != null) {
            indexName.set(storeAnnotation.getElement(ANNOTATION_ELEMENT_INDEX_NAME));
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(
                    ANNOTATION_ELEMENT_PAYLOAD_INDEX_OF_INDEX_NAME))) {
                payloadIndexOfIndexName = Integer.parseInt(
                        storeAnnotation.getElement(ANNOTATION_ELEMENT_PAYLOAD_INDEX_OF_INDEX_NAME));
            } else {
                payloadIndexOfIndexName = Integer.parseInt(
                        configReader.readConfig(ANNOTATION_ELEMENT_PAYLOAD_INDEX_OF_INDEX_NAME,
                                String.valueOf(payloadIndexOfIndexName)));
            }
            if (ElasticsearchTableUtils.isEmpty(indexName.get()) &&
                    payloadIndexOfIndexName == -1) {
                indexName.set(tableDefinition.getId());
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_HOSTNAME))) {
                hostname = storeAnnotation.getElement(ANNOTATION_ELEMENT_HOSTNAME);
            } else {
                hostname = configReader.readConfig(ANNOTATION_ELEMENT_HOSTNAME, hostname);
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_PORT))) {
                port = Integer.parseInt(storeAnnotation.getElement(ANNOTATION_ELEMENT_PORT));
            } else {
                port = Integer.parseInt(configReader.readConfig(ANNOTATION_ELEMENT_HOSTNAME, String.valueOf(port)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.
                    getElement(ANNOTATION_ELEMENT_INDEX_NUMBER_OF_SHARDS))) {
                numberOfShards = Integer.parseInt(storeAnnotation.getElement(
                        ANNOTATION_ELEMENT_INDEX_NUMBER_OF_SHARDS));
            } else {
                numberOfShards = Integer.parseInt(configReader.readConfig(ANNOTATION_ELEMENT_INDEX_NUMBER_OF_SHARDS,
                        String.valueOf(numberOfShards)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.
                    getElement(ANNOTATION_ELEMENT_INDEX_NUMBER_OF_REPLICAS))) {
                numberOfReplicas = Integer.parseInt(storeAnnotation.getElement(
                        ANNOTATION_ELEMENT_INDEX_NUMBER_OF_REPLICAS));
            } else {
                numberOfReplicas = Integer.parseInt(configReader.readConfig(ANNOTATION_ELEMENT_INDEX_NUMBER_OF_REPLICAS,
                        String.valueOf(numberOfReplicas)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_SCHEME))) {
                scheme = storeAnnotation.getElement(ANNOTATION_ELEMENT_SCHEME);
            } else {
                scheme = configReader.readConfig(ANNOTATION_ELEMENT_SCHEME, scheme);
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_USER))) {
                userName = storeAnnotation.getElement(ANNOTATION_ELEMENT_USER);
            } else {
                userName = configReader.readConfig(ANNOTATION_ELEMENT_USER, userName);
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_PASSWORD))) {
                password = storeAnnotation.getElement(ANNOTATION_ELEMENT_PASSWORD);
            } else {
                password = configReader.readConfig(ANNOTATION_ELEMENT_PASSWORD, password);
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_INDEX_ALIAS))) {
                indexAlias = storeAnnotation.getElement(ANNOTATION_ELEMENT_INDEX_ALIAS);
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_BULK_ACTIONS))) {
                bulkActions = Integer.parseInt(storeAnnotation.getElement(ANNOTATION_ELEMENT_BULK_ACTIONS));
            } else {
                bulkActions = Integer.parseInt(configReader.readConfig(ANNOTATION_ELEMENT_BULK_ACTIONS,
                        String.valueOf(bulkActions)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_BULK_SIZE))) {
                bulkSize = Long.parseLong(storeAnnotation.getElement(ANNOTATION_ELEMENT_BULK_SIZE));
            } else {
                bulkSize = Long.parseLong(configReader.readConfig(ANNOTATION_ELEMENT_BULK_SIZE,
                        String.valueOf(bulkSize)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_CONCURRENT_REQUESTS))) {
                concurrentRequests = Integer.parseInt(
                        storeAnnotation.getElement(ANNOTATION_ELEMENT_CONCURRENT_REQUESTS));
            } else {
                concurrentRequests = Integer.parseInt(configReader.readConfig(ANNOTATION_ELEMENT_CONCURRENT_REQUESTS,
                        String.valueOf(concurrentRequests)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_FLUSH_INTERVAL))) {
                flushInterval = Long.parseLong(
                        storeAnnotation.getElement(ANNOTATION_ELEMENT_FLUSH_INTERVAL));
            } else {
                flushInterval = Long.parseLong(configReader.readConfig(ANNOTATION_ELEMENT_FLUSH_INTERVAL,
                        String.valueOf(flushInterval)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(
                    ANNOTATION_ELEMENT_BACKOFF_POLICY))) {
                backoffPolicy = storeAnnotation.getElement(ANNOTATION_ELEMENT_BACKOFF_POLICY);
                backoffPolicy = backoffPolicy.equalsIgnoreCase(ANNOTATION_ELEMENT_BACKOFF_POLICY_CONSTANT_BACKOFF) ||
                        backoffPolicy.equalsIgnoreCase(ANNOTATION_ELEMENT_BACKOFF_POLICY_EXPONENTIAL_BACKOFF) ||
                        !backoffPolicy.equalsIgnoreCase(ANNOTATION_ELEMENT_BACKOFF_POLICY_DISABLE) ?
                        backoffPolicy : null;
            } else {
                backoffPolicy = configReader.readConfig(ANNOTATION_ELEMENT_BACKOFF_POLICY, backoffPolicy);
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(
                    ANNOTATION_ELEMENT_BACKOFF_POLICY_RETRY_NO))) {
                backoffPolicyRetryNo = Integer.parseInt(
                        storeAnnotation.getElement(ANNOTATION_ELEMENT_BACKOFF_POLICY_RETRY_NO));
            } else {
                backoffPolicyRetryNo = Integer.parseInt(
                        configReader.readConfig(ANNOTATION_ELEMENT_BACKOFF_POLICY_RETRY_NO,
                                String.valueOf(backoffPolicyRetryNo)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(
                    ANNOTATION_ELEMENT_BACKOFF_POLICY_WAIT_TIME))) {
                backoffPolicyWaitTime = Long.parseLong(
                        storeAnnotation.getElement(ANNOTATION_ELEMENT_BACKOFF_POLICY_WAIT_TIME));
            } else {
                backoffPolicyWaitTime = Long.parseLong(
                        configReader.readConfig(ANNOTATION_ELEMENT_BACKOFF_POLICY_WAIT_TIME,
                                String.valueOf(backoffPolicyWaitTime)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(
                    ANNOTATION_ELEMENT_CLIENT_IO_THREAD_COUNT))) {
                ioThreadCount = Integer.parseInt(
                        storeAnnotation.getElement(ANNOTATION_ELEMENT_CLIENT_IO_THREAD_COUNT));
            } else {
                ioThreadCount = Integer.parseInt(
                        configReader.readConfig(ANNOTATION_ELEMENT_CLIENT_IO_THREAD_COUNT,
                                String.valueOf(ioThreadCount)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_SSL_ENABLED))) {
                sslEnabled = Boolean.parseBoolean(storeAnnotation.getElement(ANNOTATION_ELEMENT_SSL_ENABLED));
            } else {
                sslEnabled = Boolean.parseBoolean(configReader.readConfig(ANNOTATION_ELEMENT_SSL_ENABLED,
                        String.valueOf(sslEnabled)));
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_TRUSRTSTORE_PASS))) {
                trustStorePass = storeAnnotation.getElement(ANNOTATION_ELEMENT_TRUSRTSTORE_PASS);
            } else {
                trustStorePass = configReader.readConfig(ANNOTATION_ELEMENT_TRUSRTSTORE_PASS, trustStorePass);
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_TRUSRTSTORE_PATH))) {
                trustStorePath = storeAnnotation.getElement(ANNOTATION_ELEMENT_TRUSRTSTORE_PATH);
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_TRUSRTSTORE_TYPE))) {
                trustStoreType = storeAnnotation.getElement(ANNOTATION_ELEMENT_TRUSRTSTORE_TYPE);
            } else {
                trustStoreType = configReader.readConfig(ANNOTATION_ELEMENT_TRUSRTSTORE_TYPE, trustStoreType);
            }
            if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_CONNECTION_TIMEOUT))) {
                connectionTimeout = Integer.parseInt(storeAnnotation.getElement(ANNOTATION_ELEMENT_CONNECTION_TIMEOUT));
            } else {
                connectionTimeout = Integer.parseInt(configReader.readConfig(ANNOTATION_ELEMENT_CONNECTION_TIMEOUT,
                        String.valueOf(DEFAULT_CONNECTION_TIMEOUT)));
            } if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_SOCKET_TIMEOUT))) {
                socketTimeout = Integer.parseInt(storeAnnotation.getElement(ANNOTATION_ELEMENT_SOCKET_TIMEOUT));
            } else {
                socketTimeout = Integer.parseInt(configReader.readConfig(ANNOTATION_ELEMENT_SOCKET_TIMEOUT,
                        String.valueOf(DEFAULT_SOCKET_TIMEOUT)));
            }if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_CONNECTION_KEEPALIVE_DURATION))) {
                connectionKeepAliveDuration = Integer.parseInt(storeAnnotation.getElement(ANNOTATION_ELEMENT_CONNECTION_KEEPALIVE_DURATION));
            } else {
                connectionKeepAliveDuration = Integer.parseInt(configReader.readConfig(ANNOTATION_ELEMENT_CONNECTION_KEEPALIVE_DURATION,
                        String.valueOf(DEFAULT_CONNECTION_KEEPALIVE_DURATION)));
            } if (!ElasticsearchTableUtils.isEmpty(storeAnnotation.getElement(ANNOTATION_ELEMENT_MEMBER_LIST))) {
                listOfHostnames = storeAnnotation.getElement(ANNOTATION_ELEMENT_MEMBER_LIST);
            }

            List<Annotation> typeMappingsAnnotations = storeAnnotation.getAnnotations(ANNOTATION_TYPE_MAPPINGS);
            if (typeMappingsAnnotations.size() > 0) {
                for (Element element : typeMappingsAnnotations.get(0).getElements()) {
                    validateTypeMappingAttribute(element.getKey());
                    typeMappings.put(element.getKey(), element.getValue());
                }
            }
        } else {
            throw new ElasticsearchEventTableException("Elasticsearch Store annotation list null for table id : '" +
                    tableDefinition.getId() + "', required properties cannot be resolved.");
        }
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(userName, password));
        HttpHost[] httpHostList;
        if (listOfHostnames != null) {
            String[] hostNameList = listOfHostnames.split(",");
            httpHostList = new HttpHost[hostNameList.length];
            for (int i = 0; i < httpHostList.length; i++) {
                try {
                    URL domain = new URL(hostNameList[i]);
                    httpHostList[i] = new HttpHost(domain.getHost(), domain.getPort(), domain.getProtocol());
                } catch (MalformedURLException e) {
                    throw new ElasticsearchEventTableException("Provided elastic search hostname url list is " +
                            "malformed of table id : '" + tableDefinition.getId() + ".", e);
                }
            }
        } else {
            httpHostList = new HttpHost[1];
            httpHostList[0] = new HttpHost(hostname, port, scheme);
        }
        restHighLevelClient =
                new RestHighLevelClient(RestClient.builder(httpHostList)
                        .setFailureListener(new FailureListener())
                        .setRequestConfigCallback(builder -> {
                            builder.setConnectTimeout(connectionTimeout);
                            builder.setSocketTimeout(socketTimeout);
                            builder.setConnectionRequestTimeout(DEFAULT_CONNECTION_REQUEST_TIMEOUT);
                            return builder;
                        })
                        .setHttpClientConfigCallback(httpClientBuilder -> {
                            httpClientBuilder.disableConnectionState();
                            httpClientBuilder.disableAuthCaching();
                            httpClientBuilder.setKeepAliveStrategy(new ElasticsearchKeepAliveStrategy());
                            httpClientBuilder.setDefaultIOReactorConfig(IOReactorConfig.custom().
                                    setIoThreadCount(ioThreadCount).build());
                            if (sslEnabled) {
                                try {
                                    KeyStore trustStore = KeyStore.getInstance(trustStoreType);
                                    if (trustStorePath == null) {
                                        throw new ElasticsearchEventTableException(
                                                "Please provide a valid path for trust " +
                                                        "store location for table id : '" + tableDefinition.getId());
                                    } else {
                                        try (InputStream is = Files.newInputStream(Paths.get(trustStorePath))) {
                                            trustStore.load(is, trustStorePass.toCharArray());
                                        }
                                        SSLContextBuilder sslBuilder =
                                                SSLContexts.custom().loadTrustMaterial(trustStore, null);
                                        httpClientBuilder.setSSLContext(sslBuilder.build());
                                    }
                                } catch (NoSuchAlgorithmException e) {
                                    throw new ElasticsearchEventTableException(
                                            "Algorithm used to check the integrity of the " +
                                                    "trustStore cannot be found for when loading trustStore for table id : '" +
                                                    tableDefinition.getId(), e);
                                } catch (KeyStoreException e) {
                                    throw new ElasticsearchEventTableException(
                                            "The trustStore type truststore.type = " +
                                                    "" + trustStoreType + " defined is incorrect while creating table id : '" +
                                                    tableDefinition.getId(), e);
                                } catch (CertificateException e) {
                                    throw new ElasticsearchEventTableException(
                                            "Any of the certificates in the keystore " +
                                                    "could not be loaded when loading trustStore for table id : '" +
                                                    tableDefinition.getId(), e);
                                } catch (IOException e) {
                                    throw new ElasticsearchEventTableException(
                                            "The trustStore password = " + trustStorePass +
                                                    " or trustStore path " + trustStorePath + " defined is incorrect while creating " +
                                                    "sslContext for table id : '" + tableDefinition.getId(), e);
                                } catch (KeyManagementException e) {
                                    throw new ElasticsearchEventTableException(
                                            "Error occurred while builing sslContext for " +
                                                    "table id : '" + tableDefinition.getId(), e);
                                }
                            }
                            return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                        }));
        BulkProcessor.Builder bulkProcessorBuilder = BulkProcessor.builder(
                (request, bulkListener) ->
                        restHighLevelClient.bulkAsync(request, RequestOptions.DEFAULT, bulkListener),
                new BulkProcessorListener());
        bulkProcessorBuilder.setBulkActions(bulkActions);
        bulkProcessorBuilder.setBulkSize(new ByteSizeValue(bulkSize, ByteSizeUnit.MB));
        bulkProcessorBuilder.setConcurrentRequests(concurrentRequests);
        bulkProcessorBuilder.setFlushInterval(TimeValue.timeValueSeconds(flushInterval));
        if (backoffPolicy == null) {
            bulkProcessorBuilder.setBackoffPolicy(BackoffPolicy.noBackoff());
            logger.debug("Starting elasticsearch client with noBackoff policy");
        } else if (backoffPolicy.equalsIgnoreCase(ANNOTATION_ELEMENT_BACKOFF_POLICY_CONSTANT_BACKOFF)) {
            bulkProcessorBuilder.setBackoffPolicy(BackoffPolicy.constantBackoff(
                    TimeValue.timeValueSeconds(backoffPolicyWaitTime), backoffPolicyRetryNo));
            logger.debug("Starting elasticsearch client with constantBackoff policy");
        } else {
            bulkProcessorBuilder.setBackoffPolicy(BackoffPolicy.exponentialBackoff(
                    TimeValue.timeValueSeconds(backoffPolicyWaitTime), backoffPolicyRetryNo));
            logger.debug("Starting elasticsearch client with exponentialBackoff policy");
        }
        bulkProcessor = bulkProcessorBuilder.build();

        if (logger.isDebugEnabled()) {
            logger.debug(
                    "BulkProcessor initialization completed with configuration - [ elasticsearch.member.list = "
                            + listOfHostnames + ", bulk.actions = " + bulkActions + ", bulk.size = " + bulkSize
                            + ", concurrent.requests = " + concurrentRequests + ", flush.interval = " + flushInterval
                            + ", backoff.policy = " + backoffPolicy + " backoff.policy.retry.no = " + backoffPolicyRetryNo
                            + ", backoff.policy.wait.time = " + backoffPolicyWaitTime + ", io.thread.count = "
                            + ioThreadCount + "(system default = " + DEFAULT_IO_THREAD_COUNT + "),ssl.enabled = "
                            + sslEnabled + ")");
        }
        if (indexName.get() != null && !indexName.get().isEmpty()) {
            createIndex(indexName.get());
        }
    }

    /**
    * This is to override the keepAlive duration of a connection.
    */
    class ElasticsearchKeepAliveStrategy extends DefaultConnectionKeepAliveStrategy{
        @Override
        public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
            long keepAliveDuration = super.getKeepAliveDuration(response, context);
            // if the keep-alive duration is < 0, then we set the custom one that is defined in the configuration
            if(keepAliveDuration < 0){
                return connectionKeepAliveDuration;
            }
            return keepAliveDuration;
        }
    }

    static class FailureListener extends RestClient.FailureListener {
        public FailureListener() {
            super();
        }

        @Override
        public void onFailure(Node node) {
            super.onFailure(node);
            if (logger.isDebugEnabled()) {
                logger.debug("Node failure detected. Node : " + node + " failed to respond. ");
            }
        }
    }

    static class BulkProcessorListener implements BulkProcessor.Listener {
        @Override
        public void beforeBulk(long executionId, BulkRequest request) {
            int numberOfActions = request.numberOfActions();
            logger.debug("Executing bulk [{" + executionId + "}] with {" + numberOfActions + "} requests");
        }

        @Override
        public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
            if (response.hasFailures()) {
                logger.warn("Bulk [{" + executionId + "}] executed with failures : "
                        + response.buildFailureMessage() + ", status : " + response.status().getStatus());
                if (logger.isTraceEnabled()) {
                    for (BulkItemResponse itemResponse : response) {
                        logger.trace("Bulk [{" + executionId + "}] executed with failures, item : "
                                + itemResponse.getItemId() + ", response message : "
                                + itemResponse.getFailureMessage() + ", failure : " + itemResponse.getFailure()
                                + " message : " + itemResponse.getResponse().toString());
                    }
                }
            } else {
                logger.debug("Bulk [{" + executionId + "}] completed in {" + response.getTook().getMillis() +
                        "} milliseconds");
                if (logger.isTraceEnabled()) {
                    for (BulkItemResponse itemResponse : response) {
                        logger.trace("Bulk [{" + executionId + "}] completed for : " + itemResponse.getResponse()
                                .toString());
                    }
                }
            }
        }

        @Override
        public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
            logger.error("Bulk [{" + executionId + "}] Failed to execute ", failure);
        }
    }

    /**
     * Add records to the Table
     *
     * @param records records that need to be added to the table, each Object[] represent a record and it will match
     *                the attributes of the Table Definition.
     */
    @Override
    protected void add(List<Object[]> records) throws ConnectionUnavailableException {
        for (Object[] record : records) {
            if (payloadIndexOfIndexName != -1 &&
                    (indexName.get() == null || !indexName.get()
                            .equalsIgnoreCase((String) record[payloadIndexOfIndexName]))) {
                indexName.set((String) record[payloadIndexOfIndexName]);
                createIndex(indexName.get());
            }
            IndexRequest indexRequest = new IndexRequest(indexName.get());
            if (primaryKeys != null && !primaryKeys.isEmpty()) {
                String docId = ElasticsearchTableUtils.generateRecordIdFromPrimaryKeyValues(attributes, record,
                        primaryKeys);
                indexRequest.id(docId);
            }
            try {
                XContentBuilder builder = XContentFactory.jsonBuilder();
                builder.startObject();
                for (int i = 0; i < record.length; i++) {
                    builder.field(attributes.get(i).getName(), record[i]);
                }
                builder.endObject();
                indexRequest.source(builder);
                bulkProcessor.add(indexRequest);
            } catch (IOException e) {
                throw new ElasticsearchEventTableException("Error while generating content mapping for records : '" +
                        records.toString() + "' in table id: " + tableDefinition.getId(), e);
            }
        }
    }

    /**
     * Find records matching the compiled condition
     *
     * @param findConditionParameterMap map of matching StreamVariable Ids and their values corresponding to the
     *                                  compiled condition
     * @param compiledCondition         the compiledCondition against which records should be matched
     * @return RecordIterator of matching records
     */
    @Override
    protected RecordIterator<Object[]> find(Map<String, Object> findConditionParameterMap,
                                            CompiledCondition compiledCondition) throws ConnectionUnavailableException {
        try {
            return findRecords(findConditionParameterMap, compiledCondition);
        } catch (ElasticsearchServiceException e) {
            throw new ConnectionUnavailableException("Error while performing the find operation " + e.getMessage(),
                    e);
        }
    }

    private ElasticsearchRecordIterator findRecords(Map<String, Object> findConditionParameterMap, CompiledCondition
            compiledCondition) throws ElasticsearchServiceException {
        String condition = ElasticsearchTableUtils.resolveCondition((ElasticsearchCompiledCondition) compiledCondition,
                findConditionParameterMap);
        return new ElasticsearchRecordIterator(indexName.get(), condition, restHighLevelClient, attributes);
    }

    /**
     * .
     * Check if matching record exist or not
     *
     * @param containsConditionParameterMap map of matching StreamVariable Ids and their values corresponding to the
     *                                      compiled condition
     * @param compiledCondition             the compiledCondition against which records should be matched
     * @return if matching record found or not
     */
    @Override
    protected boolean contains(Map<String, Object> containsConditionParameterMap,
                               CompiledCondition compiledCondition) throws ConnectionUnavailableException {
        try {
            ElasticsearchRecordIterator elasticsearchRecordIterator = findRecords(containsConditionParameterMap,
                    compiledCondition);
            return elasticsearchRecordIterator.hasNext();
        } catch (ElasticsearchServiceException e) {
            throw new ElasticsearchEventTableException("Error while checking content mapping for '" +
                    "' table id: " + tableDefinition.getId(), e);
        }
    }

    /**
     * Delete all matching records
     *
     * @param deleteConditionParameterMaps map of matching StreamVariable Ids and their values corresponding to the
     *                                     compiled condition
     * @param compiledCondition            the compiledCondition against which records should be matched for deletion
     * @throws ConnectionUnavailableException if end point is unavailable the ConnectionUnavailableException thrown
     *                                        such that the  system will take care retrying for connection
     **/
    @Override
    protected void delete(List<Map<String, Object>> deleteConditionParameterMaps, CompiledCondition compiledCondition)
            throws ConnectionUnavailableException {
        String docId = null;
        try {
            for (Map<String, Object> record : deleteConditionParameterMaps) {
                if (primaryKeys != null && !primaryKeys.isEmpty()) {
                    docId = ElasticsearchTableUtils.generateRecordIdFromPrimaryKeyValues(attributes, record,
                            primaryKeys);
                }
                DeleteRequest deleteRequest = new DeleteRequest(indexName.get(), docId != null ? docId : "1");
                bulkProcessor.add(deleteRequest);
            }
        } catch (Throwable throwable) {
            throw new ElasticsearchEventTableException("Error while deleting content mapping for records id: '" + docId
                    + "' in table id: " + tableDefinition.getId(), throwable);
        }
    }

    /**
     * Update all matching records
     *
     * @param compiledCondition the compiledCondition against which records should be matched for update
     * @param list              map of matching StreamVariable Ids and their values corresponding to the
     *                          compiled condition based on which the records will be updated
     * @param map               the attributes and values that should be updated if the condition matches
     * @param list1             the attributes and values that should be updated for the matching records
     * @throws ConnectionUnavailableException if end point is unavailable the ConnectionUnavailableException thrown
     *                                        such that the  system will take care retrying for connection
     */
    @Override
    protected void update(CompiledCondition compiledCondition, List<Map<String, Object>> list,
                          Map<String, CompiledExpression> map, List<Map<String, Object>> list1)
            throws ConnectionUnavailableException {
        String docId = null;
        try {
            for (Map<String, Object> record : list1) {
                if (primaryKeys != null && !primaryKeys.isEmpty()) {
                    docId = ElasticsearchTableUtils.generateRecordIdFromPrimaryKeyValues(attributes, record,
                            primaryKeys);
                }
                XContentBuilder builder = XContentFactory.jsonBuilder();
                builder.startObject();
                for (int i = 0; i < attributes.size(); i++) {
                    builder.field(attributes.get(i).getName(), record.get(attributes.get(i).getName()));
                }
                builder.endObject();
                UpdateRequest updateRequest = new UpdateRequest(indexName.get(), docId != null ? docId : "1").
                        doc(builder);
                bulkProcessor.add(updateRequest);
            }
        } catch (Throwable throwable) {
            throw new ElasticsearchEventTableException("Error while updating content mapping for records id: '" + docId
                    + "' in table id: " + tableDefinition.getId(), throwable);
        }
    }

    /**
     * Try updating the records if they exist else add the records
     *
     * @param list              map of matching StreamVariable Ids and their values corresponding to the
     *                          compiled condition based on which the records will be updated
     * @param compiledCondition the compiledCondition against which records should be matched for update
     * @param map               the attributes and values that should be updated if the condition matches
     * @param list1             the values for adding new records if the update condition did not match
     * @param list2             the attributes and values that should be updated for the matching records
     * @throws ConnectionUnavailableException if end point is unavailable the ConnectionUnavailableException thrown
     *                                        such that the  system will take care retrying for connection
     */
    @Override
    protected void updateOrAdd(CompiledCondition compiledCondition, List<Map<String, Object>> list,
                               Map<String, CompiledExpression> map, List<Map<String, Object>> list1,
                               List<Object[]> list2) throws ConnectionUnavailableException {
        try {
            for (Object[] record : list2) {
                String docId = null;
                if (primaryKeys != null && !primaryKeys.isEmpty()) {
                    docId = ElasticsearchTableUtils.generateRecordIdFromPrimaryKeyValues(attributes, record,
                            primaryKeys);
                }
                XContentBuilder builder = XContentFactory.jsonBuilder();
                builder.startObject();
                for (int i = 0; i < attributes.size(); i++) {
                    builder.field(attributes.get(i).getName(), record[i]);
                }
                builder.endObject();
                UpdateRequest updateRequest = new UpdateRequest(indexName.get(), docId != null ? docId : "1").
                        doc(builder);
                bulkProcessor.add(updateRequest);
            }
        } catch (Throwable throwable) {
            this.add(list2);
        }
    }

    /**
     * Compile the matching condition
     *
     * @param expressionBuilder that helps visiting the conditions in order to compile the condition
     * @return compiled condition that can be used for matching events in find, contains, delete, update and
     * updateOrAdd
     */
    @Override
    protected CompiledCondition compileCondition(ExpressionBuilder expressionBuilder) {
        ElasticsearchConditionVisitor visitor = new ElasticsearchConditionVisitor();
        expressionBuilder.build(visitor);
        return new ElasticsearchCompiledCondition(visitor.returnCondition());
    }

    /**
     * Compile the matching condition
     *
     * @param expressionBuilder that helps visiting the conditions in order to compile the condition
     * @return compiled condition that can be used for matching events in find, contains, delete, update and
     * updateOrAdd
     */
    @Override
    protected CompiledExpression compileSetAttribute(ExpressionBuilder expressionBuilder) {
        ElasticsearchExpressionVisitor visitor = new ElasticsearchExpressionVisitor();
        expressionBuilder.build(visitor);
        return new ElasticsearchCompiledCondition(visitor.returnExpression());
    }

    /**
     * This method will be called before the processing method.
     * Intention to establish connection to publish event.
     *
     * @throws ConnectionUnavailableException if end point is unavailable the ConnectionUnavailableException thrown
     *                                        such that the  system will take care retrying for connection
     */
    @Override
    protected void connect() throws ConnectionUnavailableException {

    }

    /**
     * Called after all publishing is done, or when {@link ConnectionUnavailableException} is thrown
     * Implementation of this method should contain the steps needed to disconnect.
     */
    @Override
    protected void disconnect() {

    }

    /**
     * The method can be called when removing an event receiver.
     * The cleanups that have to be done after removing the receiver could be done here.
     */
    @Override
    protected void destroy() {

    }

    private void createIndex(String indexName) {
        logger.debug("Creating index : " + indexName + " for table id : " + tableDefinition.getId());
        try {
            if (restHighLevelClient.indices().exists(new GetIndexRequest(indexName), RequestOptions.DEFAULT)) {
                logger.debug("Index: " + indexName + " has already being created for table id: " +
                        tableDefinition.getId() + ".");
                return;
            }
        } catch (IOException e) {
            throw new ElasticsearchEventTableException("Error while checking indices for table id : '" +
                    tableDefinition.getId(), e);
        } catch (Exception e) {
            logger.error("Unable to check if the index exists.", e);
        }

        CreateIndexRequest request = new CreateIndexRequest(indexName);
        request.settings(Settings.builder()
                .put(SETTING_INDEX_NUMBER_OF_SHARDS, numberOfShards)
                .put(SETTING_INDEX_NUMBER_OF_REPLICAS, numberOfReplicas)
        );
        try {
            XContentBuilder builder = XContentFactory.jsonBuilder();
            builder.startObject();
            {
                builder.startObject(MAPPING_PROPERTIES_ELEMENT);
                {
                    for (Attribute attribute : attributes) {
                        builder.startObject(attribute.getName());
                        {
                            if (typeMappings.containsKey(attribute.getName())) {
                                builder.field(MAPPING_TYPE_ELEMENT, typeMappings.get(attribute.getName()));
                            } else if (attribute.getType().equals(Attribute.Type.STRING)) {
                                builder.field(MAPPING_TYPE_ELEMENT, "text");
                                builder.startObject("fields");
                                {
                                    builder.startObject("keyword");
                                    {
                                        builder.field("type", "keyword");
                                        builder.field("ignore_above", 256);
                                    }
                                    builder.endObject();
                                }
                                builder.endObject();
                            } else if (attribute.getType().equals(Attribute.Type.INT)) {
                                builder.field(MAPPING_TYPE_ELEMENT, "integer");
                            } else if (attribute.getType().equals(Attribute.Type.LONG)) {
                                builder.field(MAPPING_TYPE_ELEMENT, "long");
                            } else if (attribute.getType().equals(Attribute.Type.FLOAT)) {
                                builder.field(MAPPING_TYPE_ELEMENT, "float");
                            } else if (attribute.getType().equals(Attribute.Type.DOUBLE)) {
                                builder.field(MAPPING_TYPE_ELEMENT, "double");
                            } else if (attribute.getType().equals(Attribute.Type.BOOL)) {
                                builder.field(MAPPING_TYPE_ELEMENT, "boolean");
                            } else {
                                builder.field(MAPPING_TYPE_ELEMENT, "object");
                            }
                        }
                        builder.endObject();
                    }
                }
                builder.endObject();
            }
            builder.endObject();
            request.mapping(builder);
        } catch (IOException e) {
            throw new ElasticsearchEventTableException("Error while generating mapping for table id : '" +
                    tableDefinition.getId(), e);
        }
        if (indexAlias != null) {
            request.alias(new Alias(indexAlias));
        }
        try {
            restHighLevelClient.indices().create(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new ElasticsearchEventTableException("Error while creating indices for table id : '" +
                    tableDefinition.getId(), e);
        } catch (ElasticsearchStatusException e) {
            // This is to check if another node(in a multi-node deployment) has already created the index.
            // Need to go through all the suppressed exceptions to check whether this is a
            // resource_already_exists_exception.
            for (Throwable throwable : e.getSuppressed()) {
                if (throwable.getMessage().contains("resource_already_exists_exception")) {
                    logger.warn("Index name : " + indexName + " already created for table id: " +
                            tableDefinition.getId());
                    logger.debug("Index name : " + indexName + " already created for table id: " +
                            tableDefinition.getId(), e);
                    return;
                }
            }
            // At this point we need to throw and error because we need to retry creating the index.
            logger.error("Elasticsearch status exception occurred while creating index for table id: " +
                    tableDefinition.getId(), e);
            throw new ElasticsearchEventTableException("Elasticsearch status exception occurred while creating " +
                    "index for table id: '" +
                    tableDefinition.getId(), e);
        }finally {
            logger.debug("Index " + indexName+ " has been created for table id: " + tableDefinition.getId());
        }
    }

    private void validateTypeMappingAttribute(String typeMappingAttributeName) {
        boolean matchFound = false;
        for (Attribute storeAttribute : attributes) {
            if (storeAttribute.getName().equals(typeMappingAttributeName)) {
                matchFound = true;
            }
        }
        if (!matchFound) {
            throw new SiddhiAppCreationException("Invalid attribute name '" + typeMappingAttributeName
                    + "' found in " + ANNOTATION_TYPE_MAPPINGS + ". No such attribute found in Store definition.");
        }
    }
}
