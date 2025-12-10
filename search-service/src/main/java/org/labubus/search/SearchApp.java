package org.labubus.search;

import com.hazelcast.config.Config;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MultiMapConfig;
import com.hazelcast.config.SerializationConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.javalin.Javalin;
import org.labubus.search.controller.SearchController;
import org.labubus.search.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class SearchApp {
    private static final Logger logger = LoggerFactory.getLogger(SearchApp.class);

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
        config.setClusterName("search-engine-cluster");

        MapConfig metadataMapConfig = new MapConfig("book-metadata").setBackupCount(1);
        MultiMapConfig indexMultiMapConfig = new MultiMapConfig("inverted-index").setBackupCount(1);
        config.addMapConfig(metadataMapConfig);
        config.addMultiMapConfig(indexMultiMapConfig);

        JavaSerializationFilterConfig javaFilterConfig = new JavaSerializationFilterConfig();
        javaFilterConfig.getWhitelist().addClasses("org.labubus.core.model.BookMetadata");
        config.getSerializationConfig().setJavaSerializationFilterConfig(javaFilterConfig);

        return config;
    }

    /**
     * Creates and starts the Javalin web server, including services and controllers.
     */
    private static Javalin startJavalinApp(Properties config, HazelcastInstance hazelcastInstance) {
        int port = Integer.parseInt(config.getProperty("server.port", "7003"));
        int maxResults = Integer.parseInt(config.getProperty("search.max.results", "100"));
        int defaultLimit = Integer.parseInt(config.getProperty("search.default.limit", "10"));

        // Create the services and controllers
        SearchService searchService = new SearchService(hazelcastInstance, maxResults);
        SearchController controller = new SearchController(searchService, defaultLimit);

        // Start the web server
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
        // Environment variables will override any properties from the file
        properties.putAll(System.getenv());
        return properties;
    }
}