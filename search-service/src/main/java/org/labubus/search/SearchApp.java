package org.labubus.search;

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
            int port = Integer.parseInt(config.getProperty("server.port", "7003"));
            int maxResults = Integer.parseInt(config.getProperty("search.max.results", "100"));

            logger.info("Starting Search Service...");

            // 1. Start Hazelcast and join the cluster
            HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance();
            logger.info("Hazelcast instance created and joined the cluster.");

            // 2. Create the refactored SearchService
            SearchService searchService = new SearchService(hazelcastInstance, maxResults);

            // 3. Create the controller with the new service
            SearchController controller = new SearchController(searchService, 10); // Assuming 10 is default limit

            // 4. Start Javalin and register routes (same as before)
            Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
            controller.registerRoutes(app);

            logger.info("Search Service started on port {}", port);

            // Add a shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down Search Service...");
                hazelcastInstance.shutdown();
                app.stop();
                logger.info("Search Service stopped.");
            }));

        } catch (Exception e) {
            logger.error("Failed to start Search Service", e);
            System.exit(1);
        }
    }

    private static Properties loadConfiguration() {
        // This helper method can remain unchanged
        Properties properties = new Properties();
        try (InputStream input = SearchApp.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException e) {
            logger.warn("Could not load application.properties", e);
        }
        return properties;
    }
}