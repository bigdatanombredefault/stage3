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
            config.putAll(System.getenv());

            logger.info("Starting Ingestion Service...");

            int port = Integer.parseInt(config.getProperty("server.port", "7001"));
            String brokerUrl = config.getProperty("activemq.broker.url");
            String queueName = config.getProperty("activemq.queue.name", "document.ingested");
            String datalakePath = config.getProperty("datalake.path");
            String baseUrl = config.getProperty("gutenberg.base.url", "https://www.gutenberg.org/cache/epub");
            int timeout = Integer.parseInt(config.getProperty("gutenberg.download.timeout", "30000"));

            // 1. Create dependencies
            DatalakeStorage storage = new BucketDatalakeStorage(datalakePath, 10);
            BookDownloader downloader = new GutenbergDownloader(baseUrl, timeout);
            MessageProducer messageProducer = new MessageProducer(brokerUrl, queueName);
            BookIngestionService ingestionService = new BookIngestionService(storage, downloader, messageProducer);
            IngestionController controller = new IngestionController(ingestionService, storage);

            // 2. Start the Javalin server (this is the long-running process)
            Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);

            // 3. Register the API routes
            controller.registerRoutes(app);

            // --- END OF MISSING LOGIC ---

            logger.info("Ingestion Service started and listening on port {}", port);

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