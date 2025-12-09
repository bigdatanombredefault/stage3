package org.labubus.ingestion.storage;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public interface DatalakeStorage {
	/**
	 * Save book header and body to datalake
	 */
	String saveBook(int bookId, String header, String body) throws IOException;

	/**
	 * Check if a book has been downloaded
	 */
	boolean isBookDownloaded(int bookId);

	/**
	 * Get the path where a book is stored (if it exists)
	 */
	String getBookPath(int bookId);

	/**
	 * Get all downloaded book IDs
	 */
	Set<Integer> getDownloadedBooks() throws IOException;

	/**
	 * Get list of all downloaded book IDs as a sorted list
	 */
	List<Integer> getDownloadedBooksList() throws IOException;

	/**
	 * Get count of downloaded books
	 */
	int getDownloadedBooksCount() throws IOException;
}