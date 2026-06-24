package org.labubus.indexing.web;

import org.labubus.indexing.controller.IndexingController;
import org.labubus.indexing.service.IndexingService;

import io.javalin.Javalin;

public final class IndexingHttpServer {
    private IndexingHttpServer() {}

    public static Javalin start(int port, IndexingService indexingService) {
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
        registerRoutes(app, indexingService);
        return app;
    }

    private static void registerRoutes(Javalin app, IndexingService indexingService) {
        new IndexingController(indexingService).registerRoutes(app);

        app.get("/stats", ctx -> ctx.json(indexingService.getStats()));
    }
}
