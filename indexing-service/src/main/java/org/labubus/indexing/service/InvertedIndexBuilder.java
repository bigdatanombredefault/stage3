package org.labubus.indexing.service;

import org.labubus.indexing.indexer.InvertedIndexWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class InvertedIndexBuilder {
	private static final Logger logger = LoggerFactory.getLogger(InvertedIndexBuilder.class);

	private final InvertedIndexWriter indexWriter;
	private final int minWordLength;
	private final int maxWordLength;
	private final Set<String> stopWords;

	private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z]+");

	public InvertedIndexBuilder(InvertedIndexWriter indexWriter, int minWordLength, int maxWordLength, Set<String> stopWords) {
		this.indexWriter = indexWriter;
		this.minWordLength = minWordLength;
		this.maxWordLength = maxWordLength;
		this.stopWords = stopWords;
	}

	/**
	 * Index a book's body text
	 */
	public void indexBook(int bookId, String bodyText) {
		Set<String> uniqueWords = extractWords(bodyText);

		for (String word : uniqueWords) {
			indexWriter.addWord(word, bookId);
		}

		logger.debug("Indexed book {} with {} unique words", bookId, uniqueWords.size());
	}

	/**
	 * Extract unique words from text
	 */
	private Set<String> extractWords(String text) {
		Set<String> words = new HashSet<>();

		String[] tokens = text.toLowerCase().split("\\s+");

		for (String token : tokens) {
			var matcher = WORD_PATTERN.matcher(token);
			while (matcher.find()) {
				String word = matcher.group();

				if (isValidWord(word)) {
					words.add(word);
				}
			}
		}

		return words;
	}

	/**
	 * Check if a word is valid for indexing
	 */
	private boolean isValidWord(String word) {
		if (word.length() < minWordLength || word.length() > maxWordLength) {
			return false;
		}

		return !stopWords.contains(word);
	}

	/**
	 * Parse stop words from comma-separated string
	 */
	public static Set<String> parseStopWords(String stopWordsStr) {
		if (stopWordsStr == null || stopWordsStr.trim().isEmpty()) {
			return new HashSet<>();
		}

		String[] words = stopWordsStr.split(",");
		Set<String> stopWords = new HashSet<>();

		for (String word : words) {
			String cleaned = word.trim().toLowerCase();
			if (!cleaned.isEmpty()) {
				stopWords.add(cleaned);
			}
		}

		return stopWords;
	}
}