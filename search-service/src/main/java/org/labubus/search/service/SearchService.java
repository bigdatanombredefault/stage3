package org.labubus.search.service;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.labubus.model.BookMetadata;
import org.labubus.search.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.multimap.MultiMap;

/**
 * Provides full-text search over the distributed inverted index stored in Hazelcast.
 */
public class SearchService {
    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

    private final HazelcastInstance hazelcast;
    private final int maxResults;

    private final String metadataMapName;
    private final String invertedIndexName;

    private final TermNearCache termNearCache;

    public SearchService(HazelcastInstance hazelcast, int maxResults, String metadataMapName, String invertedIndexName) {
        this(hazelcast, maxResults, metadataMapName, invertedIndexName, 10_000, 5_000);
    }

    public SearchService(
        HazelcastInstance hazelcast,
        int maxResults,
        String metadataMapName,
        String invertedIndexName,
        int nearCacheMaxEntries,
        long nearCacheTtlMs
    ) {
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
        this.termNearCache = new TermNearCache(Math.max(1, nearCacheMaxEntries), Math.max(0, nearCacheTtlMs));
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be null/blank");
        }
        return value.trim();
    }

    /**
     * Searches indexed books.
     *
     * @param query free-text query (tokenized by whitespace)
     * @param author optional author filter
     * @param language optional language filter
     * @param year optional year filter
     * @param limit optional max results (capped by {@code maxResults})
     * @return ranked results
     */
    public List<SearchResult> search(String query, String author, String language, Integer year, Integer limit) {
        logSearch(query, author, language, year, limit);
        return doSearch(new SearchQuery(query, author, language, year, limit));
    }

    private void logSearch(String query, String author, String language, Integer year, Integer limit) {
        logger.info("Search query: '{}', author: '{}', language: '{}', year: {}, limit: {}", query, author, language, year, limit);
    }

    private List<SearchResult> doSearch(SearchQuery query) {
        Set<Integer> ids = searchIndex(query.query());
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<BookMetadata> books = loadMetadata(ids);
        List<BookMetadata> filtered = applyFilters(books, query.author(), query.language(), query.year());
        List<SearchResult> ranked = rankResults(filtered, query.query());
        return limit(ranked, query.limit());
    }

    private List<BookMetadata> loadMetadata(Set<Integer> bookIds) {
        IMap<Integer, BookMetadata> metadataMap = hazelcast.getMap(metadataMapName);
        List<BookMetadata> books = metadataMap.getAll(bookIds).values().stream().toList();
        logger.debug("Retrieved metadata for {} books from Hazelcast", books.size());
        return books;
    }

    private List<SearchResult> limit(List<SearchResult> results, Integer limit) {
        int resultLimit = computeResultLimit(limit);
        return results.stream().limit(resultLimit).collect(Collectors.toList());
    }

    private int computeResultLimit(Integer limit) {
        if (limit != null && limit > 0) {
            return Math.min(limit, maxResults);
        }
        return maxResults;
    }

    private Set<Integer> searchIndex(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptySet();
        }

        String[] words = query.toLowerCase().trim().split("\\s+");
        Set<Integer> allMatchingIds = new HashSet<>();
        for (String word : words) {
            for (String docId : getDocIdsForTerm(word)) {
                try {
                    allMatchingIds.add(Integer.valueOf(docId));
                } catch (NumberFormatException ignored) {
                    // ignore malformed ids
                }
            }
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
        String[] queryWords = tokenizeQuery(query);

        Map<String, Set<String>> docsByWord = new LinkedHashMap<>();
        for (String word : queryWords) {
            docsByWord.put(word, getDocIdsForTerm(word));
        }

        return books.stream()
            .map(book -> toResult(book, queryWords, docsByWord))
            .sorted(Comparator.comparingInt(SearchResult::score).reversed())
            .collect(Collectors.toList());
    }

    private String[] tokenizeQuery(String query) {
        return query.toLowerCase().trim().split("\\s+");
    }

    private SearchResult toResult(BookMetadata book, String[] queryWords, Map<String, Set<String>> docsByWord) {
        int score = calculateScore(book, queryWords, docsByWord);
        return SearchResult.fromMetadata(book, score);
    }

    private int calculateScore(BookMetadata book, String[] queryWords, Map<String, Set<String>> docsByWord) {
        int score = 0;
        String titleLower = book.title().toLowerCase();
        String authorLower = book.author().toLowerCase();
        String docId = String.valueOf(book.bookId());

        for (String word : queryWords) {
            if (titleLower.contains(word)) score += 10;
            if (authorLower.contains(word)) score += 5;

            Set<String> docsForWord = docsByWord.get(word);
            if (docsForWord != null && docsForWord.contains(docId)) {
                score += 1;
            }
        }
        return score;
    }

    private Set<String> getDocIdsForTerm(String term) {
        String normalized = (term == null) ? "" : term.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return Collections.emptySet();
        }

        return termNearCache.get(normalized, () -> {
            MultiMap<String, String> invertedIndex = hazelcast.getMultiMap(invertedIndexName);
            return Set.copyOf(invertedIndex.get(normalized));
        });
    }

    /**
     * Lists all books currently stored in the metadata map.
     *
     * @param limit optional max results (capped by {@code maxResults})
     * @return results with score set to {@code 0}
     */
    public List<SearchResult> getAllBooks(Integer limit) {
        int resultLimit = computeResultLimit(limit);
        IMap<Integer, BookMetadata> metadataMap = hazelcast.getMap(metadataMapName);
        return metadataMap.values().stream()
            .limit(resultLimit)
            .map(book -> SearchResult.fromMetadata(book, 0))
            .collect(Collectors.toList());
    }

    /**
     * Gets search statistics from Hazelcast.
     *
     * @return total number of books and unique indexed words
     */
    public SearchStats getStats() {
        int totalBooks = hazelcast.getMap(metadataMapName).size();
        int uniqueWords = hazelcast.getMultiMap(invertedIndexName).keySet().size();
        return new SearchStats(totalBooks, uniqueWords);
    }

    /** Search statistics snapshot. */
    public record SearchStats(int totalBooks, int uniqueWords) {}

    private record SearchQuery(String query, String author, String language, Integer year, Integer limit) {}

    private static final class TermNearCache {
        private final long ttlMs;
        private final Map<String, CacheEntry> lru;

        private TermNearCache(int maxEntries, long ttlMs) {
            this.ttlMs = ttlMs;
            this.lru = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > maxEntries;
                }
            });
        }

        private Set<String> get(String key, Loader loader) {
            long now = System.currentTimeMillis();
            CacheEntry entry = lru.get(key);
            if (entry != null && !entry.isExpired(now, ttlMs)) {
                return entry.value;
            }
            Set<String> loaded = loader.load();
            lru.put(key, new CacheEntry(loaded, now));
            return loaded;
        }

        private interface Loader {
            Set<String> load();
        }

        private record CacheEntry(Set<String> value, long createdAtMs) {
            private boolean isExpired(long nowMs, long ttlMs) {
                return ttlMs > 0 && (nowMs - createdAtMs) > ttlMs;
            }
        }
    }
}