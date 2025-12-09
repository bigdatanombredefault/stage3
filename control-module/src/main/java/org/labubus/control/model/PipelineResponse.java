package org.labubus.control.model;

import java.util.List;
import java.util.Map;

public record PipelineResponse(
		String status,
		int totalBooks,
		int successfulIngestions,
		int failedIngestions,
		int booksIndexed,
		long durationMs,
		Map<String, Object> details,
		List<String> errors
) {}