package org.labubus.ingestion.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BucketDatalakeStorage implements DatalakeStorage {
	private static final Logger logger = LoggerFactory.getLogger(BucketDatalakeStorage.class);

	private final String datalakePath;
	private final int bucketSize;
	private final Path downloadedBooksFile;
	private final ReentrantLock trackingLock = new ReentrantLock();

	public BucketDatalakeStorage(String datalakePath, int bucketSize, String trackingFilename) {
		if (datalakePath == null || datalakePath.isBlank()) {
			throw new IllegalArgumentException("datalakePath cannot be null/blank");
		}
		if (bucketSize <= 0) {
			throw new IllegalArgumentException("bucketSize must be > 0");
		}
		if (trackingFilename == null || trackingFilename.isBlank()) {
			throw new IllegalArgumentException("trackingFilename cannot be null/blank");
		}
		this.datalakePath = datalakePath.trim();
		this.bucketSize = bucketSize;
		this.downloadedBooksFile = Paths.get(this.datalakePath, trackingFilename.trim());
		initializeDatalake();
	}

	private void initializeDatalake() {
		try {
			Files.createDirectories(Paths.get(datalakePath));

			if (!Files.exists(downloadedBooksFile)) {
				Files.createFile(downloadedBooksFile);
				logger.info("Created {} tracking file", downloadedBooksFile.getFileName());
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
		Path bucketPath = ensureBucketDir(bookId);
		writeBookFiles(bucketPath, bookId, header, body);
		trackDownloadedBook(bookId);
		logger.info("Saved book {} to bucket {}", bookId, bucketPath);
		return bucketPath.toString();
	}

	private Path ensureBucketDir(int bookId) throws IOException {
		Path bucketPath = getBucketPath(bookId);
		Files.createDirectories(bucketPath);
		return bucketPath;
	}

	private void writeBookFiles(Path bucketPath, int bookId, String header, String body) throws IOException {
		writeFile(bucketPath.resolve(bookId + "_header.txt"), header);
		writeFile(bucketPath.resolve(bookId + "_body.txt"), body);
	}

	private void writeFile(Path filePath, String content) throws IOException {
		Files.writeString(filePath, content);
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
		trackingLock.lock();
		try {
			Set<Integer> current = getDownloadedBooks();
			if (current.add(bookId)) {
				writeTrackedBookIds(current);
			}
		} finally {
			trackingLock.unlock();
		}
	}

	private void writeTrackedBookIds(Set<Integer> ids) throws IOException {
		StringBuilder content = new StringBuilder();
		for (int id : sorted(ids)) {
			content.append(id).append("\n");
		}
		Files.writeString(downloadedBooksFile, content.toString());
	}

	private List<Integer> sorted(Set<Integer> ids) {
		List<Integer> sorted = new ArrayList<>(ids);
		Collections.sort(sorted);
		return sorted;
	}

	private Integer parseIdOrNull(String line) {
		String trimmed = line == null ? "" : line.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		try {
			return Integer.valueOf(trimmed);
		} catch (NumberFormatException e) {
			logger.warn("Invalid book ID in tracking file: {}", line);
			return null;
		}
	}

	@Override
	public Set<Integer> getDownloadedBooks() throws IOException {
		trackingLock.lock();
		try {
			Set<Integer> books = new HashSet<>();

			if (!Files.exists(downloadedBooksFile)) {
				return books;
			}

			List<String> lines = Files.readAllLines(downloadedBooksFile);
			for (String line : lines) {
				String trimmed = line == null ? "" : line.trim();
				Integer parsed = parseIdOrNull(trimmed);
				if (parsed != null) {
					books.add(parsed);
				}
			}

			return books;
		} finally {
			trackingLock.unlock();
		}
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