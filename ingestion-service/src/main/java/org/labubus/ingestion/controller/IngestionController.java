package org.labubus.ingestion.controller;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.labubus.ingestion.model.IngestionResponse;
import org.labubus.ingestion.model.IngestionStatusResponse;
import org.labubus.ingestion.service.BookIngestionService;
import org.labubus.ingestion.storage.DatalakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IngestionController {
	private static final Logger logger = LoggerFactory.getLogger(IngestionController.class);
	private static final Gson gson = new Gson();
	private final BookIngestionService ingestionService;
	private final DatalakeStorage storage;

	public IngestionController(BookIngestionService ingestionService, DatalakeStorage storage) {
		this.ingestionService = ingestionService;
		this.storage = storage;
	}

	/**
	 * Register all routes with the Javalin app
	 */
	public void registerRoutes(Javalin app) {
		app.get("/health", this::handleHealth);

		app.post("/ingest/{book_id}", this::handleIngest);

		app.get("/ingest/status/{book_id}", this::handleStatus);

		app.get("/ingest/list", this::handleList);

		logger.info("Ingestion routes registered");
	}

	/**
	 * GET /health
	 * Health check endpoint
	 */
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

		ctx.result(gson.toJson(health));
	}

	/**
	 * POST /ingest/{book_id}
	 * Download and ingest a book
	 */
	private void handleIngest(Context ctx) {
		try {
			int bookId = Integer.parseInt(ctx.pathParam("book_id"));
			logger.info("Received ingest request for book {}", bookId);

			if (ingestionService.isBookDownloaded(bookId)) {
				String path = ingestionService.getBookPath(bookId);
				IngestionResponse response = IngestionResponse.alreadyExists(bookId, path);
				ctx.status(200).result(gson.toJson(response));
				logger.info("Book {} already exists at {}", bookId, path);
				return;
			}

			String path = ingestionService.downloadAndSave(bookId);
			IngestionResponse response = IngestionResponse.success(bookId, path);
			ctx.status(201).result(gson.toJson(response));
			logger.info("Successfully ingested book {} to {}", bookId, path);

		} catch (NumberFormatException e) {
			IngestionResponse response = IngestionResponse.failure(
					-1,
					"Invalid book_id format. Must be an integer."
			);
			ctx.status(400).result(gson.toJson(response));
			logger.warn("Invalid book_id format in request");

		} catch (IOException e) {
			int bookId = Integer.parseInt(ctx.pathParam("book_id"));
			IngestionResponse response = IngestionResponse.failure(bookId, e.getMessage());
			ctx.status(500).result(gson.toJson(response));
			logger.error("Failed to ingest book {}: {}", bookId, e.getMessage());
		}
	}

	/**
	 * GET /ingest/status/{book_id}
	 * Check if a book has been downloaded
	 */
	private void handleStatus(Context ctx) {
		try {
			int bookId = Integer.parseInt(ctx.pathParam("book_id"));
			logger.debug("Checking status for book {}", bookId);

			if (ingestionService.isBookDownloaded(bookId)) {
				String path = ingestionService.getBookPath(bookId);
				IngestionStatusResponse response = IngestionStatusResponse.available(bookId, path);
				ctx.status(200).result(gson.toJson(response));
				logger.debug("Book {} is available at {}", bookId, path);
			} else {
				IngestionStatusResponse response = IngestionStatusResponse.notFound(bookId);
				ctx.status(404).result(gson.toJson(response));
				logger.debug("Book {} not found", bookId);
			}

		} catch (NumberFormatException e) {
			Map<String, String> error = new HashMap<>();
			error.put("error", "Invalid book_id format. Must be an integer.");
			ctx.status(400).result(gson.toJson(error));
			logger.warn("Invalid book_id format in status request");
		}
	}

	/**
	 * GET /ingest/list
	 * List all downloaded books
	 */
	private void handleList(Context ctx) {
		try {
			List<Integer> books = storage.getDownloadedBooksList();
			int count = books.size();

			Map<String, Object> response = new HashMap<>();
			response.put("count", count);
			response.put("books", books);

			ctx.status(200).result(gson.toJson(response));
			logger.debug("Listed {} downloaded books", count);

		} catch (IOException e) {
			Map<String, String> error = new HashMap<>();
			error.put("error", "Failed to retrieve book list: " + e.getMessage());
			ctx.status(500).result(gson.toJson(error));
			logger.error("Failed to list books: {}", e.getMessage());
		}
	}
}