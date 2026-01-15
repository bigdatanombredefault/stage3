package org.labubus.search.bootstrap;

import org.labubus.search.config.SearchConfig;
import org.labubus.search.controller.SearchController;
import org.labubus.search.hazelcast.HazelcastConfigFactory;
import org.labubus.search.service.SearchService;
import org.labubus.search.web.SearchHttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import io.javalin.Javalin;

/**
 * Application bootstrapper for the Search Service.
 *
 * <p>Loads configuration, starts Hazelcast and the HTTP API, and registers a JVM shutdown hook.</p>
 */
public final class SearchBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(SearchBootstrap.class);

    private SearchBootstrap() {}

    /**
     * Starts the Search Service.
     *
     * <p>On startup failure, logs the error and exits with code {@code 1}.</p>
     */
    public static void run() {
        try {
            start();
        } catch (Exception e) {
            logger.error("Failed to start Search Service", e);
            System.exit(1);
        }
    }

    private static void start() {
        SearchConfig cfg = SearchConfig.load();
        HazelcastInstance hz = startHazelcast(cfg);
        Javalin app = startHttp(cfg, hz);
        addShutdownHook(hz, app);
        logger.info("Search Service started successfully.");
    }

    private static HazelcastInstance startHazelcast(SearchConfig cfg) {
        var config = HazelcastConfigFactory.build(cfg.hazelcast());
        return Hazelcast.newHazelcastInstance(config);
    }

    private static Javalin startHttp(SearchConfig cfg, HazelcastInstance hz) {
        SearchService service = buildService(cfg, hz);
        SearchController controller = new SearchController(service, cfg.defaultLimit());
        return SearchHttpServer.start(cfg.serverPort(), controller);
    }

    private static SearchService buildService(SearchConfig cfg, HazelcastInstance hz) {
        return new SearchService(
            hz,
            cfg.maxResults(),
            cfg.hazelcast().metadataMapName(),
            cfg.hazelcast().invertedIndexName()
        );
    }

    private static void addShutdownHook(HazelcastInstance hz, Javalin app) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(hz, app)));
    }

    private static void shutdown(HazelcastInstance hz, Javalin app) {
        logger.info("Shutting down Search Service...");
        hz.shutdown();
        app.stop();
        logger.info("Search Service stopped.");
    }
}
