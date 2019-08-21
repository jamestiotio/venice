package com.linkedin.venice;

import com.linkedin.venice.migration.MigrationPushStrategy;


/**
 * TODO: Merge this with {@link com.linkedin.venice.controllerapi.ControllerApiConstants}
 */
public enum Arg {

  ACCESS_CONTROL("access-control", "acl", true, "Enable/disable store-level access control"),
  URL("url", "u", true, "Venice url, eg. http://localhost:1689  This can be a router or a controller"),
  CLUSTER("cluster", "c", true, "Name of Venice cluster"),
  CLUSTER_SRC("cluster-src", "cs", true, "Store migration original Venice cluster name"),
  CLUSTER_DEST("cluster-dest", "cd", true, "Store migration destination Venice cluster name"),
  STORE("store", "s", true, "Name of Venice store"),
  VERSION("version", "v", true, "Active store version number"),
  LARGEST_USED_VERSION_NUMBER("largest-used-version", "luv", true, "Largest used store version number (whether active or not)"),
  PUSH_ID("push-id", "pid", true, "Push Id"),
  STORE_SIZE("store-size", "ss", true, "Size of the store in bytes, used to calculate partitioning"),
  KEY_SCHEMA("key-schema-file", "ks", true, "Path to text file with key schema"),
  VALUE_SCHEMA_ID("value-schema-id", "vid", true, "value schema id"),
  VALUE_SCHEMA("value-schema-file", "vs", true, "Path to text file with value schema"),
  DERIVED_SCHEMA("derived-schema-file", "ds", true, "Path to text file with derived schema"),
  OWNER("owner", "o", true, "Owner email for new store creation"),
  STORAGE_NODE("storage-node", "n", true, "Helix instance ID for a storage node, eg. lva1-app1234_1690"),
  KEY("key", "k", true, "Plain-text key for identifying a record in a store"),
  OFFSET("offset", "of", true, "Kafka offset number"),
  EXECUTION("execution", "e", true, "Execution ID of async admin command"),
  PARTITION_COUNT("partition-count", "pn", true, "number of partitions a store has"),
  READABILITY("readability", "rb", true, "store's readability"),
  WRITEABILITY("writeability", "wb", true, "store's writeability"),
  STORAGE_QUOTA("storage-quota", "sq", true, "maximum capacity a store version could have"),
  READ_QUOTA("read-quota", "rq", true, "quota for read request hit this store. Measurement is capacity unit"),
  HYBRID_REWIND_SECONDS("hybrid-rewind-seconds", "hr", true, "for hybrid stores, how far back to rewind in the nearline stream after a batch push completes"),
  HYBRID_OFFSET_LAG("hybrid-offset-lag", "ho", true, "for hybrid stores, what is the offset lag threshold for the storage nodes' consumption to be considered ONLINE"),
  EXPECTED_ROUTER_COUNT("expected-router-count", "erc", true, "How many routers that a cluster should have."),
  VOLDEMORT_STORE("voldemort-store", "vs", true, "Voldemort store name"),
  MIGRATION_PUSH_STRATEGY("migration-push-strategy", "ps", true, "Migration push strategy, valid values: ["
      + MigrationPushStrategy.getAllEnumString() + "]"),
  VSON_STORE("vson_store", "vson", true, "indicate whether it is Vson store or Avro store"),
  COMPRESSION_STRATEGY("compression-strategy", "cs", true, "strategies used to compress/decompress Record's value"),
  CLIENT_DECOMPRESSION_ENABLED("client-decompression-enabled", "csd", true, "Enable/Disable client-side record decompression (default: true)"),
  CHUNKING_ENABLED("chunking-enabled", "ce", true, "Enable/Disable value chunking, mostly for large value store support"),
  INCREMENTAL_PUSH_ENABLED("incremental-push-enabled", "ipe", true, "a flag to see if the store supports incremental push or not"),
  SINGLE_GET_ROUTER_CACHE_ENABLED("single-get-router-cache-enabled", "srce", true, "Enable/Disable single get cache in Router"),
  BATCH_GET_ROUTER_CACHE_ENABLED("batch-get-router-cache-enabled", "brce", true, "Enable/Disable batch get cache in Router"),
  BATCH_GET_LIMIT("batch-get-limit", "bgl", true, "Key number limit inside one batch-get request"),
  NUM_VERSIONS_TO_PRESERVE("num-versions-to-preserve", "nvp", true, "Number of version that store should preserve."),
  KAFKA_BOOTSTRAP_SERVERS("kafka-bootstrap-servers", "kbs", true, "Kafka bootstrap server URL(s)"),
  KAFKA_BOOTSTRAP_SERVERS_DESTINATION("kafka-bootstrap-servers-dest", "kbd", true, "Kafka bootstrap server URL(s) for the destination cluster"),
  KAFKA_ZOOKEEPER_CONNECTION_URL("kafka-zk-url", "kzu", true, "Kafka's Zookeeper URL(s)"),
  KAFKA_ZOOKEEPER_CONNECTION_URL_SOURCE("kafka-zk-url-source", "kzs", true, "Kafka's Zookeeper URL(s) for the source cluster"),
  KAFKA_ZOOKEEPER_CONNECTION_URL_DESTINATION("kafka-zk-url-dest", "kzd", true, "Kafka's Zookeeper URL(s) for the destination cluster"),
  KAFKA_TOPIC_WHITELIST("kafka-topic-whitelist", "ktw", true, "Kafka topic whilelist"),
  KAFKA_TOPIC_NAME("kafka-topic-name", "ktn", true, "Kafka topic name"),
  KAFKA_TOPIC_PARTITION("kafka-topic-partition", "ktp", true, "Kafka topic partition number"),
  KAFKA_CONSUMER_CONFIG_FILE("kafka-conumer-config-file", "kcc", true, "Configuration file for SSL (optional, if plain-text is available)"),
  KAFKA_PRODUCER_CONFIG_FILE("kafka-producer-config-file", "kpc", true, "Configuration file for SSL (optional, if plain-text is available)"),
  KAFKA_OPERATION_TIMEOUT("kafka-operation-timeout", "kot", true, "Timeout in seconds for Kafka operations (default: 30 sec)"),
  VENICE_CLIENT_SSL_CONFIG_FILE("venice-client-ssl-config-file", "vcsc", true,
      "Configuration file for querying key in Venice client through SSL."),
  STARTING_OFFSET("starting_offset", "so", true, "Starting offset when dumping admin messages, inclusive"),
  MESSAGE_COUNT("message_count", "mc", true, "Max message count when dumping admin messages"),
  WRITE_COMPUTATION_ENABLED("write-computation-enabled", "wc", true, "Whether or not write computation is enabled for a store"),
  READ_COMPUTATION_ENABLED("read-computation-enabled", "rc", true, "Enable/Disable read computation for a store"),
  BOOTSTRAP_TO_ONLINE_TIMEOUT("bootstrap-to-online-timeout", "btot", true, "Set the maximum number of hours allowed for the store to transition from bootstrap to online"),
  LEADER_FOLLOWER_MODEL_ENABLED("leader-follower-model-enabled", "lf", true, "whether or not to use L/F Helix transition model for upcoming version"),
  SKIP_DIV("skip-div", "div", true, "Whether or not to only skip DIV for skip admin"),
  BACKUP_STRATEGY("backup-strategy", "bus", true, "Strategies to preserve backup versions, eg KEEP_MIN_VERSIONS, DELETE_ON_NEW_PUSH_START. Default is KEEP_MIN_VERSIONS"),

  FILTER_JSON("filter-json", "ftj", true, "Comma-delimited list of fields to display from the json output.  Omit to display all fields"),
  FLAT_JSON("flat-json", "flj", false, "Display output as flat json, without pretty-print indentation and line breaks"),
  HELP("help", "h", false, "Show usage"),
  FORCE("force", "f", false, "Force execute this operation"),
  INCLUDE_SYSTEM_STORES("include-system-stores", "iss", true, "Include internal stores maintained by the system.");

  private final String argName;
  private final String first;
  private final boolean parameterized;
  private final String helpText;

  Arg(String argName, String first, boolean parameterized, String helpText) {
    this.argName = argName;
    this.first = first;
    this.parameterized = parameterized;
    this.helpText = helpText;
  }

  @Override
  public String toString() {
    return argName;
  }

  public String first() {
    return first;
  }

  public String getHelpText() {
    return helpText;
  }

  public boolean isParameterized() {
    return parameterized;
  }
}
