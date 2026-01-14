package org.labubus.ingestion.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Typed configuration for the Ingestion Service.
 *
 * <p>Loads {@code application.properties}, then overlays environment variables. Some convenience normalization
 * is applied:
 * <ul>
 *   <li>If {@code DATA_VOLUME_PATH} is set, it overrides {@code datalake.path}.</li>
 *   <li>If {@code activemq.broker.url} is blank/missing, it is derived from {@code MASTER_NODE_IP}.</li>
 * </ul>
 * Missing required keys fail fast with {@link IllegalStateException}.</p>
 */
public record IngestionConfig(
    int serverPort,
    ActiveMq activeMq,
    Datalake datalake,
    Gutenberg gutenberg
) {
    /** ActiveMQ connectivity settings used to publish ingestion messages. */
    public record ActiveMq(String brokerUrl, String queueName) {}

    /** Datalake settings. {@code type} selects an implementation (e.g. {@code timestamp} or {@code bucket}). */
    public record Datalake(String type, String path, int bucketSize, String trackingFilename) {}

    /** Project Gutenberg download settings. */
    public record Gutenberg(String baseUrl, int timeoutMs) {}

    /**
     * Loads configuration from classpath properties plus environment variables.
     *
     * @return a fully-initialized {@link IngestionConfig}
     */
    public static IngestionConfig load() {
        Properties properties = loadProperties("application.properties");
        overlayEnvironment(properties);
        normalizeDatalakePath(properties);
        ensureBrokerUrl(properties);
        return from(properties);
    }

    private static IngestionConfig from(Properties p) {
        return new IngestionConfig(
            requireInt(p, "server.port"),
            readActiveMq(p),
            readDatalake(p),
            readGutenberg(p)
        );
    }

    private static ActiveMq readActiveMq(Properties p) {
        return new ActiveMq(requireString(p, "activemq.broker.url"), requireString(p, "activemq.queue.name"));
    }

    private static Datalake readDatalake(Properties p) {
        return new Datalake(
            requireString(p, "datalake.type").trim().toLowerCase(),
            requireString(p, "datalake.path"),
            requireInt(p, "datalake.bucket.size"),
            requireString(p, "datalake.tracking.filename")
        );
    }

    private static Gutenberg readGutenberg(Properties p) {
        return new Gutenberg(requireString(p, "gutenberg.base.url"), requireInt(p, "gutenberg.download.timeout"));
    }

    private static Properties loadProperties(String resourceName) {
        Properties properties = new Properties();
        try (InputStream in = IngestionConfig.class.getClassLoader().getResourceAsStream(resourceName)) {
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
        String masterIp = requireString(properties, "MASTER_NODE_IP");
        properties.setProperty("activemq.broker.url", "tcp://" + masterIp + ":61616");
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
