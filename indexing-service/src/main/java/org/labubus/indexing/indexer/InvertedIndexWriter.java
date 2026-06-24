package org.labubus.indexing.indexer;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public interface InvertedIndexWriter {
	void addWord(String word, int bookId);

	void save() throws IOException;

	void load() throws IOException;

	Map<String, Set<Integer>> getIndex();

	double getSizeInMB();

	void clear();
}