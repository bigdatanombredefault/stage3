package org.labubus.search.controller;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.labubus.search.model.SearchResponse;
import org.labubus.search.model.SearchResult;
import org.labubus.search.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class SearchController {
    private static final Logger logger = LoggerFactory.getLogger(SearchController.class);
    private final SearchService searchService;
    private final int defaultLimit;

    public SearchController(SearchService searchService, int defaultLimit) {
        this.searchService = searchService;
        this.defaultLimit = defaultLimit;
    }

    public void registerRoutes(Javalin app) {
        app.get("/health", this::handleHealth);
        app.get("/search", this::handleSearch);
        app.get("/books", this::handleBrowse);
        app.get("/stats", this::handleStats);
        logger.info("Search routes registered");
    }

    private void handleHealth(Context ctx) {
        try {
            SearchService.SearchStats stats = searchService.getStats();
            ctx.json(Map.of(
                    "service", "search-service",
                    "status", "running",
                    "timestamp", System.currentTimeMillis(),
                    "total_books", stats.totalBooks(),
                    "unique_words", stats.uniqueWords()
            ));
        } catch (Exception e) {
            logger.error("Error getting stats for health check", e);
            ctx.status(500).json(Map.of("status", "error", "message", "Could not retrieve stats."));
        }
    }

    private void handleSearch(Context ctx) {
        try {
            String query = ctx.queryParam("q");
            if (query == null || query.trim().isEmpty()) {
                ctx.status(400).json(Map.of("error", "Query parameter 'q' is required."));
                return;
            }

            Integer year = ctx.queryParamAsClass("year", Integer.class).getOrDefault(null);
            Integer limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(defaultLimit);

            List<SearchResult> results = searchService.search(
                    query,
                    ctx.queryParam("author"),
                    ctx.queryParam("language"),
                    year,
                    limit
            );

            SearchResponse response = new SearchResponse(query, results.size(), results.size(), results);
            ctx.status(200).json(response);
            logger.info("Returned {} search results for query '{}'", results.size(), query);

        } catch (Exception e) {
            logger.error("Search failed", e);
            ctx.status(500).json(Map.of("error", "Search failed: " + e.getMessage()));
        }
    }

    private void handleBrowse(Context ctx) {
        try {
            Integer limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(defaultLimit);
            List<SearchResult> results = searchService.getAllBooks(limit);
            ctx.status(200).json(Map.of(
                    "total_results", results.size(),
                    "returned_results", results.size(),
                    "books", results
            ));
            logger.info("Returned {} books for browsing", results.size());
        } catch (Exception e) {
            logger.error("Browse failed", e);
            ctx.status(500).json(Map.of("error", "Browse failed: " + e.getMessage()));
        }
    }

    private void handleStats(Context ctx) {
        try {
            SearchService.SearchStats stats = searchService.getStats();
            ctx.status(200).json(Map.of(
                    "total_books", stats.totalBooks(),
                    "unique_words", stats.uniqueWords()
            ));
        } catch (Exception e) {
            logger.error("Failed to get statistics", e);
            ctx.status(500).json(Map.of("error", "Failed to retrieve statistics: " + e.getMessage()));
        }
    }
}