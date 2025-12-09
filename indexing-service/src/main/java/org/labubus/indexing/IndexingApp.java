package org.labubus.indexing;

import com.hazelcast.config.Config;
import com.hazelcast.config.FileSystemXmlConfig;
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
            config.putAll(System.getenv());
            Config hazelcastConfig = new FileSystemXmlConfig("hazelcast.xml");

            int port = Integer.parseInt(config.getProperty("server.port", "7002"));
            String brokerUrl = config.getProperty("activemq.broker.url", "tcp://localhost:61616");
            String queueName = config.getProperty("activemq.queue.name", "document.ingested");

            logger.info("Starting Indexing Service...");

            // 1. Start Hazelcast and join the cluster
            HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(hazelcastConfig);
            logger.info("Hazelcast instance created and joined the cluster.");

            // 2. Create the refactored IndexingService
            IndexingService indexingService = new IndexingService(hazelcastInstance);

            // 3. Start the ActiveMQ listener in a background thread
            IngestionMessageListener messageListener = new IngestionMessageListener(brokerUrl, queueName, indexingService);
            messageListener.start();

            // 4. (Optional) Start a Javalin server for health/stats endpoints
            Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
            // Example of a stats endpoint
            app.get("/stats", ctx -> {
                IndexingService.IndexStats stats = indexingService.getStats();
                ctx.json(stats);
            });

            logger.info("Indexing Service started. Listening for messages from {}", brokerUrl);

            // Add a shutdown hook to gracefully close resources
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down Indexing Service...");
                messageListener.stop();
                hazelcastInstance.shutdown();
                app.stop();
                logger.info("Indexing Service stopped.");
            }));

        } catch (Exception e) {
            logger.error("Failed to start Indexing Service", e);
            System.exit(1);
        }
    }

    private static Properties loadConfiguration() {
        // This helper method can remain unchanged
        Properties properties = new Properties();
        try (InputStream input = IndexingApp.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) properties.load(input);
        } catch (IOException e) {
            logger.warn("Could not load application.properties", e);
        }
        return properties;
    }
}