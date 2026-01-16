package org.labubus.ingestion.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.labubus.ingestion.service.BookContentParser;
import org.labubus.ingestion.service.BookFormatException;
import org.labubus.ingestion.storage.DatalakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;

/**
 * Receives replicated datalake files from other crawler nodes.
 */
public class DatalakeController {
    private static final Logger logger = LoggerFactory.getLogger(DatalakeController.class);

    private final DatalakeStorage storage;

    public DatalakeController(DatalakeStorage storage) {
        this.storage = storage;
    }

    /**
     * Registers datalake receiver routes.
     */
    public void registerRoutes(Javalin app) {
        app.post("/api/datalake/store", this::handleStore);
        logger.info("Datalake receiver routes registered");
    }

    private void handleStore(Context ctx) {
        try {
            int bookId = parseBookId(ctx);
            UploadedFile file = ctx.uploadedFile("file");
            if (file == null) {
                ctx.status(400).json(java.util.Map.of("error", "Missing multipart field 'file'"));
                return;
            }

            String raw = new String(file.content().readAllBytes(), StandardCharsets.UTF_8);
            String[] parts = BookContentParser.splitHeaderBody(raw);
            String path = storage.saveBook(bookId, parts[0], parts[1]);

            ctx.status(201).json(java.util.Map.of("bookId", bookId, "path", path));
            logger.info("Stored replicated book {} at {}", bookId, path);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(java.util.Map.of("error", e.getMessage()));
        } catch (BookFormatException e) {
            logger.warn("Rejected replicated book due to unsupported format: {}", e.getMessage());
            ctx.status(422).json(java.util.Map.of("error", e.getMessage()));
        } catch (IOException e) {
            logger.error("Failed to store replicated book", e);
            ctx.status(500).json(java.util.Map.of("error", e.getMessage()));
        }
    }

    private static int parseBookId(Context ctx) {
        String id = firstNonBlank(ctx.formParam("bookId"), ctx.formParam("id"), ctx.formParam("book_id"));
        if (id == null) {
            throw new IllegalArgumentException("Missing required form field: bookId");
        }
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid bookId: must be an integer");
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
