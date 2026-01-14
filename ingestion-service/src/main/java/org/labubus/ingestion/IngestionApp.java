package org.labubus.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.labubus.ingestion.controller.IngestionController;
import org.labubus.ingestion.distributed.MessageProducer;
import org.labubus.ingestion.service.BookDownloader;
import org.labubus.ingestion.service.BookIngestionService;
import org.labubus.ingestion.service.GutenbergDownloader;
import org.labubus.ingestion.storage.BucketDatalakeStorage;
import org.labubus.ingestion.storage.DatalakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.Javalin;

public class IngestionApp {
    private static final Logger logger = LoggerFactory.getLogger(IngestionApp.class);

    public static void main(String[] args) {
        try {
            Properties config = loadConfiguration();

            // 1. Start the web server and its dependent services
            Javalin app = startJavalinApp(config);

            // 2. Register a shutdown hook to gracefully close resources
            addShutdownHook(app);

            logger.info("Ingestion Service started successfully.");

        } catch (Exception e) {
            logger.error("Failed to start Ingestion Service", e);
            System.exit(1);
        }
    }

    /**
     * Creates all services and controllers, then starts the Javalin web server.
     */
    private static Javalin startJavalinApp(Properties config) {
        int port = Integer.parseInt(config.getProperty("server.port", "7001"));
        String brokerUrl = config.getProperty("activemq.broker.url");
        String queueName = config.getProperty("activemq.queue.name");
        String datalakePath = config.getProperty("datalake.path");

        String baseUrl = config.getProperty("gutenberg.base.url", "https://www.gutenberg.org/cache/epub");
        int timeout = Integer.parseInt(config.getProperty("gutenberg.download.timeout", "30000"));

        // Create all necessary service instances
        DatalakeStorage storage = new BucketDatalakeStorage(datalakePath, 10);
        BookDownloader downloader = new GutenbergDownloader(baseUrl, timeout);
        MessageProducer messageProducer = new MessageProducer(brokerUrl, queueName);
        BookIngestionService ingestionService = new BookIngestionService(storage, downloader, messageProducer);
        IngestionController controller = new IngestionController(ingestionService, storage);

        // Start the web server and register API routes
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
        controller.registerRoutes(app);

        logger.info("Javalin server started on port {}", port);
        return app;
    }

    /**
     * Adds a hook to the JVM to ensure the server is shut down gracefully on exit.
     */
    private static void addShutdownHook(Javalin app) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down Ingestion Service...");
            app.stop();
            logger.info("Ingestion Service stopped.");
        }));
    }

    /**
     * Loads configuration from application.properties and merges with environment variables.
     */
    private static Properties loadConfiguration() {
        Properties properties = new Properties();
        try (InputStream input = IngestionApp.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            logger.warn("Could not load application.properties", e);
        }
        // Environment variables will override any properties from the file
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