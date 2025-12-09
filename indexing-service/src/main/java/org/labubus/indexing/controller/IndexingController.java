package org.labubus.indexing.controller;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
// I'm assuming IndexResponse and IndexStatusResponse are simple model classes.
// We may need to adjust IndexStatusResponse.
import org.labubus.indexing.model.IndexResponse;
import org.labubus.indexing.model.IndexStatusResponse;
import org.labubus.indexing.service.IndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class IndexingController {
    private static final Logger logger = LoggerFactory.getLogger(IndexingController.class);
    private static final Gson gson = new Gson();
    private final IndexingService indexingService;

    public IndexingController(IndexingService indexingService) {
        this.indexingService = indexingService;
    }

    public void registerRoutes(Javalin app) {
        app.get("/health", this::handleHealth);
        app.post("/index/update/{book_id}", this::handleIndexUpdate);
        app.post("/index/rebuild", this::handleIndexRebuild);
        app.get("/index/status", this::handleIndexStatus);
        logger.info("Indexing routes registered");
    }

    private void handleHealth(Context ctx) {
        Map<String, Object> health = new HashMap<>();
        health.put("service", "indexing-service");
        health.put("status", "running");
        health.put("timestamp", System.currentTimeMillis());

        try {
            IndexingService.IndexStats stats = indexingService.getStats();
            health.put("books_indexed", stats.booksIndexed());
            health.put("unique_words", stats.uniqueWords());
        } catch (Exception e) {
            health.put("books_indexed", "error");
            logger.error("Error getting stats for health check", e);
        }

        ctx.json(health);
    }

    private void handleIndexUpdate(Context ctx) {
        String bookIdParam = ctx.pathParam("book_id");
        try {
            int bookId = Integer.parseInt(bookIdParam);
            logger.info("Received index update request for book {}", bookId);
            indexingService.indexBook(bookId);
            ctx.status(200).json(IndexResponse.updated(bookId));
            logger.info("Successfully indexed book {}", bookId);
        } catch (NumberFormatException e) {
            ctx.status(400).json(IndexResponse.failed(-1, "Invalid book_id format. Must be an integer."));
            logger.warn("Invalid book_id format in request: {}", bookIdParam);
        } catch (Exception e) {
            logger.error("Failed to index book {}: {}", bookIdParam, e.getMessage(), e);
            int bookId = -1;
            try { bookId = Integer.parseInt(bookIdParam); } catch (NumberFormatException ignored) {}
            ctx.status(500).json(IndexResponse.failed(bookId, e.getMessage()));
        }
    }

    private void handleIndexRebuild(Context ctx) {
        try {
            logger.info("Received index rebuild request");
            int booksIndexed = indexingService.rebuildIndex();
            Map<String, Object> response = Map.of(
                    "status", "completed",
                    "books_indexed", booksIndexed
            );
            ctx.status(200).json(response);
            logger.info("Successfully rebuilt index with {} books", booksIndexed);
        } catch (Exception e) {
            logger.error("Failed to rebuild index", e);
            ctx.status(500).json(Map.of("status", "failed", "error", e.getMessage()));
        }
    }

    private void handleIndexStatus(Context ctx) {
        try {
            IndexingService.IndexStats stats = indexingService.getStats();

            Map<String, Object> response = Map.of(
                    "books_indexed", stats.booksIndexed(),
                    "unique_words", stats.uniqueWords(),
                    "last_update", java.time.LocalDateTime.now().toString()
            );

            ctx.status(200).json(response);
            logger.debug("Retrieved index status: {} books, {} unique words",
                    stats.booksIndexed(), stats.uniqueWords());

        } catch (Exception e) {
            logger.error("Failed to get index status", e);
            ctx.status(500).json(Map.of("error", "Failed to retrieve index status: " + e.getMessage()));
        }
    }
}