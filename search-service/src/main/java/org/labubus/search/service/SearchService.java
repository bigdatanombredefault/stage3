package org.labubus.search.service;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.labubus.model.BookMetadata;
import org.labubus.search.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.multimap.MultiMap;

public class SearchService {
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

    private final HazelcastInstance hazelcast;
    private final int maxResults;

    private final String metadataMapName;
    private final String invertedIndexName;

    public SearchService(HazelcastInstance hazelcast, int maxResults, String metadataMapName, String invertedIndexName) {
        if (hazelcast == null) {
            throw new IllegalArgumentException("hazelcast cannot be null");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0");
        }
        this.hazelcast = hazelcast;
        this.maxResults = maxResults;
        this.metadataMapName = requireNonBlank(metadataMapName, "metadataMapName");
        this.invertedIndexName = requireNonBlank(invertedIndexName, "invertedIndexName");
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be null/blank");
        }
        return value.trim();
    }

    public List<SearchResult> search(String query, String author, String language, Integer year, Integer limit) {
        logger.info("Search query: '{}', author: '{}', language: '{}', year: {}, limit: {}",
                query, author, language, year, limit);

        // 1. Find all matching book IDs from the Hazelcast index
        Set<Integer> matchingBookIds = searchIndex(query);
        if (matchingBookIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Efficiently retrieve all metadata for those IDs from the Hazelcast map
        IMap<Integer, BookMetadata> metadataMap = hazelcast.getMap(metadataMapName);
        List<BookMetadata> matchingBooks = metadataMap.getAll(matchingBookIds).values().stream().toList();
        logger.debug("Retrieved metadata for {} books from Hazelcast", matchingBooks.size());

        // 3. Filter and rank the results (this logic is largely the same)
        List<BookMetadata> filteredBooks = applyFilters(matchingBooks, author, language, year);
        List<SearchResult> results = rankResults(filteredBooks, query);

        int resultLimit = (limit != null && limit > 0) ? Math.min(limit, maxResults) : maxResults;
        return results.stream().limit(resultLimit).collect(Collectors.toList());
    }

    private Set<Integer> searchIndex(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptySet();
        }

        MultiMap<String, Integer> invertedIndex = hazelcast.getMultiMap(invertedIndexName);
        String[] words = query.toLowerCase().trim().split("\\s+");
        Set<Integer> allMatchingIds = new HashSet<>();
        for (String word : words) {
            // .get() on a MultiMap returns a Collection of values.
            allMatchingIds.addAll(invertedIndex.get(word));
        }
        return allMatchingIds;
    }

    private List<BookMetadata> applyFilters(List<BookMetadata> books, String author, String language, Integer year) {
        return books.stream()
                .filter(book -> author == null || book.author().toLowerCase().contains(author.toLowerCase()))
                .filter(book -> language == null || book.language().equalsIgnoreCase(language))
                .filter(book -> year == null || (book.year() != null && book.year().equals(year)))
                .collect(Collectors.toList());
    }

    private List<SearchResult> rankResults(List<BookMetadata> books, String query) {
        String[] queryWords = query.toLowerCase().trim().split("\\s+");
        // Get the index once to pass into the scoring function for efficiency
        MultiMap<String, Integer> invertedIndex = hazelcast.getMultiMap(invertedIndexName);

        return books.stream()
                .map(book -> {
                    int score = calculateScore(book, queryWords, invertedIndex);
                    return SearchResult.fromMetadata(book, score);
                })
                .sorted(Comparator.comparingInt(SearchResult::score).reversed())
                .collect(Collectors.toList());
    }

    private int calculateScore(BookMetadata book, String[] queryWords, MultiMap<String, Integer> invertedIndex) {
        int score = 0;
        String titleLower = book.title().toLowerCase();
        String authorLower = book.author().toLowerCase();

        for (String word : queryWords) {
            if (titleLower.contains(word)) score += 10;
            if (authorLower.contains(word)) score += 5;

            // Check if the word's collection in the index contains this book's ID.
            if (invertedIndex.containsEntry(word, book.bookId())) {
                score += 1;
            }
        }
        return score;
    }

    public List<SearchResult> getAllBooks(Integer limit) {
        int resultLimit = (limit != null && limit > 0) ? Math.min(limit, maxResults) : maxResults;
        IMap<Integer, BookMetadata> metadataMap = hazelcast.getMap(metadataMapName);

        // .values() gets all metadata objects from the distributed map
        return metadataMap.values().stream()
                .limit(resultLimit)
                .map(book -> SearchResult.fromMetadata(book, 0))
                .collect(Collectors.toList());
    }

    /**
     * Get search statistics from Hazelcast.
     */
    public SearchStats getStats() {
        int totalBooks = hazelcast.getMap(metadataMapName).size();
        int uniqueWords = hazelcast.getMultiMap(invertedIndexName).keySet().size();
        return new SearchStats(totalBooks, uniqueWords);
    }

    public record SearchStats(int totalBooks, int uniqueWords) {}
}