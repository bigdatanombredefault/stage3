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
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end benchmarks for complete indexing pipeline
 * Tests: datalake read → metadata extraction → DB save → index build → index save
 *
 * This benchmark answers the key question: "How long does it take to fully index N books?"
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(1)
public class EndToEndBenchmark {

	private static final String DATALAKE_PATH = "../datalake";
	private static final String BENCHMARK_DIR = "./benchmark-e2e";

	private DatalakeReader datalakeReader;
	private MetadataExtractor metadataExtractor;
	private List<Integer> availableBooks;

	@Param({"10", "50", "100", "300"})
	private int bookCount;

	@Setup(Level.Trial)
	public void setup() throws IOException {
		System.out.println("=== End-to-End Benchmark Setup (bookCount=" + bookCount + ") ===");

		datalakeReader = new DatalakeReader(DATALAKE_PATH);
		metadataExtractor = new MetadataExtractor();
		availableBooks = datalakeReader.getDownloadedBooks();

		if (availableBooks.size() < bookCount) {
			throw new RuntimeException("Need at least " + bookCount + " books, found " + availableBooks.size());
		}

		Files.createDirectories(Paths.get(BENCHMARK_DIR));
		System.out.println("Ready to benchmark " + bookCount + " books");
	}

	@TearDown(Level.Trial)
	public void tearDown() throws IOException {
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
	 * Benchmark: Complete indexing pipeline for N books
	 * This is the most important benchmark - measures total time to index a dataset
	 */
	@Benchmark
	public void completeIndexingPipeline(Blackhole blackhole) throws IOException, SQLException {
		String iterationId = String.valueOf(System.nanoTime());
		String dbPath = BENCHMARK_DIR + "/e2e_" + iterationId + ".sqlite";

		SqliteMetadataRepository repository = new SqliteMetadataRepository(dbPath);
		InvertedIndexWriter indexWriter = new JsonIndexWriter(BENCHMARK_DIR, "e2e_index_" + iterationId + ".json");
		InvertedIndexBuilder indexBuilder = new InvertedIndexBuilder(indexWriter, 3, 50, new HashSet<>());

		int processedBooks = 0;

		try {
			for (int i = 0; i < bookCount; i++) {
				int bookId = availableBooks.get(i);

				String header = datalakeReader.readBookHeader(bookId);
				String body = datalakeReader.readBookBody(bookId);

				BookMetadata metadata = metadataExtractor.extractMetadata(
						bookId, header, "datalake/book_" + bookId
				);

				repository.save(metadata);

				indexBuilder.indexBook(bookId, body);

				processedBooks++;
			}

			indexWriter.save();

		} finally {
			repository.close();

			try {
				Files.deleteIfExists(Paths.get(dbPath));
				Files.deleteIfExists(Paths.get(BENCHMARK_DIR, "e2e_index_" + iterationId + ".json"));
			} catch (IOException e) {
			}
		}

		blackhole.consume(processedBooks);
	}

	/**
	 * Benchmark: Metadata extraction and database insertion only
	 * Isolates database performance from indexing
	 */
	@Benchmark
	public void metadataExtractionAndStorage(Blackhole blackhole) throws IOException, SQLException {
		String iterationId = String.valueOf(System.nanoTime());
		String dbPath = BENCHMARK_DIR + "/metadata_" + iterationId + ".sqlite";

		SqliteMetadataRepository repository = new SqliteMetadataRepository(dbPath);
		int processedBooks = 0;

		try {
			for (int i = 0; i < bookCount; i++) {
				int bookId = availableBooks.get(i);

				String header = datalakeReader.readBookHeader(bookId);
				BookMetadata metadata = metadataExtractor.extractMetadata(
						bookId, header, "datalake/book_" + bookId
				);

				repository.save(metadata);
				processedBooks++;
			}
		} finally {
			repository.close();

			try {
				Files.deleteIfExists(Paths.get(dbPath));
			} catch (IOException e) {
			}
		}

		blackhole.consume(processedBooks);
	}

	/**
	 * Benchmark: Index building and serialization only
	 * Isolates indexing performance from database
	 */
	@Benchmark
	public void indexBuildingAndSerialization(Blackhole blackhole) throws IOException {
		String iterationId = String.valueOf(System.nanoTime());

		InvertedIndexWriter indexWriter = new JsonIndexWriter(BENCHMARK_DIR, "index_only_" + iterationId + ".json");
		InvertedIndexBuilder indexBuilder = new InvertedIndexBuilder(indexWriter, 3, 50, new HashSet<>());

		int processedBooks = 0;

		for (int i = 0; i < bookCount; i++) {
			int bookId = availableBooks.get(i);
			String body = datalakeReader.readBookBody(bookId);
			indexBuilder.indexBook(bookId, body);
			processedBooks++;
		}

		indexWriter.save();

		try {
			Files.deleteIfExists(Paths.get(BENCHMARK_DIR, "index_only_" + iterationId + ".json"));
		} catch (IOException e) {
		}

		blackhole.consume(processedBooks);
	}

	/**
	 * Benchmark: Reading all book files from datalake
	 * Measures pure I/O performance
	 */
	@Benchmark
	public void readAllBooksFromDatalake(Blackhole blackhole) throws IOException {
		int totalChars = 0;

		for (int i = 0; i < bookCount; i++) {
			int bookId = availableBooks.get(i);
			String header = datalakeReader.readBookHeader(bookId);
			String body = datalakeReader.readBookBody(bookId);
			totalChars += header.length() + body.length();
		}

		blackhole.consume(totalChars);
	}

	/**
	 * Benchmark: Throughput calculation (books per second)
	 * This helps answer "How many books can we index per second?"
	 */
	@Benchmark
	@BenchmarkMode(Mode.Throughput)
	@OutputTimeUnit(TimeUnit.SECONDS)
	public void indexingThroughput(Blackhole blackhole) throws IOException, SQLException {
		String iterationId = String.valueOf(System.nanoTime());
		String dbPath = BENCHMARK_DIR + "/throughput_" + iterationId + ".sqlite";

		SqliteMetadataRepository repository = new SqliteMetadataRepository(dbPath);
		InvertedIndexWriter indexWriter = new JsonIndexWriter(BENCHMARK_DIR, "throughput_" + iterationId + ".json");
		InvertedIndexBuilder indexBuilder = new InvertedIndexBuilder(indexWriter, 3, 50, new HashSet<>());

		try {
			for (int i = 0; i < bookCount; i++) {
				int bookId = availableBooks.get(i);

				String header = datalakeReader.readBookHeader(bookId);
				String body = datalakeReader.readBookBody(bookId);

				BookMetadata metadata = metadataExtractor.extractMetadata(
						bookId, header, "datalake/book_" + bookId
				);

				repository.save(metadata);
				indexBuilder.indexBook(bookId, body);
			}

			indexWriter.save();
		} finally {
			repository.close();

			try {
				Files.deleteIfExists(Paths.get(dbPath));
				Files.deleteIfExists(Paths.get(BENCHMARK_DIR, "throughput_" + iterationId + ".json"));
			} catch (IOException e) {
			}
		}

		blackhole.consume(bookCount);
	}
}