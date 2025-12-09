package org.labubus.indexing.model;

import org.jetbrains.annotations.NotNull;

public record BookMetadata(
		int bookId,
		String title,
		String author,
		String language,
		Integer year,
		String path
) {
	@NotNull
	@Override
	public String toString() {
		return String.format("BookMetadata{id=%d, title='%s', author='%s', lang='%s', year=%d}",
				bookId, title, author, language, year);
	}
}