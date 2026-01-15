package org.labubus.search.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Typed configuration for the Search Service.
 *
 * <p>Loads {@code application.properties}, then overlays environment variables. Missing required keys fail
 * fast with {@link IllegalStateException}.</p>
 */
public record SearchConfig(
    int serverPort,
    int maxResults,
    int defaultLimit,
    Hazelcast hazelcast
) {
    /** Hazelcast cluster and data-structure settings used by the Search Service. */
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

    /**
     * Loads configuration from classpath properties plus environment variables.
     *
     * @return a fully-initialized {@link SearchConfig}
     */
    public static SearchConfig load() {
        Properties properties = loadProperties("application.properties");
        overlayEnvironment(properties);
        return from(properties);
    }

    private static SearchConfig from(Properties p) {
        return new SearchConfig(
            requireInt(p, "server.port"),
            requireInt(p, "search.max.results"),
            requireInt(p, "search.default.limit"),
            readHazelcast(p)
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

    private static Properties loadProperties(String resourceName) {
        Properties properties = new Properties();
        try (InputStream in = SearchConfig.class.getClassLoader().getResourceAsStream(resourceName)) {
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
