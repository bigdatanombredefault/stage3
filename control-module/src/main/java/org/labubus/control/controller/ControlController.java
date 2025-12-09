package org.labubus.control.controller;

import com.google.gson.Gson;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.labubus.control.model.PipelineRequest;
import org.labubus.control.model.ServiceStatus;
import org.labubus.control.model.WorkflowStatus;
import org.labubus.control.service.PipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class ControlController {
	private static final Logger logger = LoggerFactory.getLogger(ControlController.class);
	private static final Gson gson = new Gson();
	private final PipelineService pipelineService;

	public ControlController(PipelineService pipelineService) {
		this.pipelineService = pipelineService;
	}

	/**
	 * Register all routes with the Javalin app
	 */
	public void registerRoutes(Javalin app) {
		app.get("/health", this::handleHealth);

		app.get("/services/status", this::handleServicesStatus);

		app.post("/pipeline/execute", this::handlePipelineExecute);

		app.get("/pipeline/status", this::handlePipelineStatus);

		logger.info("Control routes registered");
	}

	/**
	 * GET /health
	 * Health check endpoint
	 */
	private void handleHealth(Context ctx) {
		Map<String, Object> health = new HashMap<>();
		health.put("service", "control-module");
		health.put("status", "running");
		health.put("timestamp", System.currentTimeMillis());

		ctx.result(gson.toJson(health));
	}

	/**
	 * GET /services/status
	 * Check status of all services
	 */
	private void handleServicesStatus(Context ctx) {
		try {
			logger.info("Checking status of all services");

			Map<String, ServiceStatus> statuses = pipelineService.checkServices();

			Map<String, Object> response = new HashMap<>();
			response.put("timestamp", System.currentTimeMillis());
			response.put("services", statuses);

			boolean allHealthy = statuses.values().stream().allMatch(ServiceStatus::reachable);
			response.put("all_healthy", allHealthy);

			ctx.status(200).result(gson.toJson(response));
			logger.info("Service status check completed: all_healthy={}", allHealthy);

		} catch (Exception e) {
			Map<String, String> error = new HashMap<>();
			error.put("error", "Failed to check services: " + e.getMessage());
			ctx.status(500).result(gson.toJson(error));
			logger.error("Failed to check services", e);
		}
	}

	/**
	 * POST /pipeline/execute
	 * Execute complete pipeline
	 * Body: { "bookIds": [11, 1342, 84], "rebuildIndex": true }
	 */
	private void handlePipelineExecute(Context ctx) {
		try {
			String body = ctx.body();
			PipelineRequest request = gson.fromJson(body, PipelineRequest.class);

			if (request.bookIds() == null || request.bookIds().isEmpty()) {
				Map<String, String> error = new HashMap<>();
				error.put("error", "bookIds is required and must not be empty");
				ctx.status(400).result(gson.toJson(error));
				return;
			}

			logger.info("Executing pipeline for {} books, rebuildIndex={}",
					request.bookIds().size(), request.rebuildIndex());

			new Thread(() -> {
				try {
					pipelineService.executePipeline(request.bookIds(), request.rebuildIndex());
				} catch (Exception e) {
					logger.error("Pipeline execution failed", e);
				}
			}).start();

			Map<String, Object> response = new HashMap<>();
			response.put("status", "started");
			response.put("message", "Pipeline execution started");
			response.put("book_count", request.bookIds().size());
			response.put("rebuild_index", request.rebuildIndex());

			ctx.status(202).result(gson.toJson(response));

		} catch (Exception e) {
			Map<String, String> error = new HashMap<>();
			error.put("error", "Failed to execute pipeline: " + e.getMessage());
			ctx.status(500).result(gson.toJson(error));
			logger.error("Failed to execute pipeline", e);
		}
	}

	/**
	 * GET /pipeline/status
	 * Get current pipeline status
	 */
	private void handlePipelineStatus(Context ctx) {
		try {
			WorkflowStatus status = pipelineService.getCurrentStatus();

			if (status == null) {
				Map<String, String> response = new HashMap<>();
				response.put("status", "idle");
				response.put("message", "No pipeline is currently running");
				ctx.status(200).result(gson.toJson(response));
			} else {
				ctx.status(200).result(gson.toJson(status));
			}

		} catch (Exception e) {
			Map<String, String> error = new HashMap<>();
			error.put("error", "Failed to get pipeline status: " + e.getMessage());
			ctx.status(500).result(gson.toJson(error));
			logger.error("Failed to get pipeline status", e);
		}
	}
}