package org.labubus.indexing.service;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.labubus.indexing.storage.DatalakeReader;
import org.labubus.model.BookMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;
import com.hazelcast.map.IMap;
import com.hazelcast.multimap.MultiMap;

public class IndexingService {
    private static final Logger logger = LoggerFactory.getLogger(IndexingService.class);

    private final HazelcastInstance hazelcast;
    private final DatalakeReader datalakeReader;
    private final MetadataExtractor metadataExtractor;

    private final String metadataMapName;
    private final String invertedIndexName;
    private final int shardCount;

    public IndexingService(
        HazelcastInstance hazelcastInstance,
        String datalakePath,
        String trackingFilename,
        String metadataMapName,
        String invertedIndexName,
        int shardCount
    ) {
        if (hazelcastInstance == null) {
            throw new IllegalArgumentException("hazelcastInstance cannot be null");
        }
        if (datalakePath == null || datalakePath.isBlank()) {
            throw new IllegalArgumentException("datalakePath cannot be null/blank");
        }
        if (trackingFilename == null || trackingFilename.isBlank()) {
            throw new IllegalArgumentException("trackingFilename cannot be null/blank");
        }
        if (metadataMapName == null || metadataMapName.isBlank()) {
            throw new IllegalArgumentException("metadataMapName cannot be null/blank");
        }
        if (invertedIndexName == null || invertedIndexName.isBlank()) {
            throw new IllegalArgumentException("invertedIndexName cannot be null/blank");
        }
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be > 0");
        }

        this.hazelcast = hazelcastInstance;
        this.datalakeReader = new DatalakeReader(datalakePath.trim(), trackingFilename.trim());

        this.metadataMapName = metadataMapName.trim();
        this.invertedIndexName = invertedIndexName.trim();
        this.shardCount = shardCount;

        this.metadataExtractor = new MetadataExtractor();
    }

    /**
     * Indexes a single book into Hazelcast, but only if it has not been indexed before.
     * @param bookId The ID of the book to index.
     * @throws IOException if reading from the datalake fails.
     */
    public void indexBook(int bookId) throws IOException {
        IMap<Integer, BookMetadata> metadataMap = hazelcast.getMap(metadataMapName);

        if (metadataMap.containsKey(bookId)) {
            logger.warn("Book {} has already been indexed. Skipping to ensure idempotency.", bookId);
            return;
        }

        logger.info("Starting indexing for book {} into Hazelcast", bookId);

        if (!datalakeReader.bookExists(bookId)) {
            throw new IOException("Book " + bookId + " not found in datalake");
        }

        String header = datalakeReader.readBookHeader(bookId);
        String body = datalakeReader.readBookBody(bookId);
        String path = datalakeReader.getBookDirectoryPath(bookId);

        BookMetadata metadata = metadataExtractor.extractMetadata(bookId, header, path);
        metadataMap.put(metadata.bookId(), metadata);
        logger.info("Saved metadata to Hazelcast for book {}: {}", bookId, metadata.title());

        Set<String> words = extractWords(body);
        MultiMap<String, Integer> invertedIndex = hazelcast.getMultiMap(invertedIndexName);

        for (String word : words) {
            int shardId = Math.abs(word.hashCode() % shardCount);

            FencedLock lock = hazelcast.getCPSubsystem().getLock("lock:shard:" + shardId);
            lock.lock();
            try {
                invertedIndex.put(word, bookId);
            } finally {
                lock.unlock();
            }
        }
        logger.info("Indexed {} unique words for book {} into Hazelcast", words.size(), bookId);
    }

    /**
     * Clears the index and re-indexes all books from the datalake.
     */
    public int rebuildIndex() throws IOException {
        logger.info("Starting full index rebuild in Hazelcast...");

        hazelcast.getMap(metadataMapName).clear();
        hazelcast.getMultiMap(invertedIndexName).clear();
        logger.info("Cleared existing data from Hazelcast maps.");

        List<Integer> bookIds = datalakeReader.getDownloadedBooks();
        logger.info("Found {} books to index", bookIds.size());

        int successCount = 0;
        for (int bookId : bookIds) {
            try {
                indexBook(bookId);
                successCount++;
            } catch (IOException e) {
                logger.error("Failed to index book {}: {}", bookId, e.getMessage(), e);
            }
        }

        logger.info("Index rebuild complete: {} books succeeded.", successCount);
        return successCount;
    }

    /**
     * Gets statistics from the Hazelcast grid.
     */
    public IndexStats getStats() {
        int booksIndexed = hazelcast.getMap(metadataMapName).size();
        int uniqueWords = hazelcast.getMultiMap(invertedIndexName).keySet().size();
        return new IndexStats(booksIndexed, uniqueWords);
    }

    /**
     * A simple helper method to tokenize and clean text.
     */
    private Set<String> extractWords(String text) {
        // This simple tokenizer splits by whitespace and punctuation.
        String[] tokens = text.toLowerCase().split("[\\s\\p{Punct}]+");
        return new HashSet<>(Arrays.asList(tokens));
    }

    /**
     * A simple record to hold index statistics.
     */
    public record IndexStats(int booksIndexed, int uniqueWords) {}
}