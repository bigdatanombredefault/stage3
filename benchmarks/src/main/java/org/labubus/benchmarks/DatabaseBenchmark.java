package org.labubus.benchmarks;

import org.labubus.indexing.model.BookMetadata;
import org.labubus.indexing.repository.SqliteMetadataRepository;
import org.labubus.indexing.service.MetadataExtractor;
import org.labubus.indexing.storage.DatalakeReader;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks for database operations (INSERT, SELECT, queries)
 * Tests how performance scales with database size
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DatabaseBenchmark {

	private static final String DATALAKE_PATH = "../datalake";
	private static final String BENCHMARK_DB_DIR = "./benchmark-dbs";

	private DatalakeReader datalakeReader;
	private MetadataExtractor metadataExtractor;
	private SqliteMetadataRepository repository;
	private List<Integer> availableBooks;

	private BookMetadata newBookMetadata;
	private int testBookIdForQuery;

	@Param({"10", "50", "100", "300"})
	private int dbSize;

	@Setup(Level.Trial)
	public void setup() throws IOException, SQLException {
		System.out.println("=== Database Benchmark Setup (dbSize=" + dbSize + ") ===");

		datalakeReader = new DatalakeReader(DATALAKE_PATH);
		metadataExtractor = new MetadataExtractor();
		availableBooks = datalakeReader.getDownloadedBooks();

		if (availableBooks.size() < dbSize + 1) {
			throw new RuntimeException("Need at least " + (dbSize + 1) + " books, found " + availableBooks.size());
		}

		Files.createDirectories(Paths.get(BENCHMARK_DB_DIR));

		String dbPath = BENCHMARK_DB_DIR + "/test_db_" + dbSize + ".sqlite";
		repository = new SqliteMetadataRepository(dbPath);

		System.out.println("Populating database with " + dbSize + " books...");
		populateDatabase(dbSize);

		int newBookId = availableBooks.get(dbSize);
		String header = datalakeReader.readBookHeader(newBookId);
		newBookMetadata = metadataExtractor.extractMetadata(
				newBookId, header, "datalake/book_" + newBookId
		);

		testBookIdForQuery = availableBooks.get(dbSize / 2);

		System.out.println("Database ready: " + dbSize + " books indexed");
	}

	@TearDown(Level.Trial)
	public void tearDown() throws SQLException, IOException {
		if (repository != null) {
			repository.close();
		}

		try {
			Path dbDir = Paths.get(BENCHMARK_DB_DIR);
			if (Files.exists(dbDir)) {
				Files.walk(dbDir)
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
	 * Benchmark: INSERT a new book metadata
	 * Tests write performance
	 */
	@Benchmark
	public void insertBookMetadata(Blackhole blackhole) throws SQLException {
		int testId = 90000 + (int)(Math.random() * 1000);
		BookMetadata testData = new BookMetadata(
				testId,
				newBookMetadata.title(),
				newBookMetadata.author(),
				newBookMetadata.language(),
				newBookMetadata.year(),
				newBookMetadata.path()
		);

		repository.save(testData);
		blackhole.consume(testId);

		repository.delete(testId);
	}

	/**
	 * Benchmark: SELECT by book ID (primary key lookup)
	 */
	@Benchmark
	public void selectBookById(Blackhole blackhole) throws SQLException {
		Optional<BookMetadata> result = repository.findById(testBookIdForQuery);
		blackhole.consume(result);
	}

	/**
	 * Benchmark: COUNT all books
	 */
	@Benchmark
	public void countAllBooks(Blackhole blackhole) throws SQLException {
		int count = repository.count();
		blackhole.consume(count);
	}

	/**
	 * Benchmark: SELECT all books (full table scan)
	 */
	@Benchmark
	public void selectAllBooks(Blackhole blackhole) throws SQLException {
		List<BookMetadata> books = repository.findAll();
		blackhole.consume(books);
	}

	/**
	 * Benchmark: Query by author (uses index, but still requires filtering)
	 * Simulates search by author pattern
	 */
	@Benchmark
	public void queryByAuthorPattern(Blackhole blackhole) throws SQLException {
		List<BookMetadata> allBooks = repository.findAll();
		List<BookMetadata> filtered = new ArrayList<>();

		for (BookMetadata book : allBooks) {
			if (book.author() != null && book.author().toLowerCase().contains("a")) {
				filtered.add(book);
			}
		}

		blackhole.consume(filtered);
	}

	/**
	 * Benchmark: Query by language (uses index)
	 */
	@Benchmark
	public void queryByLanguage(Blackhole blackhole) throws SQLException {
		List<BookMetadata> allBooks = repository.findAll();
		List<BookMetadata> filtered = new ArrayList<>();

		for (BookMetadata book : allBooks) {
			if ("en".equalsIgnoreCase(book.language())) {
				filtered.add(book);
			}
		}

		blackhole.consume(filtered);
	}

	/**
	 * Benchmark: Query by year (uses index)
	 */
	@Benchmark
	public void queryByYear(Blackhole blackhole) throws SQLException {
		List<BookMetadata> allBooks = repository.findAll();
		List<BookMetadata> filtered = new ArrayList<>();

		int targetYear = 1800;

		for (BookMetadata book : allBooks) {
			if (book.year() != null && book.year() >= targetYear && book.year() <= targetYear + 50) {
				filtered.add(book);
			}
		}

		blackhole.consume(filtered);
	}

	private void populateDatabase(int count) throws IOException, SQLException {
		for (int i = 0; i < count; i++) {
			int bookId = availableBooks.get(i);
			String header = datalakeReader.readBookHeader(bookId);
			BookMetadata metadata = metadataExtractor.extractMetadata(
					bookId, header, "datalake/book_" + bookId
			);
			repository.save(metadata);
		}

		System.out.println("Database populated with " + repository.count() + " books");
	}
}