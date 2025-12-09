package org.labubus.search.indexer;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public interface InvertedIndexReader {
	/**
	 * Load the index from storage
	 */
	void load() throws IOException;

	/**
	 * Search for a word in the index
	 * @return Set of book IDs containing the word, empty if word not found
	 */
	Set<Integer> search(String word);

	/**
	 * Get the complete index
	 */
	Map<String, Set<Integer>> getIndex();

	/**
	 * Check if index is loaded
	 */
	boolean isLoaded();

	/**
	 * Get index statistics
	 */
	IndexStats getStats();

	record IndexStats(int uniqueWords, int totalMappings, double sizeInMB) {}
}