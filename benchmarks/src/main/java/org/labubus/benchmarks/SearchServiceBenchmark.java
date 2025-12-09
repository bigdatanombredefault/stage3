package org.labubus.benchmarks;

import org.labubus.indexing.indexer.InvertedIndexWriter;
import org.labubus.indexing.indexer.JsonIndexWriter;
import org.labubus.indexing.model.BookMetadata;
import org.labubus.indexing.repository.SqliteMetadataRepository;
import org.labubus.indexing.service.InvertedIndexBuilder;
import org.labubus.indexing.service.MetadataExtractor;
import org.labubus.indexing.storage.DatalakeReader;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Benchmarks for search service operations
 * Tests: keyword search, filtering, ranking, full search pipeline
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SearchServiceBenchmark {

	private static final String DATALAKE_PATH = "../datalake";
	private static final String BENCHMARK_DIR = "./benchmark-search";

	private DatalakeReader datalakeReader;
	private MetadataExtractor metadataExtractor;
	private SqliteMetadataRepository repository;
	private Map<String, Set<Integer>> invertedIndex;
	private List<Integer> availableBooks;
	private List<BookMetadata> allMetadata;

	@Param({"50", "100", "300"})
	private int datasetSize;

	@Setup(Level.Trial)
	public void setup() throws IOException, SQLException {
		System.out.println("=== Search Service Benchmark Setup (datasetSize=" + datasetSize + ") ===");

		datalakeReader = new DatalakeReader(DATALAKE_PATH);
		metadataExtractor = new MetadataExtractor();
		availableBooks = datalakeReader.getDownloadedBooks();

		if (availableBooks.size() < datasetSize) {
			throw new RuntimeException("Need at least " + datasetSize + " books, found " + availableBooks.size());
		}

		Files.createDirectories(Paths.get(BENCHMARK_DIR));

		String dbPath = BENCHMARK_DIR + "/search_db_" + datasetSize + ".sqlite";
		repository = new SqliteMetadataRepository(dbPath);

		String indexDir = BENCHMARK_DIR;
		InvertedIndexWriter indexWriter = new JsonIndexWriter(indexDir, "search_index_" + datasetSize + ".json");
		InvertedIndexBuilder indexBuilder = new InvertedIndexBuilder(indexWriter, 3, 50, new HashSet<>());

		System.out.println("Building dataset with " + datasetSize + " books...");
		allMetadata = new ArrayList<>();

		for (int i = 0; i < datasetSize; i++) {
			int bookId = availableBooks.get(i);

			String header = datalakeReader.readBookHeader(bookId);
			BookMetadata metadata = metadataExtractor.extractMetadata(bookId, header, "datalake/book_" + bookId);
			repository.save(metadata);
			allMetadata.add(metadata);

			String body = datalakeReader.readBookBody(bookId);
			indexBuilder.indexBook(bookId, body);
		}

		indexWriter.save();
		invertedIndex = new HashMap<>(indexWriter.getIndex());

		System.out.println("Dataset ready: " + datasetSize + " books, " + invertedIndex.size() + " unique words");
	}

	@TearDown(Level.Trial)
	public void tearDown() throws SQLException, IOException {
		if (repository != null) {
			repository.close();
		}

		try {
			Files.walk(Paths.get(BENCHMARK_DIR))
					.sorted((a, b) -> -a.compareTo(b))
					.forEach(path -> {
						try {
							Files.deleteIfExists(path);
						} catch (IOException e) {
						}
					});
		} catch (Exception e) {
			System.err.println("Failed to cleanup: " + e.getMessage());
		}
	}

	/**
	 * Benchmark: Search by single keyword
	 */
	@Benchmark
	public void searchBySingleKeyword(Blackhole blackhole) {
		Set<Integer> bookIds = searchIndex("adventure");
		blackhole.consume(bookIds);
	}

	/**
	 * Benchmark: Search by multiple keywords
	 */
	@Benchmark
	public void searchByMultipleKeywords(Blackhole blackhole) {
		Set<Integer> bookIds = searchIndex("adventure mystery treasure");
		blackhole.consume(bookIds);
	}

	/**
	 * Benchmark: Filter books by author
	 */
	@Benchmark
	public void filterByAuthor(Blackhole blackhole) {
		List<BookMetadata> filtered = allMetadata.stream()
				.filter(book -> book.author() != null && book.author().toLowerCase().contains("a"))
				.collect(Collectors.toList());
		blackhole.consume(filtered);
	}

	/**
	 * Benchmark: Filter books by language
	 */
	@Benchmark
	public void filterByLanguage(Blackhole blackhole) {
		List<BookMetadata> filtered = allMetadata.stream()
				.filter(book -> "en".equalsIgnoreCase(book.language()))
				.collect(Collectors.toList());
		blackhole.consume(filtered);
	}

	/**
	 * Benchmark: Filter books by year
	 */
	@Benchmark
	public void filterByYear(Blackhole blackhole) {
		List<BookMetadata> filtered = allMetadata.stream()
				.filter(book -> book.year() != null && book.year() >= 1800 && book.year() <= 1900)
				.collect(Collectors.toList());
		blackhole.consume(filtered);
	}

	/**
	 * Benchmark: Combined filters (author + language)
	 */
	@Benchmark
	public void filterByAuthorAndLanguage(Blackhole blackhole) {
		List<BookMetadata> filtered = allMetadata.stream()
				.filter(book -> book.author() != null && book.author().toLowerCase().contains("a"))
				.filter(book -> "en".equalsIgnoreCase(book.language()))
				.collect(Collectors.toList());
		blackhole.consume(filtered);
	}

	/**
	 * Benchmark: Combined filters (author + language + year)
	 */
	@Benchmark
	public void filterByAllCriteria(Blackhole blackhole) {
		List<BookMetadata> filtered = allMetadata.stream()
				.filter(book -> book.author() != null && book.author().toLowerCase().contains("a"))
				.filter(book -> "en".equalsIgnoreCase(book.language()))
				.filter(book -> book.year() != null && book.year() >= 1800 && book.year() <= 1900)
				.collect(Collectors.toList());
		blackhole.consume(filtered);
	}

	/**
	 * Benchmark: Rank search results by relevance
	 */
	@Benchmark
	public void rankSearchResults(Blackhole blackhole) {
		String query = "adventure";
		Set<Integer> bookIds = searchIndex(query);

		List<BookMetadata> matchingBooks = allMetadata.stream()
				.filter(book -> bookIds.contains(book.bookId()))
				.collect(Collectors.toList());

		List<ScoredResult> ranked = matchingBooks.stream()
				.map(book -> new ScoredResult(book, calculateScore(book, query)))
				.sorted((a, b) -> Integer.compare(b.score, a.score))
				.collect(Collectors.toList());

		blackhole.consume(ranked);
	}

	/**
	 * Benchmark: Full search pipeline (index lookup + DB query + filter + rank)
	 */
	@Benchmark
	public void fullSearchPipeline(Blackhole blackhole) throws SQLException {
		String query = "adventure";
		String authorFilter = "a";
		String languageFilter = "en";

		Set<Integer> bookIds = searchIndex(query);

		List<BookMetadata> matchingBooks = new ArrayList<>();
		for (Integer bookId : bookIds) {
			Optional<BookMetadata> metadata = repository.findById(bookId);
			metadata.ifPresent(matchingBooks::add);
		}

		List<BookMetadata> filtered = matchingBooks.stream()
				.filter(book -> book.author() != null && book.author().toLowerCase().contains(authorFilter))
				.filter(book -> languageFilter.equalsIgnoreCase(book.language()))
				.collect(Collectors.toList());

		List<ScoredResult> ranked = filtered.stream()
				.map(book -> new ScoredResult(book, calculateScore(book, query)))
				.sorted((a, b) -> Integer.compare(b.score, a.score))
				.limit(20)
				.collect(Collectors.toList());

		blackhole.consume(ranked);
	}

	/**
	 * Benchmark: Retrieve books by IDs from database
	 */
	@Benchmark
	public void retrieveBooksByIds(Blackhole blackhole) throws SQLException {
		Set<Integer> bookIds = searchIndex("adventure");
		List<BookMetadata> books = new ArrayList<>();

		for (Integer bookId : bookIds) {
			Optional<BookMetadata> metadata = repository.findById(bookId);
			metadata.ifPresent(books::add);
		}

		blackhole.consume(books);
	}

	private Set<Integer> searchIndex(String query) {
		String[] words = query.toLowerCase().trim().split("\\s+");
		Set<Integer> allResults = new HashSet<>();

		for (String word : words) {
			Set<Integer> bookIds = invertedIndex.getOrDefault(word, Collections.emptySet());
			allResults.addAll(bookIds);
		}

		return allResults;
	}

	private int calculateScore(BookMetadata book, String query) {
		int score = 0;
		String titleLower = book.title().toLowerCase();
		String authorLower = book.author().toLowerCase();
		String[] queryWords = query.toLowerCase().split("\\s+");

		for (String word : queryWords) {
			if (titleLower.contains(word)) {
				score += 10;
			}
			if (authorLower.contains(word)) {
				score += 5;
			}
			Set<Integer> bookIds = invertedIndex.getOrDefault(word, Collections.emptySet());
			if (bookIds.contains(book.bookId())) {
				score += 1;
			}
		}

		return score;
	}

	private static class ScoredResult {
		BookMetadata book;
		int score;

		ScoredResult(BookMetadata book, int score) {
			this.book = book;
			this.score = score;
		}
	}
}