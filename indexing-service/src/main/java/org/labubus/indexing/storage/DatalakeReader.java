package org.labubus.indexing.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class DatalakeReader {
	private static final Logger logger = LoggerFactory.getLogger(DatalakeReader.class);
	private final String datalakePath;

	public DatalakeReader(String datalakePath) {
		this.datalakePath = datalakePath;
	}

	/**
	 * Get list of all downloaded book IDs from the tracking file
	 */
	public List<Integer> getDownloadedBooks() throws IOException {
		Path trackingFile = Paths.get(datalakePath, "downloaded_books.txt");
		List<Integer> bookIds = new ArrayList<>();

		if (!Files.exists(trackingFile)) {
			logger.warn("Tracking file not found: {}", trackingFile);
			return bookIds;
		}

		List<String> lines = Files.readAllLines(trackingFile);
		for (String line : lines) {
			String[] parts = line.split("\\|");
			String bookIdStr = parts[0].trim();

			if (!bookIdStr.isEmpty()) {
				try {
					bookIds.add(Integer.parseInt(bookIdStr));
				} catch (NumberFormatException e) {
					logger.warn("Invalid book ID in tracking file: {}", line);
				}
			}
		}

		logger.info("Found {} downloaded books in datalake", bookIds.size());
		return bookIds;
	}

	/**
	 * Read book header for a specific book ID
	 */
	public String readBookHeader(int bookId) throws IOException {
		Path headerPath = findBookHeader(bookId);
		if (headerPath == null) {
			throw new IOException("Header file not found for book " + bookId);
		}
		return Files.readString(headerPath);
	}

	/**
	 * Read book body for a specific book ID
	 */
	public String readBookBody(int bookId) throws IOException {
		Path bodyPath = findBookBody(bookId);
		if (bodyPath == null) {
			throw new IOException("Body file not found for book " + bookId);
		}
		return Files.readString(bodyPath);
	}

	/**
	 * Find header file for a book (searches all bucket/timestamp structures)
	 */
	private Path findBookHeader(int bookId) throws IOException {
		return findBookFile(bookId, "_header.txt");
	}

	/**
	 * Find body file for a book (searches all bucket/timestamp structures)
	 */
	private Path findBookBody(int bookId) throws IOException {
		return findBookFile(bookId, "_body.txt");
	}

	/**
	 * Generic method to find a book file with a specific suffix
	 */
	private Path findBookFile(int bookId, String suffix) throws IOException {
		Path datalakeDir = Paths.get(datalakePath);

		if (!Files.exists(datalakeDir)) {
			throw new IOException("Datalake directory not found: " + datalakePath);
		}

		try (Stream<Path> paths = Files.walk(datalakeDir)) {
			return paths
					.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().equals(bookId + suffix))
					.findFirst()
					.orElse(null);
		}
	}

	/**
	 * Check if a book exists in the datalake
	 */
	public boolean bookExists(int bookId) {
		try {
			return findBookHeader(bookId) != null && findBookBody(bookId) != null;
		} catch (IOException e) {
			return false;
		}
	}
}