package org.labubus.control.service;

import com.google.gson.JsonObject;
import org.labubus.control.client.ServiceClient;
import org.labubus.control.model.PipelineResponse;
import org.labubus.control.model.ServiceStatus;
import org.labubus.control.model.WorkflowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class PipelineService {
	private static final Logger logger = LoggerFactory.getLogger(PipelineService.class);

	private final ServiceClient client;
	private final String ingestionUrl;
	private final String indexingUrl;
	private final String searchUrl;
	private final int checkInterval;
	private final int maxRetries;

	private WorkflowStatus currentStatus;

	public PipelineService(ServiceClient client, String ingestionUrl, String indexingUrl, String searchUrl,
						   int checkInterval, int maxRetries) {
		this.client = client;
		this.ingestionUrl = ingestionUrl;
		this.indexingUrl = indexingUrl;
		this.searchUrl = searchUrl;
		this.checkInterval = checkInterval;
		this.maxRetries = maxRetries;
	}

	/**
	 * Check health of all services
	 */
	public Map<String, ServiceStatus> checkServices() {
		Map<String, ServiceStatus> statuses = new HashMap<>();

		try {
			String healthUrl = ingestionUrl + "/health";
			if (client.isReachable(healthUrl)) {
				String response = client.get(healthUrl);
				statuses.put("ingestion", ServiceStatus.reachable("ingestion-service"));
			} else {
				statuses.put("ingestion", ServiceStatus.unreachable("ingestion-service", "Service not responding"));
			}
		} catch (Exception e) {
			statuses.put("ingestion", ServiceStatus.unreachable("ingestion-service", e.getMessage()));
		}

		try {
			String healthUrl = indexingUrl + "/health";
			if (client.isReachable(healthUrl)) {
				String response = client.get(healthUrl);
				statuses.put("indexing", ServiceStatus.reachable("indexing-service"));
			} else {
				statuses.put("indexing", ServiceStatus.unreachable("indexing-service", "Service not responding"));
			}
		} catch (Exception e) {
			statuses.put("indexing", ServiceStatus.unreachable("indexing-service", e.getMessage()));
		}

		try {
			String healthUrl = searchUrl + "/health";
			if (client.isReachable(healthUrl)) {
				String response = client.get(healthUrl);
				statuses.put("search", ServiceStatus.reachable("search-service"));
			} else {
				statuses.put("search", ServiceStatus.unreachable("search-service", "Service not responding"));
			}
		} catch (Exception e) {
			statuses.put("search", ServiceStatus.unreachable("search-service", e.getMessage()));
		}

		return statuses;
	}

	/**
	 * Execute complete pipeline: ingest books → index them → make searchable
	 */
	public PipelineResponse executePipeline(List<Integer> bookIds, boolean rebuildIndex) {
		long startTime = System.currentTimeMillis();
		List<String> errors = new ArrayList<>();
		Map<String, Object> details = new HashMap<>();

		logger.info("Starting pipeline for {} books", bookIds.size());

		updateStatus("verification", "running", "Checking services...", 0, bookIds.size());
		Map<String, ServiceStatus> serviceStatuses = checkServices();
		details.put("service_statuses", serviceStatuses);

		boolean allServicesHealthy = serviceStatuses.values().stream().allMatch(ServiceStatus::reachable);
		if (!allServicesHealthy) {
			String errorMsg = "Not all services are healthy. Cannot proceed with pipeline.";
			errors.add(errorMsg);
			updateStatus("verification", "failed", errorMsg, 0, bookIds.size());
			long duration = System.currentTimeMillis() - startTime;
			return new PipelineResponse("failed", bookIds.size(), 0, 0, 0, duration, details, errors);
		}

		updateStatus("verification", "completed", "All services healthy", 0, bookIds.size());

		updateStatus("ingestion", "running", "Downloading books...", 0, bookIds.size());
		int successfulIngestions = 0;
		int failedIngestions = 0;

		for (int i = 0; i < bookIds.size(); i++) {
			int bookId = bookIds.get(i);
			try {
				String url = ingestionUrl + "/ingest/" + bookId;
				String response = client.post(url);
				JsonObject json = client.parseJson(response);

				String status = json.get("status").getAsString();
				if ("downloaded".equals(status) || "already_exists".equals(status)) {
					successfulIngestions++;
					logger.info("Ingested book {} ({}/{})", bookId, i + 1, bookIds.size());
				} else {
					failedIngestions++;
					errors.add("Failed to ingest book " + bookId + ": " + status);
				}

			} catch (Exception e) {
				failedIngestions++;
				String errorMsg = "Failed to ingest book " + bookId + ": " + e.getMessage();
				errors.add(errorMsg);
				logger.error(errorMsg);
			}

			updateStatus("ingestion", "running", "Downloading books...", i + 1, bookIds.size());
		}

		details.put("successful_ingestions", successfulIngestions);
		details.put("failed_ingestions", failedIngestions);
		updateStatus("ingestion", "completed",
				String.format("Downloaded %d/%d books", successfulIngestions, bookIds.size()),
				bookIds.size(), bookIds.size());

		updateStatus("indexing", "running", "Building index...", 0, 1);
		int booksIndexed = 0;

		try {
			if (rebuildIndex) {
				String url = indexingUrl + "/index/rebuild";
				String response = client.post(url);
				JsonObject json = client.parseJson(response);

				if (json.has("books_indexed")) {
					booksIndexed = json.get("books_indexed").getAsInt();
					logger.info("Rebuilt index: {} books indexed", booksIndexed);
				}
			} else {
				for (int bookId : bookIds) {
					try {
						String url = indexingUrl + "/index/update/" + bookId;
						String response = client.post(url);
						booksIndexed++;
						logger.info("Indexed book {}", bookId);
					} catch (Exception e) {
						String errorMsg = "Failed to index book " + bookId + ": " + e.getMessage();
						errors.add(errorMsg);
						logger.error(errorMsg);
					}
				}
			}

			details.put("books_indexed", booksIndexed);
			updateStatus("indexing", "completed", "Index built successfully", 1, 1);

		} catch (Exception e) {
			String errorMsg = "Indexing failed: " + e.getMessage();
			errors.add(errorMsg);
			logger.error(errorMsg);
			updateStatus("indexing", "failed", errorMsg, 0, 1);
		}

		updateStatus("verification", "running", "Verifying search...", 0, 1);
		try {
			String statsUrl = searchUrl + "/stats";
			String response = client.get(statsUrl);
			JsonObject json = client.parseJson(response);

			int totalBooks = json.get("total_books").getAsInt();
			boolean indexLoaded = json.get("index_loaded").getAsBoolean();

			details.put("searchable_books", totalBooks);
			details.put("search_index_loaded", indexLoaded);

			if (indexLoaded) {
				updateStatus("verification", "completed",
						String.format("Search ready with %d books", totalBooks), 1, 1);
			} else {
				String errorMsg = "Search index not loaded";
				errors.add(errorMsg);
				updateStatus("verification", "failed", errorMsg, 1, 1);
			}

		} catch (Exception e) {
			String errorMsg = "Search verification failed: " + e.getMessage();
			errors.add(errorMsg);
			logger.error(errorMsg);
			updateStatus("verification", "failed", errorMsg, 0, 1);
		}

		long duration = System.currentTimeMillis() - startTime;
		String finalStatus = errors.isEmpty() ? "completed" : "completed_with_errors";

		if (successfulIngestions == 0) {
			finalStatus = "failed";
		}

		updateStatus("completed", finalStatus,
				String.format("Pipeline finished: %d books ingested, %d indexed",
						successfulIngestions, booksIndexed),
				bookIds.size(), bookIds.size());

		logger.info("Pipeline completed in {}ms: {} books ingested, {} indexed",
				duration, successfulIngestions, booksIndexed);

		return new PipelineResponse(
				finalStatus,
				bookIds.size(),
				successfulIngestions,
				failedIngestions,
				booksIndexed,
				duration,
				details,
				errors
		);
	}

	/**
	 * Get current workflow status
	 */
	public WorkflowStatus getCurrentStatus() {
		return currentStatus;
	}

	/**
	 * Update current workflow status
	 */
	private void updateStatus(String stage, String status, String message, int progress, int total) {
		currentStatus = new WorkflowStatus(stage, status, message, progress, total);
		logger.info("Pipeline status: {} - {} - {} ({}/{})", stage, status, message, progress, total);
	}
}