package org.labubus.ingestion.web;

import org.labubus.ingestion.controller.DatalakeController;
import org.labubus.ingestion.controller.IngestionController;

import io.javalin.Javalin;

public final class IngestionHttpServer {
    private IngestionHttpServer() {}

    public static Javalin start(int port, IngestionController controller, DatalakeController datalakeController) {
        Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false).start(port);
        controller.registerRoutes(app);
        datalakeController.registerRoutes(app);
        return app;
    }
}
