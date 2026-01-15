package org.labubus.ingestion.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimestampDatalakeStorage implements DatalakeStorage {
	private static final Logger logger = LoggerFactory.getLogger(TimestampDatalakeStorage.class);
	private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("HH");

	private final String datalakePath;
	private final Path downloadedBooksFile;

	public TimestampDatalakeStorage(String datalakePath, String trackingFilename) {
		if (datalakePath == null || datalakePath.isBlank()) {
			throw new IllegalArgumentException("datalakePath cannot be null/blank");
		}
		if (trackingFilename == null || trackingFilename.isBlank()) {
			throw new IllegalArgumentException("trackingFilename cannot be null/blank");
		}
		this.datalakePath = datalakePath.trim();
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
		Path bookPath = createBookDirectory(bookId);
		writeBookFiles(bookPath, bookId, header, body);
		trackDownloadedBook(bookId, bookPath.toString());
		logger.info("Saved book {} to {}", bookId, bookPath);
		return bookPath.toString();
	}

	private Path createBookDirectory(int bookId) throws IOException {
		Path bookPath = getTimestampPath(bookId);
		Files.createDirectories(bookPath);
		return bookPath;
	}

	private void writeBookFiles(Path bookPath, int bookId, String header, String body) throws IOException {
		writeFile(bookPath.resolve(bookId + "_header.txt"), header);
		writeFile(bookPath.resolve(bookId + "_body.txt"), body);
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
		try {
			return findBookPath(bookId);
		} catch (IOException e) {
			logger.error("Error getting book path", e);
			return null;
		}
	}

	private String findBookPath(int bookId) throws IOException {
		if (!Files.exists(downloadedBooksFile)) {
			return null;
		}
		for (String line : Files.readAllLines(downloadedBooksFile)) {
			String path = parsePathOrNull(line, bookId);
			if (path != null) {
				return path;
			}
		}
		return null;
	}

	private String parsePathOrNull(String line, int bookId) {
		String[] parts = line.split("\\|");
		if (parts.length != 2) {
			return null;
		}
		Integer parsedId = parseIdOrNull(parts[0]);
		return parsedId != null && parsedId == bookId ? parts[1].trim() : null;
	}

	private void trackDownloadedBook(int bookId, String path) throws IOException {
		Set<BookEntry> entries = getDownloadedBookEntries();
		entries.add(new BookEntry(bookId, path));
		Files.writeString(downloadedBooksFile, formatEntries(entries));
	}

	private String formatEntries(Set<BookEntry> entries) {
		List<BookEntry> sortedEntries = new ArrayList<>(entries);
		sortedEntries.sort(Comparator.comparingInt(e -> e.bookId));
		StringBuilder content = new StringBuilder();
		for (BookEntry entry : sortedEntries) {
			content.append(entry.bookId).append("|").append(entry.path).append("\n");
		}
		return content.toString();
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
					books.add(Integer.valueOf(parts[0].trim()));
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
		for (String line : Files.readAllLines(downloadedBooksFile)) {
			BookEntry entry = parseEntryOrNull(line);
			if (entry != null) {
				entries.add(entry);
			}
		}
		return entries;
	}

	private BookEntry parseEntryOrNull(String line) {
		String[] parts = line.split("\\|");
		if (parts.length != 2) {
			return null;
		}
		Integer id = parseIdOrNull(parts[0]);
		if (id == null) {
			logger.warn("Invalid entry in tracking file: {}", line);
			return null;
		}
		return new BookEntry(id, parts[1].trim());
	}

	private Integer parseIdOrNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		try {
			return Integer.valueOf(trimmed);
		} catch (NumberFormatException e) {
			return null;
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