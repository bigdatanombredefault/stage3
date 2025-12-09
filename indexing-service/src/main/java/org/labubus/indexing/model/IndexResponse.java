package org.labubus.indexing.model;

public record IndexResponse(
		int bookId,
		String index,
		String message
) {
	public static IndexResponse updated(int bookId) {
		return new IndexResponse(bookId, "updated", null);
	}

	public static IndexResponse failed(int bookId, String message) {
		return new IndexResponse(bookId, "failed", message);
	}
}