package org.labubus.ingestion.service;

import java.io.IOException;

public interface BookDownloader {
	/**
	 * Download a book and return its content
	 * @param bookId The book identifier
	 * @return The complete book content
	 * @throws IOException if download fails
	 */
	String downloadBook(int bookId) throws IOException;
}