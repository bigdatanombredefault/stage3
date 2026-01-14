package org.labubus.ingestion.bootstrap;

import org.labubus.ingestion.config.IngestionConfig;
import org.labubus.ingestion.controller.DatalakeController;
import org.labubus.ingestion.controller.IngestionController;
import org.labubus.ingestion.distributed.DatalakeReplicationClient;
import org.labubus.ingestion.distributed.MessageProducer;
import org.labubus.ingestion.service.BookDownloader;
import org.labubus.ingestion.service.BookIngestionService;
import org.labubus.ingestion.service.GutenbergDownloader;
import org.labubus.ingestion.storage.DatalakeStorage;
import org.labubus.ingestion.storage.DatalakeStorageFactory;
import org.labubus.ingestion.web.IngestionHttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.Javalin;

/**
 * Application bootstrapper for the Ingestion Service.
 *
 * <p>Loads configuration, wires controller + services, starts the HTTP API, and registers a JVM shutdown hook.</p>
 */
public final class IngestionBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(IngestionBootstrap.class);

    private IngestionBootstrap() {}

    /**
     * Starts the Ingestion Service.
     *
     * <p>On startup failure, logs the error and exits with code {@code 1}.</p>
     */
    public static void run() {
        try {
            start();
        } catch (Exception e) {
            logger.error("Failed to start Ingestion Service", e);
            System.exit(1);
        }
    }

    private static void start() {
        IngestionConfig cfg = IngestionConfig.load();
        Javalin app = startHttp(cfg);
        addShutdownHook(app);
        logger.info("Ingestion Service started successfully.");
    }

    private static Javalin startHttp(IngestionConfig cfg) {
        DatalakeStorage storage = DatalakeStorageFactory.create(cfg.datalake());
        IngestionController ingestionController = buildIngestionController(cfg, storage);
        DatalakeController datalakeController = new DatalakeController(storage);
        return IngestionHttpServer.start(cfg.serverPort(), ingestionController, datalakeController);
    }

    private static IngestionController buildIngestionController(IngestionConfig cfg, DatalakeStorage storage) {
        BookDownloader downloader = new GutenbergDownloader(cfg.gutenberg().baseUrl(), cfg.gutenberg().timeoutMs());
        MessageProducer producer = new MessageProducer(cfg.activeMq().brokerUrl(), cfg.activeMq().queueName());
        DatalakeReplicationClient replicator = new DatalakeReplicationClient(cfg.replication().timeout());
        BookIngestionService ingestionService = new BookIngestionService(storage, downloader, producer, replicator, cfg.replication());
        return new IngestionController(ingestionService, storage);
    }

    private static void addShutdownHook(Javalin app) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(app)));
    }

    private static void shutdown(Javalin app) {
        logger.info("Shutting down Ingestion Service...");
        app.stop();
        logger.info("Ingestion Service stopped.");
    }
}
