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
import org.labubus.ingestion.storage.TimestampDatalakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.Javalin;

public class IngestionApp {
    private static final Logger logger = LoggerFactory.getLogger(IngestionApp.class);

    private static final String ENV_MASTER_NODE_IP = "MASTER_NODE_IP";
    private static final String ENV_DATA_VOLUME_PATH = "DATA_VOLUME_PATH";

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
        int port = requireInt(config, "server.port");
        String brokerUrl = requireProperty(config, "activemq.broker.url");
        String queueName = requireProperty(config, "activemq.queue.name");
        String datalakePath = requireProperty(config, "datalake.path");

        String datalakeType = requireProperty(config, "datalake.type").trim().toLowerCase();
        int bucketSize = requireInt(config, "datalake.bucket.size");
        String trackingFilename = requireProperty(config, "datalake.tracking.filename");

        String baseUrl = requireProperty(config, "gutenberg.base.url");
        int timeout = requireInt(config, "gutenberg.download.timeout");

        // Create all necessary service instances
        DatalakeStorage storage = switch (datalakeType) {
            case "timestamp" -> new TimestampDatalakeStorage(datalakePath, trackingFilename);
            case "bucket" -> new BucketDatalakeStorage(datalakePath, bucketSize, trackingFilename);
            default -> throw new IllegalArgumentException("Unsupported datalake.type: " + datalakeType);
        };
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