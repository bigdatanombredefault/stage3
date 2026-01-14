package org.labubus.search.web;

import org.labubus.search.controller.SearchController;

import io.javalin.Javalin;

/** HTTP server wiring for the Search Service. */
public final class SearchHttpServer {
    private SearchHttpServer() {}

    /**
     * Starts the Javalin HTTP server and registers routes.
     *
     * @param port port to bind
     * @param controller controller that registers routes
     * @return started {@link Javalin} instance
     */
    public static Javalin start(int port, SearchController controller) {
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
        controller.registerRoutes(app);
        return app;
    }
}
