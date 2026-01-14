package org.labubus.ingestion.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BucketDatalakeStorage implements DatalakeStorage {
	private static final Logger logger = LoggerFactory.getLogger(BucketDatalakeStorage.class);

	private final String datalakePath;
	private final int bucketSize;
	private final Path downloadedBooksFile;

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
        // 1. Lock the file channel prevents other containers from writing simultaneously
        try (RandomAccessFile file = new RandomAccessFile(downloadedBooksFile.toFile(), "rw");
             FileChannel channel = file.getChannel();
             FileLock lock = channel.lock()) { // Blocks until lock is acquired

			if (!lock.isValid()) {
				throw new IOException("Could not acquire a valid lock for tracking file: " + downloadedBooksFile);
			}

            // 2. Read current content
            Set<Integer> currentBooks = new HashSet<>();
            String line;
            while ((line = file.readLine()) != null) {
                if (!line.trim().isEmpty()) {
					currentBooks.add(Integer.valueOf(line.trim()));
                }
            }

            // 3. Add new book
            if (currentBooks.add(bookId)) {
                // 4. Rewrite file
                file.setLength(0); // Clear file
                StringBuilder content = new StringBuilder();
                List<Integer> sorted = new ArrayList<>(currentBooks);
                Collections.sort(sorted);
                for (int id : sorted) {
                    content.append(id).append("\n");
                }
                file.writeBytes(content.toString());
            }
            // Lock is automatically released by try-with-resources
        }
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
					books.add(Integer.valueOf(line));
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