package org.labubus.ingestion;

import io.javalin.Javalin;
import org.labubus.ingestion.distributed.MessageProducer;
import org.labubus.ingestion.controller.IngestionController;
import org.labubus.ingestion.service.BookDownloader;
import org.labubus.ingestion.service.BookIngestionService;
import org.labubus.ingestion.service.GutenbergDownloader;
import org.labubus.ingestion.storage.BucketDatalakeStorage;
import org.labubus.ingestion.storage.DatalakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class IngestionApp {
    private static final Logger logger = LoggerFactory.getLogger(IngestionApp.class);

    public static void main(String[] args) {
        try {
            Properties config = loadConfiguration();

            logger.info("Starting Ingestion Service...");

            int port = Integer.parseInt(config.getProperty("server.port"));
            String brokerUrl = config.getProperty("activemq.broker.url");
            String queueName = config.getProperty("activemq.queue.name", "document.ingested");

            String baseUrl = config.getProperty("gutenberg.base.url");
            int timeout = Integer.parseInt(config.getProperty("gutenberg.download.timeout"));
            String datalakePath = config.getProperty("datalake.path");
            int bucketSize = Integer.parseInt(config.getProperty("datalake.bucket.size"));

            // Create dependencies
            DatalakeStorage storage = new BucketDatalakeStorage(datalakePath, bucketSize);
            BookDownloader downloader = new GutenbergDownloader(baseUrl, timeout);

            // NEW: Create the message producer
            MessageProducer messageProducer = new MessageProducer(brokerUrl, queueName);

            // Inject the new dependency into the service
            BookIngestionService ingestionService = new BookIngestionService(storage, downloader, messageProducer);
            IngestionController controller = new IngestionController(ingestionService, storage);

            // Start Javalin (same as before)
            Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
            controller.registerRoutes(app);

            logger.info("Ingestion Service started on port {}", port);

        } catch (Exception e) {
            logger.error("Failed to start Ingestion Service", e);
            System.exit(1);
        }
    }

    private static Properties loadConfiguration() {
        Properties properties = new Properties();
        try (InputStream input = IngestionApp.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException e) {
            logger.warn("Could not load application.properties", e);
        }
        return properties;
    }
}