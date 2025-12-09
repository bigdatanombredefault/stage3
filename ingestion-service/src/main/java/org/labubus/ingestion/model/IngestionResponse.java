package org.labubus.ingestion.model;

public record IngestionResponse (
		int bookId,
		String status,
		String path,
		String message
) {
	public static IngestionResponse success(int bookId, String path) {
		return new IngestionResponse(bookId, "downloaded", path, null);
	}

	public static IngestionResponse alreadyExists(int bookId, String path) {
		return new IngestionResponse(bookId, "already_exists", path, null);
	}

	public static IngestionResponse failure(int bookId, String message) {
		return new IngestionResponse(bookId, "failed", null, message);
	}
}