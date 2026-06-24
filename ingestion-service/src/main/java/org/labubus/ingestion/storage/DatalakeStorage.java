package org.labubus.ingestion.storage;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public interface DatalakeStorage {
	String saveBook(int bookId, String header, String body) throws IOException;

	boolean isBookDownloaded(int bookId);

	String getBookPath(int bookId);

	Set<Integer> getDownloadedBooks() throws IOException;

	List<Integer> getDownloadedBooksList() throws IOException;

	int getDownloadedBooksCount() throws IOException;
}