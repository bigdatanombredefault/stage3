package org.labubus.indexing.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatalakeReader {
	private static final Logger logger = LoggerFactory.getLogger(DatalakeReader.class);
	private final String datalakePath;
	private final String trackingFilename;
	private final ConcurrentHashMap<Integer, BookPaths> bookPathsCache = new ConcurrentHashMap<>();

	private record BookPaths(Path headerPath, Path bodyPath) {}

	public DatalakeReader(String datalakePath, String trackingFilename) {
		if (datalakePath == null || datalakePath.isBlank()) {
			throw new IllegalArgumentException("datalakePath cannot be null/blank");
		}
		if (trackingFilename == null || trackingFilename.isBlank()) {
			throw new IllegalArgumentException("trackingFilename cannot be null/blank");
		}
		this.datalakePath = datalakePath.trim();
		this.trackingFilename = trackingFilename.trim();
	}

	public List<Integer> getDownloadedBooks() throws IOException {
		Path trackingFile = Paths.get(datalakePath, trackingFilename);
		List<Integer> bookIds = new ArrayList<>();

		if (!Files.exists(trackingFile)) {
			logger.warn("Tracking file not found: {}", trackingFile);
			return bookIds;
		}

		List<String> lines = Files.readAllLines(trackingFile);
		for (String line : lines) {
			String[] parts = line.split("\\|");
			if (parts.length > 0 && !parts[0].trim().isEmpty()) {
				try {
					bookIds.add(Integer.valueOf(parts[0].trim()));
				} catch (NumberFormatException e) {
					logger.warn("Invalid book ID in tracking file: {}", line);
				}
			}
		}

		logger.info("Found {} downloaded books in datalake", bookIds.size());
		return bookIds;
	}

	public List<Integer> scanBookIdsFromFiles() throws IOException {
		Path datalakeDir = Paths.get(datalakePath);
		if (!Files.exists(datalakeDir)) {
			return List.of();
		}

		Set<Integer> ids = new HashSet<>();
		try (Stream<Path> paths = Files.walk(datalakeDir)) {
			paths
				.filter(Files::isRegularFile)
				.map(p -> p.getFileName().toString())
				.filter(name -> name.endsWith("_body.txt"))
				.map(this::parseBodyFilenameOrNull)
				.filter(id -> id != null)
				.forEach(ids::add);
		}

		List<Integer> list = new ArrayList<>(ids);
		Collections.sort(list);
		return list;
	}

	private Integer parseBodyFilenameOrNull(String filename) {
		String suffix = "_body.txt";
		if (filename == null || !filename.endsWith(suffix)) {
			return null;
		}
		String prefix = filename.substring(0, filename.length() - suffix.length());
		try {
			return Integer.valueOf(prefix);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public String readBookHeader(int bookId) throws IOException {
		BookPaths paths = resolveBookPaths(bookId);
		Path headerPath = paths.headerPath();
		if (headerPath == null) {
			throw new IOException("Header file not found for book " + bookId);
		}
		return Files.readString(headerPath);
	}

	public String readBookBody(int bookId) throws IOException {
		BookPaths paths = resolveBookPaths(bookId);
		Path bodyPath = paths.bodyPath();
		if (bodyPath == null) {
			throw new IOException("Body file not found for book " + bookId);
		}
		return Files.readString(bodyPath);
	}

	private Path findBookHeader(int bookId) throws IOException {
		return resolveBookPaths(bookId).headerPath();
	}

	private BookPaths resolveBookPaths(int bookId) throws IOException {
		BookPaths cached = bookPathsCache.get(bookId);
		if (cached != null) {
			return cached;
		}

		BookPaths resolved = scanBookPaths(bookId);
		if (resolved.headerPath() != null && resolved.bodyPath() != null) {
			bookPathsCache.put(bookId, resolved);
		}
		return resolved;
	}

	private BookPaths scanBookPaths(int bookId) throws IOException {
		Path datalakeDir = Paths.get(datalakePath);
		if (!Files.exists(datalakeDir)) {
			throw new IOException("Datalake directory not found: " + datalakePath);
		}

		String headerName = bookId + "_header.txt";
		String bodyName = bookId + "_body.txt";
		Path headerPath = null;
		Path bodyPath = null;

		try (Stream<Path> paths = Files.walk(datalakeDir)) {
			Iterator<Path> it = paths.iterator();
			while (it.hasNext() && (headerPath == null || bodyPath == null)) {
				Path p = it.next();
				if (!Files.isRegularFile(p)) {
					continue;
				}
				String name = p.getFileName().toString();
				if (headerPath == null && headerName.equals(name)) {
					headerPath = p;
				} else if (bodyPath == null && bodyName.equals(name)) {
					bodyPath = p;
				}
			}
		}

		return new BookPaths(headerPath, bodyPath);
	}

	public boolean bookExists(int bookId) {
		try {
			BookPaths p = resolveBookPaths(bookId);
			return p.headerPath() != null && p.bodyPath() != null;
		} catch (IOException e) {
			return false;
		}
	}

    public String getBookDirectoryPath(int bookId) throws IOException {
		Path headerPath = findBookHeader(bookId);

        if (headerPath != null && headerPath.getParent() != null) {
            return headerPath.getParent().toString();
        } else {
            throw new IOException("Could not find directory path for book " + bookId);
        }
    }
}