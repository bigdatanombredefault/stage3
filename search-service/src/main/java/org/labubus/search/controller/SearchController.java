package org.labubus.search.controller;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.labubus.search.model.SearchResponse;
import org.labubus.search.model.SearchResult;
import org.labubus.search.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SearchController {
	private static final Logger logger = LoggerFactory.getLogger(SearchController.class);
	private static final Gson gson = new Gson();
	private final SearchService searchService;
	private final int defaultLimit;

	public SearchController(SearchService searchService, int defaultLimit) {
		this.searchService = searchService;
		this.defaultLimit = defaultLimit;
	}

	/**
	 * Register all routes with the Javalin app
	 */
	public void registerRoutes(Javalin app) {
		app.get("/health", this::handleHealth);

		app.get("/search", this::handleSearch);

		app.get("/books", this::handleBrowse);

		app.get("/stats", this::handleStats);

		logger.info("Search routes registered");
	}

	/**
	 * GET /health
	 * Health check endpoint
	 */
	private void handleHealth(Context ctx) {
		Map<String, Object> health = new HashMap<>();
		health.put("service", "search-service");
		health.put("status", "running");
		health.put("timestamp", System.currentTimeMillis());

		try {
			SearchService.SearchStats stats = searchService.getStats();
			health.put("total_books", stats.totalBooks());
			health.put("index_loaded", stats.indexLoaded());
			health.put("unique_words", stats.uniqueWords());
		} catch (Exception e) {
			health.put("total_books", "error");
			logger.error("Error getting stats for health check", e);
		}

		ctx.result(gson.toJson(health));
	}

	/**
	 * GET /search?q={query}&author={author}&language={lang}&year={year}&limit={limit}
	 * Search for books
	 */
	private void handleSearch(Context ctx) {
		try {
			String query = ctx.queryParam("q");
			String author = ctx.queryParam("author");
			String language = ctx.queryParam("language");
			String yearStr = ctx.queryParam("year");
			String limitStr = ctx.queryParam("limit");

			Integer year = null;
			if (yearStr != null && !yearStr.isEmpty()) {
				try {
					year = Integer.parseInt(yearStr);
				} catch (NumberFormatException e) {
					Map<String, String> error = new HashMap<>();
					error.put("error", "Invalid year format. Must be an integer.");
					ctx.status(400).result(gson.toJson(error));
					return;
				}
			}

			Integer limit = defaultLimit;
			if (limitStr != null && !limitStr.isEmpty()) {
				try {
					limit = Integer.parseInt(limitStr);
				} catch (NumberFormatException e) {
					Map<String, String> error = new HashMap<>();
					error.put("error", "Invalid limit format. Must be an integer.");
					ctx.status(400).result(gson.toJson(error));
					return;
				}
			}

			if (query == null || query.trim().isEmpty()) {
				Map<String, String> error = new HashMap<>();
				error.put("error", "Query parameter 'q' is required.");
				ctx.status(400).result(gson.toJson(error));
				return;
			}

			logger.info("Search request: q='{}', author='{}', language='{}', year={}, limit={}",
					query, author, language, year, limit);

			List<SearchResult> results = searchService.search(query, author, language, year, limit);

			SearchResponse response = new SearchResponse(
					query,
					results.size(),
					results.size(),
					results
			);

			ctx.status(200).result(gson.toJson(response));
			logger.info("Returned {} search results", results.size());

		} catch (Exception e) {
			Map<String, String> error = new HashMap<>();
			error.put("error", "Search failed: " + e.getMessage());
			ctx.status(500).result(gson.toJson(error));
			logger.error("Search failed", e);
		}
	}

	/**
	 * GET /books?limit={limit}
	 * Browse all books (no search query)
	 */
	private void handleBrowse(Context ctx) {
		try {
			String limitStr = ctx.queryParam("limit");

			Integer limit = defaultLimit;
			if (limitStr != null && !limitStr.isEmpty()) {
				try {
					limit = Integer.parseInt(limitStr);
				} catch (NumberFormatException e) {
					Map<String, String> error = new HashMap<>();
					error.put("error", "Invalid limit format. Must be an integer.");
					ctx.status(400).result(gson.toJson(error));
					return;
				}
			}

			logger.info("Browse request: limit={}", limit);

			List<SearchResult> results = searchService.getAllBooks(limit);

			Map<String, Object> response = new HashMap<>();
			response.put("total_results", results.size());
			response.put("returned_results", results.size());
			response.put("books", results);

			ctx.status(200).result(gson.toJson(response));
			logger.info("Returned {} books for browsing", results.size());

		} catch (Exception e) {
			Map<String, String> error = new HashMap<>();
			error.put("error", "Browse failed: " + e.getMessage());
			ctx.status(500).result(gson.toJson(error));
			logger.error("Browse failed", e);
		}
	}

	/**
	 * GET /stats
	 * Get search statistics
	 */
	private void handleStats(Context ctx) {
		try {
			SearchService.SearchStats stats = searchService.getStats();

			Map<String, Object> response = new HashMap<>();
			response.put("total_books", stats.totalBooks());
			response.put("unique_words", stats.uniqueWords());
			response.put("total_mappings", stats.totalMappings());
			response.put("index_size_mb", String.format("%.2f", stats.indexSizeMB()));
			response.put("index_loaded", stats.indexLoaded());

			ctx.status(200).result(gson.toJson(response));
			logger.debug("Retrieved search statistics");

		} catch (Exception e) {
			Map<String, String> error = new HashMap<>();
			error.put("error", "Failed to retrieve statistics: " + e.getMessage());
			ctx.status(500).result(gson.toJson(error));
			logger.error("Failed to get statistics", e);
		}
	}
}