package org.labubus.search;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import org.labubus.search.controller.SearchController;
import org.labubus.search.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.config.Config;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MultiMapConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import io.javalin.Javalin;

public class SearchApp {
    private static final Logger logger = LoggerFactory.getLogger(SearchApp.class);

    private static final String ENV_CURRENT_NODE_IP = "CURRENT_NODE_IP";
    private static final String ENV_CLUSTER_NODES_LIST = "CLUSTER_NODES_LIST";

    public static void main(String[] args) {
        try {
            Properties config = loadConfiguration();

            // 1. Create the Hazelcast instance with explicit configuration
            HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(createHazelcastConfig(config));
            logger.info("Hazelcast instance created programmatically and joined the cluster.");

            // 2. Start the web server and register API endpoints
            Javalin app = startJavalinApp(config, hazelcastInstance);

            // 3. Register a shutdown hook to gracefully close resources
            addShutdownHook(hazelcastInstance, app);

            logger.info("Search Service started successfully.");

        } catch (Exception e) {
            logger.error("Failed to start Search Service", e);
            System.exit(1);
        }
    }

    /**
     * Creates and configures a Hazelcast instance programmatically.
     * This configuration MUST be consistent with the IndexingApp.
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
     * Creates and starts the Javalin web server, including services and controllers.
     */
    private static Javalin startJavalinApp(Properties config, HazelcastInstance hazelcastInstance) {
        int port = requireInt(config, "server.port");
        int maxResults = requireInt(config, "search.max.results");
        int defaultLimit = requireInt(config, "search.default.limit");

        String metadataMapName = requireProperty(config, "hazelcast.map.metadata.name");
        String invertedIndexName = requireProperty(config, "hazelcast.multimap.invertedIndex.name");

        SearchService searchService = new SearchService(hazelcastInstance, maxResults, metadataMapName, invertedIndexName);
        SearchController controller = new SearchController(searchService, defaultLimit);

        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
        controller.registerRoutes(app);

        logger.info("Javalin server started on port {}", port);
        return app;
    }

    /**
     * Adds a hook to the JVM to ensure services are shut down gracefully on exit.
     */
    private static void addShutdownHook(HazelcastInstance hazelcast, Javalin app) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down Search Service...");
            hazelcast.shutdown();
            app.stop();
            logger.info("Search Service stopped.");
        }));
    }

    /**
     * Loads configuration from application.properties and merges with environment variables.
     */
    private static Properties loadConfiguration() {
        Properties properties = new Properties();
        try (InputStream input = SearchApp.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            logger.warn("Could not load application.properties", e);
        }
        properties.putAll(System.getenv());

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