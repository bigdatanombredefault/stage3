package org.labubus.ingestion.model;

public record IngestionStatusResponse(
		int bookId,
		String status,
		String path,
		String message
) {
	public static IngestionStatusResponse available(int bookId, String path) {
		return new IngestionStatusResponse(bookId, "available", path, null);
	}

	public static IngestionStatusResponse notFound(int bookId) {
		return new IngestionStatusResponse(bookId, "not_found", null, null);
	}

	public static IngestionStatusResponse status(int bookId, String status, String path, String message) {
		return new IngestionStatusResponse(bookId, status, path, message);
	}
}