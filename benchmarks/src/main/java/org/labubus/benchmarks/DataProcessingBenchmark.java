package org.labubus.benchmarks;

import org.labubus.indexing.service.InvertedIndexBuilder;
import org.labubus.indexing.service.MetadataExtractor;
import org.labubus.indexing.storage.DatalakeReader;
import org.labubus.indexing.model.BookMetadata;
import org.labubus.indexing.indexer.InvertedIndexWriter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Benchmarks for pure data processing operations (no I/O)
 * Tests: tokenization, metadata extraction, in-memory index building
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DataProcessingBenchmark {

	private static final String DATALAKE_PATH = "../datalake";
	private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z]+");

	private DatalakeReader datalakeReader;
	private MetadataExtractor metadataExtractor;
	private List<Integer> availableBooks;

	private String sampleHeader;
	private String sampleBody;
	private int sampleBookId;

	@Param({"10", "50", "100", "300"})
	private int bookCount;

	@Setup(Level.Trial)
	public void setup() throws IOException {
		datalakeReader = new DatalakeReader(DATALAKE_PATH);
		metadataExtractor = new MetadataExtractor();

		availableBooks = datalakeReader.getDownloadedBooks();

		if (availableBooks.isEmpty()) {
			throw new RuntimeException("No books found in datalake at: " + DATALAKE_PATH);
		}

		System.out.println("Found " + availableBooks.size() + " books in datalake");
		System.out.println("Benchmark will use " + bookCount + " books");

		sampleBookId = availableBooks.get(0);
		sampleHeader = datalakeReader.readBookHeader(sampleBookId);
		sampleBody = datalakeReader.readBookBody(sampleBookId);

		System.out.println("Sample book " + sampleBookId + " loaded: " +
				sampleBody.length() + " characters");
	}

	/**
	 * Benchmark: Tokenize a single book body (word extraction)
	 */
	@Benchmark
	public void tokenizeSingleBook(Blackhole blackhole) {
		Set<String> words = extractWords(sampleBody, 3, 50);
		blackhole.consume(words);
	}

	/**
	 * Benchmark: Extract metadata from a single book header
	 */
	@Benchmark
	public void extractMetadataFromSingleBook(Blackhole blackhole) {
		BookMetadata metadata = metadataExtractor.extractMetadata(
				sampleBookId,
				sampleHeader,
				"datalake/book_" + sampleBookId
		);
		blackhole.consume(metadata);
	}

	/**
	 * Benchmark: Build in-memory inverted index from N books
	 * This tests pure index construction without I/O overhead
	 */
	@Benchmark
	public void buildInMemoryInvertedIndex(Blackhole blackhole) throws IOException {
		Map<String, Set<Integer>> index = new HashMap<>();

		int booksToProcess = Math.min(bookCount, availableBooks.size());

		for (int i = 0; i < booksToProcess; i++) {
			int bookId = availableBooks.get(i);
			String body = datalakeReader.readBookBody(bookId);

			Set<String> words = extractWords(body, 3, 50);

			for (String word : words) {
				index.computeIfAbsent(word, k -> new HashSet<>()).add(bookId);
			}
		}

		blackhole.consume(index);
	}

	/**
	 * Benchmark: Extract words from multiple books
	 * Tests tokenization performance at scale
	 */
	@Benchmark
	public void extractWordsFromMultipleBooks(Blackhole blackhole) throws IOException {
		int totalWords = 0;
		int booksToProcess = Math.min(bookCount, availableBooks.size());

		for (int i = 0; i < booksToProcess; i++) {
			int bookId = availableBooks.get(i);
			String body = datalakeReader.readBookBody(bookId);
			Set<String> words = extractWords(body, 3, 50);
			totalWords += words.size();
		}

		blackhole.consume(totalWords);
	}

	/**
	 * Benchmark: Extract metadata from multiple books
	 */
	@Benchmark
	public void extractMetadataFromMultipleBooks(Blackhole blackhole) throws IOException {
		List<BookMetadata> allMetadata = new ArrayList<>();
		int booksToProcess = Math.min(bookCount, availableBooks.size());

		for (int i = 0; i < booksToProcess; i++) {
			int bookId = availableBooks.get(i);
			String header = datalakeReader.readBookHeader(bookId);
			BookMetadata metadata = metadataExtractor.extractMetadata(
					bookId, header, "datalake/book_" + bookId
			);
			allMetadata.add(metadata);
		}

		blackhole.consume(allMetadata);
	}

	private Set<String> extractWords(String text, int minLength, int maxLength) {
		Set<String> words = new HashSet<>();
		String[] tokens = text.toLowerCase().split("\\s+");

		for (String token : tokens) {
			var matcher = WORD_PATTERN.matcher(token);
			while (matcher.find()) {
				String word = matcher.group();
				if (word.length() >= minLength && word.length() <= maxLength) {
					words.add(word);
				}
			}
		}

		return words;
	}
}