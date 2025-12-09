package org.labubus.indexing.controller;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
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

	/**
	 * Register all routes with the Javalin app
	 */
	public void registerRoutes(Javalin app) {
		app.get("/health", this::handleHealth);

		app.post("/index/update/{book_id}", this::handleIndexUpdate);

		app.post("/index/rebuild", this::handleIndexRebuild);

		app.get("/index/status", this::handleIndexStatus);

		logger.info("Indexing routes registered");
	}

	/**
	 * GET /health
	 * Health check endpoint
	 */
	private void handleHealth(Context ctx) {
		Map<String, Object> health = new HashMap<>();
		health.put("service", "indexing-service");
		health.put("status", "running");
		health.put("timestamp", System.currentTimeMillis());

		try {
			IndexingService.IndexStats stats = indexingService.getStats();
			health.put("books_indexed", stats.booksIndexed());
			health.put("unique_words", stats.uniqueWords());
			health.put("index_size_mb", String.format("%.2f", stats.indexSizeMB()));
		} catch (Exception e) {
			health.put("books_indexed", "error");
			logger.error("Error getting stats for health check", e);
		}

		ctx.result(gson.toJson(health));
	}

	/**
	 * POST /index/update/{book_id}
	 * Index a specific book
	 */
	private void handleIndexUpdate(Context ctx) {
		try {
			int bookId = Integer.parseInt(ctx.pathParam("book_id"));
			logger.info("Received index update request for book {}", bookId);

			indexingService.indexBook(bookId);

			IndexResponse response = IndexResponse.updated(bookId);
			ctx.status(200).result(gson.toJson(response));
			logger.info("Successfully indexed book {}", bookId);

		} catch (NumberFormatException e) {
			IndexResponse response = IndexResponse.failed(-1, "Invalid book_id format. Must be an integer.");
			ctx.status(400).result(gson.toJson(response));
			logger.warn("Invalid book_id format in request");

		} catch (Exception e) {
			int bookId = Integer.parseInt(ctx.pathParam("book_id"));
			IndexResponse response = IndexResponse.failed(bookId, e.getMessage());
			ctx.status(500).result(gson.toJson(response));
			logger.error("Failed to index book {}: {}", bookId, e.getMessage());
		}
	}

	/**
	 * POST /index/rebuild
	 * Rebuild entire index from all books in datalake
	 */
	private void handleIndexRebuild(Context ctx) {
		try {
			logger.info("Received index rebuild request");

			int booksIndexed = indexingService.rebuildIndex();

			Map<String, Object> response = new HashMap<>();
			response.put("status", "completed");
			response.put("books_indexed", booksIndexed);

			ctx.status(200).result(gson.toJson(response));
			logger.info("Successfully rebuilt index with {} books", booksIndexed);

		} catch (Exception e) {
			Map<String, String> error = new HashMap<>();
			error.put("status", "failed");
			error.put("error", e.getMessage());
			ctx.status(500).result(gson.toJson(error));
			logger.error("Failed to rebuild index: {}", e.getMessage());
		}
	}

	/**
	 * GET /index/status
	 * Get indexing statistics
	 */
	private void handleIndexStatus(Context ctx) {
		try {
			IndexingService.IndexStats stats = indexingService.getStats();

			IndexStatusResponse response = new IndexStatusResponse(
					stats.booksIndexed(),
					java.time.LocalDateTime.now().toString(),
					stats.indexSizeMB()
			);

			ctx.status(200).result(gson.toJson(response));
			logger.debug("Retrieved index status: {} books, {} MB",
					stats.booksIndexed(), stats.indexSizeMB());

		} catch (Exception e) {
			Map<String, String> error = new HashMap<>();
			error.put("error", "Failed to retrieve index status: " + e.getMessage());
			ctx.status(500).result(gson.toJson(error));
			logger.error("Failed to get index status: {}", e.getMessage());
		}
	}
}