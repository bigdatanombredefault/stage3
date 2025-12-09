package org.labubus.ingestion.storage;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimestampDatalakeStorage implements DatalakeStorage {
	private static final Logger logger = LoggerFactory.getLogger(TimestampDatalakeStorage.class);
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("HH");

	private final String datalakePath;
	private final Path downloadedBooksFile;

	public TimestampDatalakeStorage(String datalakePath) {
		this.datalakePath = datalakePath;
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

			logger.info("Timestamp-based datalake initialized at: {}", datalakePath);
		} catch (IOException e) {
			logger.error("Failed to initialize datalake", e);
			throw new RuntimeException("Failed to initialize datalake", e);
		}
	}

	private Path getTimestampPath(int bookId) {
		LocalDateTime now = LocalDateTime.now();
		String date = now.format(DATE_FORMATTER);
		String hour = now.format(HOUR_FORMATTER);
		return Paths.get(datalakePath, date, hour, String.valueOf(bookId));
	}

	@Override
	public String saveBook(int bookId, String header, String body) throws IOException {
		Path bookPath = getTimestampPath(bookId);

		Files.createDirectories(bookPath);

		Path headerPath = bookPath.resolve(bookId + "_header.txt");
		Files.writeString(headerPath, header);

		Path bodyPath = bookPath.resolve(bookId + "_body.txt");
		Files.writeString(bodyPath, body);

		trackDownloadedBook(bookId, bookPath.toString());

		logger.info("Saved book {} to {}", bookId, bookPath);
		return bookPath.toString();
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
		try {
			if (!Files.exists(downloadedBooksFile)) {
				return null;
			}

			List<String> lines = Files.readAllLines(downloadedBooksFile);
			for (String line : lines) {
				String[] parts = line.split("\\|");
				if (parts.length == 2) {
					int id = Integer.parseInt(parts[0].trim());
					if (id == bookId) {
						return parts[1].trim();
					}
				}
			}
		} catch (IOException e) {
			logger.error("Error getting book path", e);
		}
		return null;
	}

	private void trackDownloadedBook(int bookId, String path) throws IOException {
		Set<BookEntry> entries = getDownloadedBookEntries();
		entries.add(new BookEntry(bookId, path));

		List<BookEntry> sortedEntries = new ArrayList<>(entries);
		sortedEntries.sort(Comparator.comparingInt(e -> e.bookId));

		StringBuilder content = new StringBuilder();
		for (BookEntry entry : sortedEntries) {
			content.append(entry.bookId).append("|").append(entry.path).append("\n");
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
			String[] parts = line.split("\\|");
			if (parts.length >= 1) {
				try {
					books.add(Integer.parseInt(parts[0].trim()));
				} catch (NumberFormatException e) {
					logger.warn("Invalid book ID in tracking file: {}", line);
				}
			}
		}

		return books;
	}

	private Set<BookEntry> getDownloadedBookEntries() throws IOException {
		Set<BookEntry> entries = new HashSet<>();

		if (!Files.exists(downloadedBooksFile)) {
			return entries;
		}

		List<String> lines = Files.readAllLines(downloadedBooksFile);
		for (String line : lines) {
			String[] parts = line.split("\\|");
			if (parts.length == 2) {
				try {
					int id = Integer.parseInt(parts[0].trim());
					String path = parts[1].trim();
					entries.add(new BookEntry(id, path));
				} catch (NumberFormatException e) {
					logger.warn("Invalid entry in tracking file: {}", line);
				}
			}
		}

		return entries;
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

	private static class BookEntry {
		int bookId;
		String path;

		BookEntry(int bookId, String path) {
			this.bookId = bookId;
			this.path = path;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			BookEntry that = (BookEntry) o;
			return bookId == that.bookId;
		}

		@Override
		public int hashCode() {
			return Integer.hashCode(bookId);
		}
	}
}