package org.labubus.search.indexer;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class JsonIndexReader implements InvertedIndexReader {
	private static final Logger logger = LoggerFactory.getLogger(JsonIndexReader.class);
	private final Map<String, Set<Integer>> index;
	private final String datamartPath;
	private final String indexFilename;
	private final Gson gson;
	private boolean loaded;

	public JsonIndexReader(String datamartPath, String indexFilename) {
		this.datamartPath = datamartPath;
		this.indexFilename = indexFilename;
		this.index = new HashMap<>();
		this.gson = new Gson();
		this.loaded = false;
	}

	@Override
	public void load() throws IOException {
		Path indexPath = Paths.get(datamartPath, indexFilename);

		if (!Files.exists(indexPath)) {
			throw new IOException("Index file not found: " + indexPath);
		}

		String json = Files.readString(indexPath);
		Type type = new TypeToken<Map<String, Set<Integer>>>(){}.getType();
		Map<String, Set<Integer>> loadedIndex = gson.fromJson(json, type);

		if (loadedIndex != null) {
			index.clear();
			index.putAll(loadedIndex);
			loaded = true;
			logger.info("Loaded inverted index from {} ({} unique words)",
					indexPath, index.size());
		} else {
			throw new IOException("Failed to parse index file");
		}
	}

	@Override
	public Set<Integer> search(String word) {
		if (!loaded) {
			logger.warn("Index not loaded, returning empty results");
			return Collections.emptySet();
		}

		word = word.toLowerCase().trim();
		return index.getOrDefault(word, Collections.emptySet());
	}

	@Override
	public Map<String, Set<Integer>> getIndex() {
		return Collections.unmodifiableMap(index);
	}

	@Override
	public boolean isLoaded() {
		return loaded;
	}

	@Override
	public IndexStats getStats() {
		if (!loaded) {
			return new IndexStats(0, 0, 0.0);
		}

		int uniqueWords = index.size();
		int totalMappings = index.values().stream()
				.mapToInt(Set::size)
				.sum();

		double sizeInMB = 0.0;
		Path indexPath = Paths.get(datamartPath, indexFilename);
		try {
			if (Files.exists(indexPath)) {
				long bytes = Files.size(indexPath);
				sizeInMB = bytes / (1024.0 * 1024.0);
			}
		} catch (IOException e) {
			logger.warn("Failed to get index file size", e);
		}

		return new IndexStats(uniqueWords, totalMappings, sizeInMB);
	}
}