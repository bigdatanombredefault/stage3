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
    private static final String METADATA_MAP_NAME = "book-metadata";
    private static final String INVERTED_INDEX_NAME = "inverted-index";
    private final HazelcastInstance hazelcast;
    private final DatalakeReader datalakeReader;
    private final MetadataExtractor metadataExtractor;

    public IndexingService(HazelcastInstance hazelcastInstance) {
        this(hazelcastInstance, "../datalake");
    }

    public IndexingService(HazelcastInstance hazelcastInstance, String datalakePath) {
        this.hazelcast = hazelcastInstance;
        String effectivePath = (datalakePath == null || datalakePath.isBlank()) ? "../datalake" : datalakePath;
        this.datalakeReader = new DatalakeReader(effectivePath);
        this.metadataExtractor = new MetadataExtractor();
    }

    /**
     * Indexes a single book into Hazelcast, but only if it has not been indexed before.
     * @param bookId The ID of the book to index.
     * @throws IOException if reading from the datalake fails.
     */
    public void indexBook(int bookId) throws IOException {
        IMap<Integer, BookMetadata> metadataMap = hazelcast.getMap(METADATA_MAP_NAME);

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
        MultiMap<String, Integer> invertedIndex = hazelcast.getMultiMap(INVERTED_INDEX_NAME);
        int shardCount = 20;

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

        hazelcast.getMap(METADATA_MAP_NAME).clear();
        hazelcast.getMultiMap(INVERTED_INDEX_NAME).clear();
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
        int booksIndexed = hazelcast.getMap(METADATA_MAP_NAME).size();
        int uniqueWords = hazelcast.getMultiMap(INVERTED_INDEX_NAME).keySet().size();
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