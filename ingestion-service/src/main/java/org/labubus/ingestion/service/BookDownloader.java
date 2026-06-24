package org.labubus.ingestion.service;

import java.io.IOException;

public interface BookDownloader {
	String downloadBook(int bookId) throws IOException;
}