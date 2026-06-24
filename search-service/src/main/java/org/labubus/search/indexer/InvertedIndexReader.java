package org.labubus.search.indexer;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public interface InvertedIndexReader {
	void load() throws IOException;

	Set<Integer> search(String word);

	Map<String, Set<Integer>> getIndex();

	boolean isLoaded();

	IndexStats getStats();

	record IndexStats(int uniqueWords, int totalMappings, double sizeInMB) {}
}