package org.labubus.ingestion.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BucketDatalakeStorage implements DatalakeStorage {
	private static final Logger logger = LoggerFactory.getLogger(BucketDatalakeStorage.class);

	private final String datalakePath;
	private final int bucketSize;
	private final Path downloadedBooksFile;

	public BucketDatalakeStorage(String datalakePath, int bucketSize) {
		this.datalakePath = datalakePath;
		this.bucketSize = bucketSize;
		this.downloadedBooksFile = Paths.get(datalakePath, "downloaded_books.txt");
		initializeDatalake();
	}

	private void initializeDatalake() {
		try {
			Files.createDirectories(Paths.get(datalakePath));

			if (!Files.exists(downloadedBooksFile)) {
				Files.createFile(downloadedBooksFile);
				logger.info("Created downloaded_books.txt tracking file");
			}

			logger.info("Bucket-based datalake initialized at: {}", datalakePath);
		} catch (IOException e) {
			logger.error("Failed to initialize datalake", e);
			throw new RuntimeException("Failed to initialize datalake", e);
		}
	}

	private int calculateBucket(int bookId) {
		return bookId / bucketSize;
	}

	private Path getBucketPath(int bookId) {
		int bucket = calculateBucket(bookId);
		return Paths.get(datalakePath, "bucket_" + bucket);
	}

	@Override
	public String saveBook(int bookId, String header, String body) throws IOException {
		Path bucketPath = getBucketPath(bookId);

		Files.createDirectories(bucketPath);

		Path headerPath = bucketPath.resolve(bookId + "_header.txt");
		Files.writeString(headerPath, header);

		Path bodyPath = bucketPath.resolve(bookId + "_body.txt");
		Files.writeString(bodyPath, body);

		trackDownloadedBook(bookId);

		logger.info("Saved book {} to bucket {}", bookId, bucketPath);
		return bucketPath.toString();
	}

	@Override
	public boolean isBookDownloaded(int bookId) {
		try {
			Set<Integer> downloadedBooks = getDownloadedBooks();
			return downloadedBooks.contains(bookId);
		} catch (IOException e) {
			logger.error("Error checking if book is downloaded", e);
			return false;
		}
	}

	@Override
	public String getBookPath(int bookId) {
		if (isBookDownloaded(bookId)) {
			return getBucketPath(bookId).toString();
		}
		return null;
	}

	private void trackDownloadedBook(int bookId) throws IOException {
		Set<Integer> downloadedBooks = getDownloadedBooks();
		downloadedBooks.add(bookId);

		List<Integer> sortedBooks = new ArrayList<>(downloadedBooks);
		Collections.sort(sortedBooks);

		StringBuilder content = new StringBuilder();
		for (int id : sortedBooks) {
			content.append(id).append("\n");
		}

		Files.writeString(downloadedBooksFile, content.toString());
	}

	@Override
	public Set<Integer> getDownloadedBooks() throws IOException {
		Set<Integer> books = new HashSet<>();

		if (!Files.exists(downloadedBooksFile)) {
			return books;
		}

		List<String> lines = Files.readAllLines(downloadedBooksFile);
		for (String line : lines) {
			line = line.trim();
			if (!line.isEmpty()) {
				try {
					books.add(Integer.parseInt(line));
				} catch (NumberFormatException e) {
					logger.warn("Invalid book ID in tracking file: {}", line);
				}
			}
		}

		return books;
	}

	@Override
	public List<Integer> getDownloadedBooksList() throws IOException {
		List<Integer> books = new ArrayList<>(getDownloadedBooks());
		Collections.sort(books);
		return books;
	}

	@Override
	public int getDownloadedBooksCount() throws IOException {
		return getDownloadedBooks().size();
	}
}