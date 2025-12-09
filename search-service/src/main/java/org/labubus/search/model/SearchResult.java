package org.labubus.search.model;

import org.labubus.core.model.BookMetadata;

public record SearchResult(
		int bookId,
		String title,
		String author,
		String language,
		Integer year,
		int score
) {
	public static SearchResult fromMetadata(BookMetadata metadata, int score) {
		return new SearchResult(
				metadata.bookId(),
				metadata.title(),
				metadata.author(),
				metadata.language(),
				metadata.year(),
				score
		);
	}
}