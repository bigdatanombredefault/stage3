package org.labubus.indexing.indexer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class JsonIndexWriter implements InvertedIndexWriter {
	private static final Logger logger = LoggerFactory.getLogger(JsonIndexWriter.class);
	private final Map<String, Set<Integer>> index;
	private final String datamartPath;
	private final String indexFilename;
	private final Gson gson;

	public JsonIndexWriter(String datamartPath, String indexFilename) {
		this.datamartPath = datamartPath;
		this.indexFilename = indexFilename;
		this.index = new HashMap<>();
		this.gson = new GsonBuilder().setPrettyPrinting().create();

		try {
			Files.createDirectories(Paths.get(datamartPath));
			logger.info("JSON index writer initialized at: {}", datamartPath);
		} catch (IOException e) {
			logger.error("Failed to create datamart directory", e);
			throw new RuntimeException("Failed to create datamart directory", e);
		}
	}

	@Override
	public void addWord(String word, int bookId) {
		word = word.toLowerCase().trim();
		index.computeIfAbsent(word, k -> new TreeSet<>()).add(bookId);
	}

	@Override
	public void save() throws IOException {
		Path indexPath = Paths.get(datamartPath, indexFilename);

		Map<String, Set<Integer>> sortedIndex = new TreeMap<>(index);

		String json = gson.toJson(sortedIndex);
		Files.writeString(indexPath, json);

		logger.info("Saved inverted index to {} ({} unique words, {} MB)",
				indexPath, index.size(), getSizeInMB());
	}

	@Override
	public void load() throws IOException {
		Path indexPath = Paths.get(datamartPath, indexFilename);

		if (!Files.exists(indexPath)) {
			logger.warn("Index file does not exist: {}", indexPath);
			return;
		}

		String json = Files.readString(indexPath);
		Type type = new TypeToken<Map<String, Set<Integer>>>(){}.getType();
		Map<String, Set<Integer>> loadedIndex = gson.fromJson(json, type);

		if (loadedIndex != null) {
			index.clear();
			index.putAll(loadedIndex);
			logger.info("Loaded inverted index from {} ({} unique words)",
					indexPath, index.size());
		}
	}

	@Override
	public Map<String, Set<Integer>> getIndex() {
		return Collections.unmodifiableMap(index);
	}

	@Override
	public double getSizeInMB() {
		Path indexPath = Paths.get(datamartPath, indexFilename);
		try {
			if (Files.exists(indexPath)) {
				long bytes = Files.size(indexPath);
				return bytes / (1024.0 * 1024.0);
			}
		} catch (IOException e) {
			logger.warn("Failed to get index file size", e);
		}
		return 0.0;
	}

	@Override
	public void clear() {
		index.clear();
		logger.info("Cleared inverted index");
	}
}