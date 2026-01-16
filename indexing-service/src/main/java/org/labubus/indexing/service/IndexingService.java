package org.labubus.indexing.service;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    private static final int MIN_TERM_LENGTH = 3;
    private static final Set<String> STOPWORDS = Set.of(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from", "has", "have", "he",
        "her", "hers", "him", "his", "i", "in", "is", "it", "its", "me", "my", "not", "of", "on",
        "or", "our", "she", "so", "that", "the", "their", "them", "they", "this", "to", "was",
        "we", "were", "with", "you", "your"
    );

    private final HazelcastInstance hazelcast;
    private final DatalakeReader datalakeReader;
    private final MetadataExtractor metadataExtractor;

    private final String metadataMapName;
    private final String invertedIndexName;

    public IndexingService(
        HazelcastInstance hazelcastInstance,
        String datalakePath,
        String trackingFilename,
        String metadataMapName,
        String invertedIndexName
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

        this.hazelcast = hazelcastInstance;
        this.datalakeReader = new DatalakeReader(datalakePath.trim(), trackingFilename.trim());

        this.metadataMapName = metadataMapName.trim();
        this.invertedIndexName = invertedIndexName.trim();

        this.metadataExtractor = new MetadataExtractor();
    }

    /**
     * Indexes a single book into Hazelcast, but only if it has not been indexed before.
     * @param bookId The ID of the book to index.
     * @throws IOException if reading from the datalake fails.
     */
    public void indexBook(int bookId) throws IOException {
        if (isAlreadyIndexed(bookId)) {
            return;
        }
        logger.info("Starting indexing for book {} into Hazelcast", bookId);
        ensureBookExists(bookId);

        BookData book = readBook(bookId);
        storeMetadata(bookId, book);
        indexWords(bookId, book.body());
    }

    private boolean isAlreadyIndexed(int bookId) {
        IMap<Integer, BookMetadata> metadataMap = hazelcast.getMap(metadataMapName);
        if (!metadataMap.containsKey(bookId)) {
            return false;
        }
        logger.warn("Book {} has already been indexed. Skipping to ensure idempotency.", bookId);
        return true;
    }

    private void ensureBookExists(int bookId) throws IOException {
        if (datalakeReader.bookExists(bookId)) {
            return;
        }
        throw new IOException("Book " + bookId + " not found in datalake");
    }

    private BookData readBook(int bookId) throws IOException {
        String header = datalakeReader.readBookHeader(bookId);
        String body = datalakeReader.readBookBody(bookId);
        String path = datalakeReader.getBookDirectoryPath(bookId);
        return new BookData(header, body, path);
    }

    private void storeMetadata(int bookId, BookData book) {
        IMap<Integer, BookMetadata> metadataMap = hazelcast.getMap(metadataMapName);
        BookMetadata metadata = metadataExtractor.extractMetadata(bookId, book.header(), book.path());
        metadataMap.put(metadata.bookId(), metadata);
        logger.info("Saved metadata to Hazelcast for book {}: {}", bookId, metadata.title());
    }

    private void indexWords(int bookId, String body) {
        Set<String> words = extractWords(body);
        MultiMap<String, String> invertedIndex = hazelcast.getMultiMap(invertedIndexName);
        String docId = String.valueOf(bookId);
        words.forEach(word -> indexWord(invertedIndex, word, docId));
        logger.info("Indexed {} unique words for book {} into Hazelcast", words.size(), bookId);
    }

    private void indexWord(MultiMap<String, String> invertedIndex, String word, String docId) {
        if (word == null || word.isBlank()) {
            return;
        }
        String lockName = lockNameForWord(word);
        FencedLock lock = hazelcast.getCPSubsystem().getLock(lockName);
        lock.lock();
        try {
            invertedIndex.put(word, docId);
        } finally {
            lock.unlock();
        }
    }

    private String lockNameForWord(String word) {
        String normalized = word.trim().toLowerCase();
        return "lock:inverted-index:" + normalized;
    }

    /**
     * Clears the index and re-indexes all books from the datalake.
     */
    public int rebuildIndex() throws IOException {
        logger.info("Starting full index rebuild in Hazelcast...");
        clearIndex();
        List<Integer> bookIds = datalakeReader.getDownloadedBooks();
        logger.info("Found {} books to index", bookIds.size());
        int successCount = indexAll(bookIds);
        logger.info("Index rebuild complete: {} books succeeded.", successCount);
        return successCount;
    }

    /**
     * Returns {@code true} when the inverted index is empty in Hazelcast.
     */
    public boolean isInvertedIndexEmpty() {
        return hazelcast.getMultiMap(invertedIndexName).keySet().isEmpty();
    }

    /**
     * Clears the index and re-indexes all books discovered by scanning the local datalake files.
     */
    public int rebuildIndexFromLocalFiles() throws IOException {
        logger.info("Starting disaster-recovery index rebuild from local datalake files...");
        clearIndex();
        List<Integer> bookIds = datalakeReader.scanBookIdsFromFiles();
        logger.info("Discovered {} books from local filesystem scan", bookIds.size());
        int successCount = indexAll(bookIds);
        logger.info("Disaster-recovery rebuild complete: {} books succeeded.", successCount);
        return successCount;
    }

    private void clearIndex() {
        hazelcast.getMap(metadataMapName).clear();
        hazelcast.getMultiMap(invertedIndexName).clear();
        logger.info("Cleared existing data from Hazelcast maps.");
    }

    private int indexAll(List<Integer> bookIds) {
        int successCount = 0;
        for (int bookId : bookIds) {
            successCount += indexOneIgnoringFailures(bookId);
        }
        return successCount;
    }

    private int indexOneIgnoringFailures(int bookId) {
        try {
            indexBook(bookId);
            return 1;
        } catch (IOException e) {
            logger.error("Failed to index book {}: {}", bookId, e.getMessage(), e);
            return 0;
        }
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
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] tokens = tokenize(text);
        return Arrays.stream(tokens)
            .map(String::trim)
            .filter(t -> !t.isEmpty())
            .filter(t -> t.length() >= MIN_TERM_LENGTH)
            .filter(t -> !STOPWORDS.contains(t))
            .collect(Collectors.toCollection(HashSet::new));
    }

    private String[] tokenize(String text) {
        return text.toLowerCase().split("[\\s\\p{Punct}]+");
    }

    private record BookData(String header, String body, String path) {}

    /**
     * A simple record to hold index statistics.
     */
    public record IndexStats(int booksIndexed, int uniqueWords) {}
}