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

    private static final int HAZELCAST_PORT = 5701;

    public static void main(String[] args) {
        try {
            Properties config = loadConfiguration();

            // 1. Create the Hazelcast instance with explicit configuration
            HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(createHazelcastConfig(config));
            logger.info("Hazelcast instance created programmatically and joined the cluster.");

            // 2. Create services and background listeners
                IndexingService indexingService = new IndexingService(
                    hazelcastInstance,
                    config.getProperty("datalake.path")
                );
            IngestionMessageListener messageListener = new IngestionMessageListener(
                    config.getProperty("activemq.broker.url"),
                    config.getProperty("activemq.queue.name"),
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
        config.setClusterName("search-engine-cluster");

        // Fixed port required for a physical cluster with host port exposure
        config.getNetworkConfig().setPort(HAZELCAST_PORT).setPortAutoIncrement(false);

        // Ensure members advertise a routable address (host IP), not the container IP.
        String currentNodeIp = properties.getProperty("CURRENT_NODE_IP", "localhost").trim();
        if (!currentNodeIp.isEmpty()) {
            config.getNetworkConfig().setPublicAddress(currentNodeIp + ":" + HAZELCAST_PORT);
        }

        MapConfig metadataMapConfig = new MapConfig("book-metadata").setBackupCount(1);
        MultiMapConfig indexMultiMapConfig = new MultiMapConfig("inverted-index").setBackupCount(1);
        config.addMapConfig(metadataMapConfig);
        config.addMultiMapConfig(indexMultiMapConfig);

        // Discovery: multicast is blocked in the lab, so use TCP-IP with static node list.
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        var tcpIpConfig = config.getNetworkConfig().getJoin().getTcpIpConfig();
        tcpIpConfig.setEnabled(true);
        tcpIpConfig.getMembers().clear();

        String nodesCsv = properties.getProperty("CLUSTER_NODES_LIST", "localhost");
        Arrays.stream(nodesCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .forEach(ip -> tcpIpConfig.addMember(ip + ":" + HAZELCAST_PORT));

        JavaSerializationFilterConfig javaFilterConfig = new JavaSerializationFilterConfig();
        javaFilterConfig.getWhitelist().addClasses("org.labubus.model.BookMetadata");
        config.getSerializationConfig().setJavaSerializationFilterConfig(javaFilterConfig);

        return config;
    }

    /**
     * Creates and starts the Javalin web server for API endpoints.
     */
    private static Javalin startJavalinApp(Properties config, IndexingService indexingService) {
        int port = Integer.parseInt(config.getProperty("server.port", "7002"));
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

        // --- Standardized env vars (default to localhost for testing) ---
        properties.putIfAbsent("CURRENT_NODE_IP", "localhost");
        properties.putIfAbsent("MASTER_NODE_IP", "localhost");
        properties.putIfAbsent("CLUSTER_NODES_LIST", "localhost");

        // Normalize datalake path (internal container volume path)
        String dataVolumePath = properties.getProperty("DATA_VOLUME_PATH");
        if (dataVolumePath == null || dataVolumePath.isBlank()) {
            dataVolumePath = properties.getProperty("datalake.path", "../datalake");
            properties.setProperty("DATA_VOLUME_PATH", dataVolumePath);
        }
        properties.setProperty("datalake.path", dataVolumePath);

        // Compute broker URL from MASTER_NODE_IP unless explicitly set.
        String masterIp = properties.getProperty("MASTER_NODE_IP", "localhost");
        String brokerUrl = properties.getProperty("activemq.broker.url", "");
        if (brokerUrl.isBlank() || brokerUrl.equals("tcp://localhost:61616")) {
            properties.setProperty("activemq.broker.url", "tcp://" + masterIp + ":61616");
        }
        return properties;
    }
}