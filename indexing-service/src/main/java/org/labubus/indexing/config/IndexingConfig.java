package org.labubus.indexing.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Typed configuration for the Indexing Service.
 *
 * <p>Loads {@code application.properties}, then overlays environment variables. Some convenience normalization
 * is applied:
 * <ul>
 *   <li>If {@code DATA_VOLUME_PATH} is set, it overrides {@code datalake.path}.</li>
 *   <li>If {@code activemq.broker.url} is blank/missing, it is derived from {@code MASTER_NODE_IP}.</li>
 * </ul>
 * Missing required keys fail fast with {@link IllegalStateException}.</p>
 */
public record IndexingConfig(
    int serverPort,
    Hazelcast hazelcast,
    ActiveMq activeMq,
    Datalake datalake
) {
    /** Hazelcast cluster and data-structure settings used by the Indexing Service. */
    public record Hazelcast(
        String clusterName,
        int port,
        int backupCount,
        int asyncBackupCount,
        String currentNodeIp,
        List<String> members,
        String metadataMapName,
        String invertedIndexName
    ) {}

    /** ActiveMQ connectivity settings used to consume ingestion messages. */
    public record ActiveMq(String brokerUrl, String queueName) {}

    /** Datalake location and tracking file settings. */
    public record Datalake(String path, String trackingFilename) {}

    /**
     * Loads configuration from classpath properties plus environment variables.
     *
     * @return a fully-initialized {@link IndexingConfig}
     */
    public static IndexingConfig load() {
        Properties properties = loadProperties("application.properties");
        overlayEnvironment(properties);
        normalizeDatalakePath(properties);
        ensureBrokerUrl(properties);
        return from(properties);
    }

    private static IndexingConfig from(Properties p) {
        return new IndexingConfig(
            requireInt(p, "server.port"),
            readHazelcast(p),
            readActiveMq(p),
            readDatalake(p)
        );
    }

    private static Hazelcast readHazelcast(Properties p) {
        return new Hazelcast(
            requireString(p, "hazelcast.cluster.name"),
            requireInt(p, "hazelcast.port"),
            requireInt(p, "hazelcast.backup.count"),
            requireInt(p, "hazelcast.async.backup.count"),
            requireString(p, "CURRENT_NODE_IP"),
            splitCsv(requireString(p, "CLUSTER_NODES_LIST")),
            requireString(p, "hazelcast.map.metadata.name"),
            requireString(p, "hazelcast.multimap.invertedIndex.name")
        );
    }

    private static ActiveMq readActiveMq(Properties p) {
        return new ActiveMq(requireString(p, "activemq.broker.url"), requireString(p, "activemq.queue.name"));
    }

    private static Datalake readDatalake(Properties p) {
        return new Datalake(requireString(p, "datalake.path"), requireString(p, "datalake.tracking.filename"));
    }

    private static Properties loadProperties(String resourceName) {
        Properties properties = new Properties();
        try (InputStream in = IndexingConfig.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + resourceName, e);
        }
        return properties;
    }

    private static void overlayEnvironment(Properties properties) {
        properties.putAll(System.getenv());
    }

    private static void normalizeDatalakePath(Properties properties) {
        String volume = trimToNull(properties.getProperty("DATA_VOLUME_PATH"));
        if (volume != null) {
            properties.setProperty("datalake.path", volume);
        }
    }

    private static void ensureBrokerUrl(Properties properties) {
        String brokerUrl = trimToNull(properties.getProperty("activemq.broker.url"));
        if (brokerUrl != null) {
            return;
        }

        String brokerEnv = trimToNull(properties.getProperty("BROKER_URL"));
        if (brokerEnv != null) {
            properties.setProperty("activemq.broker.url", brokerEnv);
            return;
        }

        String masterIp = requireString(properties, "MASTER_NODE_IP");
        properties.setProperty("activemq.broker.url", "tcp://" + masterIp + ":61616");
    }

    private static List<String> splitCsv(String csv) {
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    private static String requireString(Properties properties, String key) {
        String value = trimToNull(properties.getProperty(key));
        if (value == null) {
            throw new IllegalStateException("Missing required configuration: " + key);
        }
        return value;
    }

    private static int requireInt(Properties properties, String key) {
        String value = requireString(properties, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid integer for configuration '" + key + "': '" + value + "'", e);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
