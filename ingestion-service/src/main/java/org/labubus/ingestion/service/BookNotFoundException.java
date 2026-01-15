package org.labubus.ingestion.service;

import java.io.IOException;

/**
 * Indicates a book could not be found on the remote source (e.g., Gutenberg).
 */
public class BookNotFoundException extends IOException {
	public BookNotFoundException(String message) {
		super(message);
	}

	public BookNotFoundException(String message, Throwable cause) {
		super(message, cause);
	}
}
