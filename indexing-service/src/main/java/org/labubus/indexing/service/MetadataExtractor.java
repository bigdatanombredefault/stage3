package org.labubus.indexing.service;

import org.labubus.model.BookMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetadataExtractor {
	private static final Logger logger = LoggerFactory.getLogger(MetadataExtractor.class);

	private static final Pattern TITLE_PATTERN = Pattern.compile("Title:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern AUTHOR_PATTERN = Pattern.compile("Author:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern LANGUAGE_PATTERN = Pattern.compile("Language:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern RELEASE_DATE_PATTERN = Pattern.compile("Release Date:\\s*.*?(\\d{4})", Pattern.CASE_INSENSITIVE);

	/**
     * Extract metadata from book header
     */
	public org.labubus.model.BookMetadata extractMetadata(int bookId, String header, String path) {
		String title = extractTitle(header);
		String author = extractAuthor(header);
		String language = extractLanguage(header);
		Integer year = extractYear(header);

		title = cleanString(title);
		author = cleanString(author);
		language = cleanString(language);

		if (title == null || title.isEmpty()) {
			title = "Unknown Title (Book " + bookId + ")";
		}
		if (author == null || author.isEmpty()) {
			author = "Unknown Author";
		}
		if (language == null || language.isEmpty()) {
			language = "en";
		}

		BookMetadata metadata = new BookMetadata(bookId, title, author, language, year, path);
		logger.debug("Extracted metadata: {}", metadata);

		return metadata;
	}

	private String extractTitle(String header) {
		Matcher matcher = TITLE_PATTERN.matcher(header);
		if (matcher.find()) {
			return matcher.group(1).trim();
		}
		return null;
	}

	private String extractAuthor(String header) {
		Matcher matcher = AUTHOR_PATTERN.matcher(header);
		if (matcher.find()) {
			return matcher.group(1).trim();
		}
		return null;
	}

	private String extractLanguage(String header) {
		Matcher matcher = LANGUAGE_PATTERN.matcher(header);
		if (matcher.find()) {
			return matcher.group(1).trim().toLowerCase();
		}
		return null;
	}

	private Integer extractYear(String header) {
		Matcher matcher = RELEASE_DATE_PATTERN.matcher(header);
		if (matcher.find()) {
			try {
				return Integer.parseInt(matcher.group(1));
			} catch (NumberFormatException e) {
				logger.warn("Failed to parse year: {}", matcher.group(1));
			}
		}
		return null;
	}

	/**
	 * Clean and normalize extracted string
	 */
	private String cleanString(String str) {
		if (str == null) {
			return null;
		}

		str = str.replaceAll("\\s+", " ").trim();

		if (str.length() > 300) {
			str = str.substring(0, 297) + "...";
		}

		return str;
	}
}