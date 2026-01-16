package org.labubus.ingestion.controller;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jms.JMSException;

import org.labubus.ingestion.model.IngestionResponse;
import org.labubus.ingestion.model.IngestionStatusResponse;
import org.labubus.ingestion.service.BookFormatException;
import org.labubus.ingestion.service.BookIngestionService;
import org.labubus.ingestion.service.BookNotFoundException;
import org.labubus.ingestion.storage.DatalakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.Javalin;
import io.javalin.http.Context;

public class IngestionController {
    private static final Logger logger = LoggerFactory.getLogger(IngestionController.class);

    private final BookIngestionService ingestionService;
    private final DatalakeStorage storage;

    public IngestionController(BookIngestionService ingestionService, DatalakeStorage storage) {
        this.ingestionService = ingestionService;
        this.storage = storage;
    }

    public void registerRoutes(Javalin app) {
        app.get("/health", this::handleHealth);
        app.post("/ingest/{book_id}", this::handleIngest);
        app.get("/ingest/status/{book_id}", this::handleStatus);
        app.get("/ingest/list", this::handleList);
        logger.info("Ingestion routes registered");
    }

    private void handleHealth(Context ctx) {
        Map<String, Object> health = new HashMap<>();
        health.put("service", "ingestion-service");
        health.put("status", "running");
        health.put("timestamp", System.currentTimeMillis());
        try {
            health.put("books_downloaded", storage.getDownloadedBooksCount());
        } catch (IOException e) {
            health.put("books_downloaded", "error");
            logger.error("Error getting book count for health check", e);
        }
        ctx.json(health);
    }

    private void handleIngest(Context ctx) {
        String bookIdParam = ctx.pathParam("book_id");
        int bookId = -1;
        try {
            bookId = Integer.parseInt(bookIdParam);
            logger.info("Received ingest request for book {}", bookId);

            String asyncParam = ctx.queryParam("async");
            boolean async = asyncParam != null ? Boolean.parseBoolean(asyncParam) : ingestionService.isAsyncEnabledByDefault();

            if (ingestionService.isBookDownloaded(bookId)) {
                String path = ingestionService.getBookPath(bookId);
                ctx.status(200).json(IngestionResponse.alreadyExists(bookId, path));
                logger.info("Book {} already exists at {}", bookId, path);
                return;
            }

            if (async) {
                var status = ingestionService.startAsyncIngestion(bookId);
                ctx.status(202).json(IngestionResponse.accepted(status.bookId(), status.status(), status.path(), status.message()));
                logger.info("Accepted async ingestion for book {} (status={})", bookId, status.status());
                return;
            }

            String path = ingestionService.downloadAndSave(bookId);
            ctx.status(201).json(IngestionResponse.success(bookId, path));
            logger.info("Successfully ingested book {} to {}", bookId, path);

        } catch (NumberFormatException e) {
            ctx.status(400).json(IngestionResponse.failure(-1, "Invalid book_id format. Must be an integer."));
            logger.warn("Invalid book_id format in request: {}", bookIdParam);
        } catch (BookNotFoundException e) {
            logger.info("Book {} not found on source: {}", bookId, e.getMessage());
            ctx.status(404).json(IngestionResponse.failure(bookId, e.getMessage()));
        } catch (BookFormatException e) {
            logger.warn("Book {} has unsupported format: {}", bookId, e.getMessage());
            ctx.status(422).json(IngestionResponse.failure(bookId, e.getMessage()));
        } catch (IOException | JMSException e) {
            logger.error("Failed to ingest book {}: {}", bookId, e.getMessage(), e);
            ctx.status(500).json(IngestionResponse.failure(bookId, e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Unexpected failure ingesting book {}: {}", bookId, e.getMessage(), e);
            ctx.status(500).json(IngestionResponse.failure(bookId, "Unexpected ingestion failure: " + e.getMessage()));
        }
    }

    private void handleStatus(Context ctx) {
        String bookIdParam = ctx.pathParam("book_id");
        try {
            int bookId = Integer.parseInt(bookIdParam);
            logger.debug("Checking status for book {}", bookId);

            var status = ingestionService.getJobStatus(bookId);
            if ("not_found".equals(status.status())) {
                ctx.status(404).json(IngestionStatusResponse.notFound(bookId));
                return;
            }

            ctx.status(200).json(IngestionStatusResponse.status(status.bookId(), status.status(), status.path(), status.message()));
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid book_id format. Must be an integer."));
            logger.warn("Invalid book_id format in status request: {}", bookIdParam);
        }
    }

    private void handleList(Context ctx) {
        try {
            List<Integer> books = storage.getDownloadedBooksList();
            ctx.status(200).json(Map.of("count", books.size(), "books", books));
            logger.debug("Listed {} downloaded books", books.size());
        } catch (IOException e) {
            logger.error("Failed to list books", e);
            ctx.status(500).json(Map.of("error", "Failed to retrieve book list: " + e.getMessage()));
        }
    }
}