package org.labubus.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import org.labubus.indexing.distributed.IngestionMessageListener;
import org.labubus.indexing.service.IndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.config.Config;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MultiMapConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import io.javalin.Javalin;

public class IndexingApp {
    private static final Logger logger = LoggerFactory.getLogger(IndexingApp.class);

    private static final String ENV_CURRENT_NODE_IP = "CURRENT_NODE_IP";
    private static final String ENV_MASTER_NODE_IP = "MASTER_NODE_IP";
    private static final String ENV_CLUSTER_NODES_LIST = "CLUSTER_NODES_LIST";
    private static final String ENV_DATA_VOLUME_PATH = "DATA_VOLUME_PATH";

    public static void main(String[] args) {
        try {
            Properties config = loadConfiguration();

            // 1. Create the Hazelcast instance with explicit configuration
            HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(createHazelcastConfig(config));
            logger.info("Hazelcast instance created programmatically and joined the cluster.");

            // 2. Create services and background listeners
            IndexingService indexingService = new IndexingService(
                hazelcastInstance,
                requireProperty(config, "datalake.path"),
                requireProperty(config, "datalake.tracking.filename"),
                requireProperty(config, "hazelcast.map.metadata.name"),
                requireProperty(config, "hazelcast.multimap.invertedIndex.name"),
                requireInt(config, "indexing.shard.count")
            );
            IngestionMessageListener messageListener = new IngestionMessageListener(
                    requireProperty(config, "activemq.broker.url"),
                    requireProperty(config, "activemq.queue.name"),
                    indexingService
            );
            messageListener.start();

            // 3. Start the web server for health/stats
            Javalin app = startJavalinApp(config, indexingService);

            // 4. Register a shutdown hook to gracefully close resources
            addShutdownHook(hazelcastInstance, messageListener, app);

            logger.info("Indexing Service started successfully.");

        } catch (Exception e) {
            logger.error("Failed to start Indexing Service", e);
            System.exit(1);
        }
    }

    /**
     * Creates and configures a Hazelcast instance programmatically.
     */
    private static Config createHazelcastConfig(Properties properties) {
        Config config = new Config();

        String clusterName = requireProperty(properties, "hazelcast.cluster.name");
        int hazelcastPort = requireInt(properties, "hazelcast.port");
        int backupCount = requireInt(properties, "hazelcast.backup.count");
        String metadataMapName = requireProperty(properties, "hazelcast.map.metadata.name");
        String invertedIndexName = requireProperty(properties, "hazelcast.multimap.invertedIndex.name");

        config.setClusterName(clusterName);

        config.getNetworkConfig().setPort(hazelcastPort).setPortAutoIncrement(false);

        String currentNodeIp = requireProperty(properties, ENV_CURRENT_NODE_IP);
        config.getNetworkConfig().setPublicAddress(currentNodeIp + ":" + hazelcastPort);

        MapConfig metadataMapConfig = new MapConfig(metadataMapName).setBackupCount(backupCount);
        MultiMapConfig indexMultiMapConfig = new MultiMapConfig(invertedIndexName).setBackupCount(backupCount);
        config.addMapConfig(metadataMapConfig);
        config.addMultiMapConfig(indexMultiMapConfig);

        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        var tcpIpConfig = config.getNetworkConfig().getJoin().getTcpIpConfig();
        tcpIpConfig.setEnabled(true);
        tcpIpConfig.getMembers().clear();

        String nodesCsv = requireProperty(properties, ENV_CLUSTER_NODES_LIST);
        Arrays.stream(nodesCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .forEach(ip -> tcpIpConfig.addMember(ip + ":" + hazelcastPort));

        JavaSerializationFilterConfig javaFilterConfig = new JavaSerializationFilterConfig();
        javaFilterConfig.getWhitelist().addClasses("org.labubus.model.BookMetadata");
        config.getSerializationConfig().setJavaSerializationFilterConfig(javaFilterConfig);

        return config;
    }

    /**
     * Creates and starts the Javalin web server for API endpoints.
     */
    private static Javalin startJavalinApp(Properties config, IndexingService indexingService) {
        int port = requireInt(config, "server.port");
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);

        app.get("/stats", ctx -> {
            IndexingService.IndexStats stats = indexingService.getStats();
            ctx.json(stats);
        });

        logger.info("Javalin server started on port {}", port);
        return app;
    }

    /**
     * Adds a hook to the JVM to ensure services are shut down gracefully on exit.
     */
    private static void addShutdownHook(HazelcastInstance hazelcast, IngestionMessageListener listener, Javalin app) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down Indexing Service...");
            listener.stop();
            hazelcast.shutdown();
            app.stop();
            logger.info("Indexing Service stopped.");
        }));
    }

    /**
     * Loads configuration from application.properties and merges with environment variables.
     */
    private static Properties loadConfiguration() {
        Properties properties = new Properties();
        try (InputStream input = IndexingApp.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            logger.warn("Could not load application.properties", e);
        }
        // Environment variables override file properties
        properties.putAll(System.getenv());

        // Normalize datalake path (internal container volume path)
        String dataVolumePath = properties.getProperty(ENV_DATA_VOLUME_PATH);
        if (dataVolumePath == null || dataVolumePath.isBlank()) {
            String configuredDatalakePath = properties.getProperty("datalake.path");
            if (configuredDatalakePath != null && !configuredDatalakePath.isBlank()) {
                properties.setProperty(ENV_DATA_VOLUME_PATH, configuredDatalakePath.trim());
            }
        } else {
            properties.setProperty(ENV_DATA_VOLUME_PATH, dataVolumePath.trim());
        }

        if (properties.getProperty(ENV_DATA_VOLUME_PATH) != null && !properties.getProperty(ENV_DATA_VOLUME_PATH).isBlank()) {
            properties.setProperty("datalake.path", properties.getProperty(ENV_DATA_VOLUME_PATH).trim());
        }

        // Compute broker URL from MASTER_NODE_IP unless explicitly set.
        String brokerUrl = properties.getProperty("activemq.broker.url", "");
        if (brokerUrl.isBlank()) {
            String masterIp = requireProperty(properties, ENV_MASTER_NODE_IP);
            properties.setProperty("activemq.broker.url", "tcp://" + masterIp + ":61616");
        }
        return properties;
    }

    private static String requireProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required configuration: " + key);
        }
        return value.trim();
    }

    private static int requireInt(Properties properties, String key) {
        String value = requireProperty(properties, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid integer for configuration '" + key + "': '" + value + "'", e);
        }
    }
}