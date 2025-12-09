package org.labubus.indexing.service;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.multimap.MultiMap;
import org.labubus.core.model.BookMetadata;
import org.labubus.indexing.storage.DatalakeReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class IndexingService {
    private static final Logger logger = LoggerFactory.getLogger(IndexingService.class);
    private static final String METADATA_MAP_NAME = "book-metadata";
    private static final String INVERTED_INDEX_NAME = "inverted-index";
    private final HazelcastInstance hazelcast;
    private final DatalakeReader datalakeReader;
    private final MetadataExtractor metadataExtractor;

    public IndexingService(HazelcastInstance hazelcastInstance) {
        this.hazelcast = hazelcastInstance;
        this.datalakeReader = new DatalakeReader("../datalake");
        this.metadataExtractor = new MetadataExtractor();
    }

    public void indexBook(int bookId) throws IOException {
        logger.info("Starting indexing for book {} into Hazelcast", bookId);

        if (!datalakeReader.bookExists(bookId)) {
            throw new IOException("Book " + bookId + " not found in datalake");
        }

        String header = datalakeReader.readBookHeader(bookId);
        String body = datalakeReader.readBookBody(bookId);
        String path = datalakeReader.getBookDirectoryPath(bookId);

        BookMetadata metadata = metadataExtractor.extractMetadata(bookId, header, path);
        IMap<Integer, BookMetadata> metadataMap = hazelcast.getMap(METADATA_MAP_NAME);
        metadataMap.put(metadata.bookId(), metadata);
        logger.info("Saved metadata to Hazelcast for book {}: {}", bookId, metadata.title());

        Set<String> words = extractWords(body);
        MultiMap<String, Integer> invertedIndex = hazelcast.getMultiMap(INVERTED_INDEX_NAME);
        for (String word : words) {
            invertedIndex.put(word, bookId);
        }
        logger.info("Indexed {} unique words for book {} into Hazelcast", words.size(), bookId);
    }

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

    public IndexStats getStats() {
        int booksIndexed = hazelcast.getMap(METADATA_MAP_NAME).size();
        int uniqueWords = hazelcast.getMultiMap(INVERTED_INDEX_NAME).keySet().size();
        return new IndexStats(booksIndexed, uniqueWords);
    }

    private Set<String> extractWords(String text) {
        String[] tokens = text.toLowerCase().split("[\\s\\p{Punct}]+");
        return new HashSet<>(Arrays.asList(tokens));
    }

    public record IndexStats(int booksIndexed, int uniqueWords) {}
}