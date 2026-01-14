package org.labubus.ingestion.web;

import org.labubus.ingestion.controller.DatalakeController;
import org.labubus.ingestion.controller.IngestionController;

import io.javalin.Javalin;

/** HTTP server wiring for the Ingestion Service. */
public final class IngestionHttpServer {
    private IngestionHttpServer() {}

    /**
     * Starts the Javalin HTTP server and registers routes.
     *
     * @param port port to bind
     * @param controller controller that registers routes
     * @param datalakeController controller that receives replicated datalake content
     * @return started {@link Javalin} instance
     */
    public static Javalin start(int port, IngestionController controller, DatalakeController datalakeController) {
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
        controller.registerRoutes(app);
        datalakeController.registerRoutes(app);
        return app;
    }
}
