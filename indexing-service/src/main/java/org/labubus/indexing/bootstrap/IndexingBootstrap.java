package org.labubus.indexing.bootstrap;

import org.labubus.indexing.config.IndexingConfig;
import org.labubus.indexing.distributed.IngestionMessageListener;
import org.labubus.indexing.hazelcast.HazelcastConfigFactory;
import org.labubus.indexing.service.IndexingService;
import org.labubus.indexing.web.IndexingHttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import io.javalin.Javalin;

/**
 * Application bootstrapper for the Indexing Service.
 *
 * <p>Loads configuration, starts Hazelcast, wires the ingestion listener and HTTP server,
 * and registers a JVM shutdown hook for clean termination.</p>
 */
public final class IndexingBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(IndexingBootstrap.class);

    private IndexingBootstrap() {}

    /**
     * Starts the Indexing Service.
     *
     * <p>On startup failure, logs the error and exits with code {@code 1}.</p>
     */
    public static void run() {
        try {
            start();
        } catch (Exception e) {
            logger.error("Failed to start Indexing Service", e);
            System.exit(1);
        }
    }

    private static void start() {
        IndexingConfig cfg = IndexingConfig.load();
        HazelcastInstance hz = startHazelcast(cfg);
        IndexingService service = buildService(hz, cfg);
        runStartupConsistencyCheck(service);
        IngestionMessageListener listener = startListener(cfg, service);
        Javalin app = startHttp(cfg, service);
        addShutdownHook(hz, listener, app);
        logger.info("Indexing Service started successfully.");
    }

    private static void runStartupConsistencyCheck(IndexingService service) {
        if (!service.isInvertedIndexEmpty()) {
            return;
        }

        logger.warn("Hazelcast inverted index is empty. Entering re-indexing mode...");
        try {
            int rebuilt = service.rebuildIndexFromLocalFiles();
            logger.info("Re-indexing mode complete. Indexed {} books.", rebuilt);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to rebuild index from local datalake", e);
        }
    }

    private static HazelcastInstance startHazelcast(IndexingConfig cfg) {
        var config = HazelcastConfigFactory.build(cfg.hazelcast());
        return Hazelcast.newHazelcastInstance(config);
    }

    private static IndexingService buildService(HazelcastInstance hz, IndexingConfig cfg) {
        return new IndexingService(
            hz,
            cfg.datalake().path(),
            cfg.datalake().trackingFilename(),
            cfg.hazelcast().metadataMapName(),
            cfg.hazelcast().invertedIndexName(),
            cfg.shardCount()
        );
    }

    private static IngestionMessageListener startListener(IndexingConfig cfg, IndexingService indexingService) {
        IngestionMessageListener listener = new IngestionMessageListener(
            cfg.activeMq().brokerUrl(),
            cfg.activeMq().queueName(),
            cfg.hazelcast().currentNodeIp(),
            indexingService
        );
        listener.start();
        return listener;
    }

    private static Javalin startHttp(IndexingConfig cfg, IndexingService indexingService) {
        return IndexingHttpServer.start(cfg.serverPort(), indexingService);
    }

    private static void addShutdownHook(HazelcastInstance hz, IngestionMessageListener listener, Javalin app) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(hz, listener, app)));
    }

    private static void shutdown(HazelcastInstance hz, IngestionMessageListener listener, Javalin app) {
        logger.info("Shutting down Indexing Service...");
        listener.stop();
        hz.shutdown();
        app.stop();
        logger.info("Indexing Service stopped.");
    }
}
