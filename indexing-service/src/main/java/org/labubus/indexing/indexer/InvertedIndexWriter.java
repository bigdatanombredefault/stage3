package org.labubus.indexing.indexer;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public interface InvertedIndexWriter {
	/**
	 * Add a word and its associated book to the index
	 */
	void addWord(String word, int bookId);

	/**
	 * Save the index to storage
	 */
	void save() throws IOException;

	/**
	 * Load the index from storage
	 */
	void load() throws IOException;

	/**
	 * Get the complete index
	 */
	Map<String, Set<Integer>> getIndex();

	/**
	 * Get size of the index in MB
	 */
	double getSizeInMB();

	/**
	 * Clear the index
	 */
	void clear();
}