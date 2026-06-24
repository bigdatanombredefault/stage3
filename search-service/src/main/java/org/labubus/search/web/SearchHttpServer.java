package org.labubus.search.web;

import org.labubus.search.controller.SearchController;

import io.javalin.Javalin;

public final class SearchHttpServer {
    private SearchHttpServer() {}

    public static Javalin start(int port, SearchController controller) {
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
        controller.registerRoutes(app);
        return app;
    }
}
