package org.labubus.indexing;

import com.hazelcast.config.Config;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MultiMapConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.javalin.Javalin;
import org.labubus.indexing.distributed.IngestionMessageListener;
import org.labubus.indexing.service.IndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class IndexingApp {
    private static final Logger logger = LoggerFactory.getLogger(IndexingApp.class);

    public static void main(String[] args) {
        try {
            Properties config = loadConfiguration();

            // 1. Create the Hazelcast instance with explicit configuration
            HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(createHazelcastConfig(config));
            logger.info("Hazelcast instance created programmatically and joined the cluster.");

            // 2. Create services and background listeners
            IndexingService indexingService = new IndexingService(hazelcastInstance);
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

        MapConfig metadataMapConfig = new MapConfig("book-metadata").setBackupCount(1);
        MultiMapConfig indexMultiMapConfig = new MultiMapConfig("inverted-index").setBackupCount(1);
        config.addMapConfig(metadataMapConfig);
        config.addMultiMapConfig(indexMultiMapConfig);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true)
                .addMember("indexing-service")
                .addMember("search-service");

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
        return properties;
    }
}