package org.labubus.search.service;

import org.labubus.search.indexer.InvertedIndexReader;
import org.labubus.search.model.BookMetadata;
import org.labubus.search.model.SearchResult;
import org.labubus.search.repository.MetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class SearchService {
	private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

	private final MetadataRepository metadataRepository;
	private final InvertedIndexReader indexReader;
	private final int maxResults;

	public SearchService(MetadataRepository metadataRepository, InvertedIndexReader indexReader, int maxResults) {
		this.metadataRepository = metadataRepository;
		this.indexReader = indexReader;
		this.maxResults = maxResults;
	}

	/**
	 * Search for books by keyword query
	 */
	public List<SearchResult> search(String query, String author, String language, Integer year, Integer limit) throws SQLException {
		logger.info("Search query: '{}', author: '{}', language: '{}', year: {}, limit: {}",
				query, author, language, year, limit);

		int resultLimit = (limit != null && limit > 0) ? Math.min(limit, maxResults) : maxResults;

		Set<Integer> matchingBookIds = searchIndex(query);
		logger.debug("Found {} books matching query in index", matchingBookIds.size());

		if (matchingBookIds.isEmpty()) {
			return Collections.emptyList();
		}

		List<BookMetadata> matchingBooks = metadataRepository.findByIds(new ArrayList<>(matchingBookIds));
		logger.debug("Retrieved metadata for {} books", matchingBooks.size());

		List<BookMetadata> filteredBooks = applyFilters(matchingBooks, author, language, year);
		logger.debug("After filtering: {} books", filteredBooks.size());

		List<SearchResult> results = rankResults(filteredBooks, query);

		if (results.size() > resultLimit) {
			results = results.subList(0, resultLimit);
		}

		logger.info("Returning {} search results", results.size());
		return results;
	}

	/**
	 * Search inverted index for books containing query words
	 */
	private Set<Integer> searchIndex(String query) {
		if (query == null || query.trim().isEmpty()) {
			logger.warn("Empty search query");
			return Collections.emptySet();
		}

		String[] words = query.toLowerCase().trim().split("\\s+");

		List<Set<Integer>> wordResults = new ArrayList<>();
		for (String word : words) {
			Set<Integer> bookIds = indexReader.search(word);
			if (!bookIds.isEmpty()) {
				wordResults.add(bookIds);
			}
		}

		if (wordResults.isEmpty()) {
			return Collections.emptySet();
		}

		Set<Integer> allResults = new HashSet<>();
		for (Set<Integer> result : wordResults) {
			allResults.addAll(result);
		}

		return allResults;
	}

	/**
	 * Apply filters to book list
	 */
	private List<BookMetadata> applyFilters(List<BookMetadata> books, String author, String language, Integer year) {
		return books.stream()
				.filter(book -> author == null || book.author().toLowerCase().contains(author.toLowerCase()))
				.filter(book -> language == null || book.language().equalsIgnoreCase(language))
				.filter(book -> year == null || (book.year() != null && book.year().equals(year)))
				.collect(Collectors.toList());
	}

	/**
	 * Rank results by relevance score
	 */
	private List<SearchResult> rankResults(List<BookMetadata> books, String query) {
		String[] queryWords = query.toLowerCase().trim().split("\\s+");

		return books.stream()
				.map(book -> {
					int score = calculateScore(book, queryWords);
					return SearchResult.fromMetadata(book, score);
				})
				.sorted(Comparator.comparingInt(SearchResult::score).reversed())
				.collect(Collectors.toList());
	}

	/**
	 * Calculate relevance score for a book
	 */
	private int calculateScore(BookMetadata book, String[] queryWords) {
		int score = 0;
		String titleLower = book.title().toLowerCase();
		String authorLower = book.author().toLowerCase();

		for (String word : queryWords) {
			if (titleLower.contains(word)) {
				score += 10;
			}

			if (authorLower.contains(word)) {
				score += 5;
			}

			Set<Integer> bookIds = indexReader.search(word);
			if (bookIds.contains(book.bookId())) {
				score += 1;
			}
		}

		return score;
	}

	/**
	 * Get all books (no search)
	 */
	public List<SearchResult> getAllBooks(Integer limit) throws SQLException {
		int resultLimit = (limit != null && limit > 0) ? Math.min(limit, maxResults) : maxResults;

		List<BookMetadata> books = metadataRepository.findAll();

		if (books.size() > resultLimit) {
			books = books.subList(0, resultLimit);
		}

		return books.stream()
				.map(book -> SearchResult.fromMetadata(book, 0))
				.collect(Collectors.toList());
	}

	/**
	 * Get search statistics
	 */
	public SearchStats getStats() throws SQLException {
		int totalBooks = metadataRepository.count();
		InvertedIndexReader.IndexStats indexStats = indexReader.getStats();

		return new SearchStats(
				totalBooks,
				indexStats.uniqueWords(),
				indexStats.totalMappings(),
				indexStats.sizeInMB(),
				indexReader.isLoaded()
		);
	}

	public record SearchStats(
			int totalBooks,
			int uniqueWords,
			int totalMappings,
			double indexSizeMB,
			boolean indexLoaded
	) {}
}