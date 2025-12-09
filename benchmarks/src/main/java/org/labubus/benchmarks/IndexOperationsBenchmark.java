package org.labubus.benchmarks;

import org.labubus.indexing.indexer.InvertedIndexWriter;
import org.labubus.indexing.indexer.JsonIndexWriter;
import org.labubus.indexing.service.InvertedIndexBuilder;
import org.labubus.indexing.storage.DatalakeReader;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks for inverted index operations
 * Tests: add word, search word, serialize, deserialize
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class IndexOperationsBenchmark {

	private static final String DATALAKE_PATH = "../datalake";
	private static final String BENCHMARK_INDEX_DIR = "./benchmark-indexes";

	private DatalakeReader datalakeReader;
	private List<Integer> availableBooks;
	private InvertedIndexWriter indexWriter;
	private InvertedIndexBuilder indexBuilder;

	private Map<String, Set<Integer>> preBuiltIndex;

	private String testWord;
	private int testBookId;

	@Param({"10", "50", "100", "300"})
	private int indexSize;

	@Setup(Level.Trial)
	public void setup() throws IOException {
		System.out.println("=== Index Operations Benchmark Setup (indexSize=" + indexSize + ") ===");

		datalakeReader = new DatalakeReader(DATALAKE_PATH);
		availableBooks = datalakeReader.getDownloadedBooks();

		if (availableBooks.size() < indexSize + 1) {
			throw new RuntimeException("Need at least " + (indexSize + 1) + " books, found " + availableBooks.size());
		}

		Files.createDirectories(Paths.get(BENCHMARK_INDEX_DIR));

		String indexPath = BENCHMARK_INDEX_DIR + "/test_index_" + indexSize + ".json";
		indexWriter = new JsonIndexWriter(BENCHMARK_INDEX_DIR, "test_index_" + indexSize + ".json");

		Set<String> stopWords = new HashSet<>();
		indexBuilder = new InvertedIndexBuilder(indexWriter, 3, 50, stopWords);

		System.out.println("Building index with " + indexSize + " books...");
		buildIndex(indexSize);

		indexWriter.save();

		preBuiltIndex = new HashMap<>(indexWriter.getIndex());

		testWord = "adventure";
		testBookId = 99999;

		System.out.println("Index ready: " + preBuiltIndex.size() + " unique words");
	}

	@TearDown(Level.Trial)
	public void tearDown() throws IOException {
		try {
			Path indexDir = Paths.get(BENCHMARK_INDEX_DIR);
			if (Files.exists(indexDir)) {
				Files.walk(indexDir)
						.sorted((a, b) -> -a.compareTo(b))
						.forEach(path -> {
							try {
								Files.deleteIfExists(path);
							} catch (IOException e) {
							}
						});
			}
		} catch (Exception e) {
			System.err.println("Failed to cleanup: " + e.getMessage());
		}
	}

	/**
	 * Benchmark: Add a single word to index
	 */
	@Benchmark
	public void addWordToIndex(Blackhole blackhole) {
		InvertedIndexWriter tempWriter = new JsonIndexWriter(BENCHMARK_INDEX_DIR, "temp.json");

		for (Map.Entry<String, Set<Integer>> entry : preBuiltIndex.entrySet()) {
			for (Integer bookId : entry.getValue()) {
				tempWriter.addWord(entry.getKey(), bookId);
			}
		}

		tempWriter.addWord("newword", testBookId);

		blackhole.consume(tempWriter.getIndex().size());
	}

	/**
	 * Benchmark: Search for a word in index (lookup)
	 */
	@Benchmark
	public void searchWordInIndex(Blackhole blackhole) {
		Set<Integer> results = preBuiltIndex.getOrDefault(testWord.toLowerCase(), Collections.emptySet());
		blackhole.consume(results);
	}

	/**
	 * Benchmark: Search for multiple common words
	 */
	@Benchmark
	public void searchMultipleWords(Blackhole blackhole) {
		String[] words = {"the", "and", "of", "to", "a"};
		Set<Integer> allResults = new HashSet<>();

		for (String word : words) {
			Set<Integer> results = preBuiltIndex.getOrDefault(word, Collections.emptySet());
			allResults.addAll(results);
		}

		blackhole.consume(allResults);
	}

	/**
	 * Benchmark: Serialize entire index to JSON
	 * Tests write performance
	 */
	@Benchmark
	public void serializeIndexToJson(Blackhole blackhole) throws IOException {
		InvertedIndexWriter tempWriter = new JsonIndexWriter(
				BENCHMARK_INDEX_DIR,
				"serialize_test_" + System.nanoTime() + ".json"
		);

		for (Map.Entry<String, Set<Integer>> entry : preBuiltIndex.entrySet()) {
			for (Integer bookId : entry.getValue()) {
				tempWriter.addWord(entry.getKey(), bookId);
			}
		}

		tempWriter.save();
		blackhole.consume(tempWriter.getSizeInMB());

		try {
			Path file = Paths.get(BENCHMARK_INDEX_DIR,
					"serialize_test_" + System.nanoTime() + ".json");
			Files.deleteIfExists(file);
		} catch (Exception e) {
		}
	}

	/**
	 * Benchmark: Deserialize index from JSON
	 * Tests read performance
	 */
	@Benchmark
	public void deserializeIndexFromJson(Blackhole blackhole) throws IOException {
		InvertedIndexWriter tempWriter = new JsonIndexWriter(
				BENCHMARK_INDEX_DIR,
				"test_index_" + indexSize + ".json"
		);

		tempWriter.load();
		blackhole.consume(tempWriter.getIndex().size());
	}

	/**
	 * Benchmark: Get index size in MB
	 */
	@Benchmark
	public void getIndexSize(Blackhole blackhole) {
		double size = indexWriter.getSizeInMB();
		blackhole.consume(size);
	}

	/**
	 * Benchmark: Count unique words in index
	 */
	@Benchmark
	public void countUniqueWords(Blackhole blackhole) {
		int count = preBuiltIndex.size();
		blackhole.consume(count);
	}

	/**
	 * Benchmark: Get all book IDs for a common word
	 */
	@Benchmark
	public void getAllBookIdsForCommonWord(Blackhole blackhole) {
		Set<Integer> bookIds = preBuiltIndex.getOrDefault("the", Collections.emptySet());
		int count = bookIds.size();
		blackhole.consume(count);
	}

	private void buildIndex(int count) throws IOException {
		for (int i = 0; i < count; i++) {
			int bookId = availableBooks.get(i);
			String body = datalakeReader.readBookBody(bookId);
			indexBuilder.indexBook(bookId, body);
		}

		System.out.println("Index built with " + indexWriter.getIndex().size() + " unique words");
	}
}