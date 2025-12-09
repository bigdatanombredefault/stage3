package org.labubus.indexing.model;

public record IndexStatusResponse(
		int booksIndexed,
		String lastUpdate,
		double indexSizeMB
) {}