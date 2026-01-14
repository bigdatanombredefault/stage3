package org.labubus.indexing.web;

import org.labubus.indexing.service.IndexingService;

import io.javalin.Javalin;

/** HTTP server wiring for the Indexing Service. */
public final class IndexingHttpServer {
    private IndexingHttpServer() {}

    /**
     * Starts the Javalin HTTP server and registers routes.
     *
     * @param port port to bind
     * @param indexingService service used by route handlers
     * @return started {@link Javalin} instance
     */
    public static Javalin start(int port, IndexingService indexingService) {
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
        registerRoutes(app, indexingService);
        return app;
    }

    private static void registerRoutes(Javalin app, IndexingService indexingService) {
        app.get("/stats", ctx -> ctx.json(indexingService.getStats()));
    }
}
