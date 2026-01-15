package org.labubus.ingestion.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GutenbergDownloader implements BookDownloader {
	private static final Logger logger = LoggerFactory.getLogger(GutenbergDownloader.class);

	private final String baseUrl;
	private final int timeout;

	public GutenbergDownloader(String baseUrl, int timeout) {
		this.baseUrl = baseUrl;
		this.timeout = timeout;
	}

	@Override
	public String downloadBook(int bookId) throws IOException {
		String[] urlFormats = {
				String.format("%s/%d/pg%d.txt", baseUrl, bookId, bookId),
				String.format("%s/%d/pg%d.txt.utf8", baseUrl, bookId, bookId),
				String.format("%s/%d/pg%d.txt.utf-8", baseUrl, bookId, bookId),
				String.format("%s/%d/%d.txt", baseUrl, bookId, bookId),
				String.format("%s/%d/%d.txt.utf8", baseUrl, bookId, bookId),
				String.format("%s/%d/%d.txt.utf-8", baseUrl, bookId, bookId),
				String.format("%s/%d/%d-0.txt", baseUrl, bookId, bookId),
				String.format("%s/%d/%d-0.txt.utf8", baseUrl, bookId, bookId),
				String.format("%s/%d/%d-0.txt.utf-8", baseUrl, bookId, bookId)
		};

		IOException lastException = null;
		BookNotFoundException lastNotFound = null;
		boolean sawNonNotFoundFailure = false;
		StringBuilder attemptedUrls = new StringBuilder();

		for (String urlString : urlFormats) {
			try {
				logger.debug("Trying URL: {}", urlString);
				String content = downloadFromUrl(urlString);
				logger.info("Successfully downloaded book {} from {}", bookId, urlString);
				return content;
			} catch (BookNotFoundException e) {
				logger.debug("Not found at {}: {}", urlString, e.getMessage());
				attemptedUrls.append("\n  - ").append(urlString);
				lastNotFound = e;
				lastException = e;
			} catch (IOException e) {
				logger.debug("Failed to download from {}: {}", urlString, e.getMessage());
				attemptedUrls.append("\n  - ").append(urlString);
				lastException = e;
				sawNonNotFoundFailure = true;
			}
		}

		String message = String.format("Failed to download book %d. Attempted URLs:%s", bookId, attemptedUrls);
		if (lastNotFound != null && !sawNonNotFoundFailure) {
			throw new BookNotFoundException(message, lastNotFound);
		}
		throw new IOException(message, lastException);
	}

	private String downloadFromUrl(String urlString) throws IOException {
		final URL url;
		try {
			url = URI.create(urlString).toURL();
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid URL: " + urlString, e);
		}
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(timeout);
		connection.setReadTimeout(timeout);
		connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Stage2 Book Ingestion Service)");

		int responseCode = connection.getResponseCode();
		if (responseCode == 404 || responseCode == 410) {
			throw new BookNotFoundException("HTTP " + responseCode + " for URL: " + urlString);
		}
		if (responseCode != 200) {
			throw new IOException("HTTP " + responseCode + " for URL: " + urlString);
		}

		StringBuilder content = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				content.append(line).append("\n");
			}
		}

		return content.toString();
	}
}