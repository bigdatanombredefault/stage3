package org.labubus.search.model;

import java.util.List;

public record SearchResponse(
		String query,
		int totalResults,
		int returnedResults,
		List<SearchResult> results
) {}