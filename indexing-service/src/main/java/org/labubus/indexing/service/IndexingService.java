package org.labubus.indexing.service;

import org.labubus.indexing.indexer.InvertedIndexWriter;
import org.labubus.indexing.model.BookMetadata;
import org.labubus.indexing.repository.MetadataRepository;
import org.labubus.indexing.storage.DatalakeReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

public class IndexingService {
	private static final Logger logger = LoggerFactory.getLogger(IndexingService.class);

	private final DatalakeReader datalakeReader;
	private final MetadataExtractor metadataExtractor;
	private final MetadataRepository metadataRepository;
	private final InvertedIndexBuilder indexBuilder;
	private final InvertedIndexWriter indexWriter;

	public IndexingService(
			DatalakeReader datalakeReader,
			MetadataExtractor metadataExtractor,
			MetadataRepository metadataRepository,
			InvertedIndexBuilder indexBuilder,
			InvertedIndexWriter indexWriter) {
		this.datalakeReader = datalakeReader;
		this.metadataExtractor = metadataExtractor;
		this.metadataRepository = metadataRepository;
		this.indexBuilder = indexBuilder;
		this.indexWriter = indexWriter;
	}

	/**
	 * Index a single book: extract metadata, save to DB, build inverted index
	 */
	public void indexBook(int bookId) throws IOException, SQLException {
		logger.info("Starting indexing for book {}", bookId);

		if (!datalakeReader.bookExists(bookId)) {
			throw new IOException("Book " + bookId + " not found in datalake");
		}

		String header = datalakeReader.readBookHeader(bookId);
		String body = datalakeReader.readBookBody(bookId);

		String path = "datalake/book_" + bookId; // Simplified path
		BookMetadata metadata = metadataExtractor.extractMetadata(bookId, header, path);

		metadataRepository.save(metadata);
		logger.info("Saved metadata for book {}: {}", bookId, metadata.title());

		indexBuilder.indexBook(bookId, body);
		logger.info("Indexed words for book {}", bookId);

		indexWriter.save();
		logger.info("Successfully indexed book {}", bookId);
	}

	/**
	 * Rebuild entire index from all books in datalake
	 */
	public int rebuildIndex() throws IOException, SQLException {
		logger.info("Starting full index rebuild...");

		indexWriter.clear();

		List<Integer> bookIds = datalakeReader.getDownloadedBooks();
		logger.info("Found {} books to index", bookIds.size());

		int successCount = 0;
		int failureCount = 0;

		for (int bookId : bookIds) {
			try {
				indexBook(bookId);
				successCount++;
			} catch (Exception e) {
				logger.error("Failed to index book {}: {}", bookId, e.getMessage());
				failureCount++;
			}
		}

		logger.info("Index rebuild complete: {} succeeded, {} failed", successCount, failureCount);
		return successCount;
	}

	/**
	 * Get indexing statistics
	 */
	public IndexStats getStats() throws SQLException {
		int booksInDatabase = metadataRepository.count();
		double indexSize = indexWriter.getSizeInMB();
		int uniqueWords = indexWriter.getIndex().size();

		return new IndexStats(booksInDatabase, uniqueWords, indexSize);
	}

	/**
	 * Simple stats container
	 */
	public record IndexStats(int booksIndexed, int uniqueWords, double indexSizeMB) {}
}