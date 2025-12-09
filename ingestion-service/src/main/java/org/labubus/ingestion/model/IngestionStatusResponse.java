package org.labubus.ingestion.model;

public record IngestionStatusResponse(
		int bookId,
		String status,
		String path
) {
	public static IngestionStatusResponse available(int bookId, String path) {
		return new IngestionStatusResponse(bookId, "available", path);
	}

	public static IngestionStatusResponse notFound(int bookId) {
		return new IngestionStatusResponse(bookId, "not_found", null);
	}
}