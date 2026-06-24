package org.labubus.ingestion.service;

import java.io.IOException;

public class BookNotFoundException extends IOException {
	public BookNotFoundException(String message) {
		super(message);
	}

	public BookNotFoundException(String message, Throwable cause) {
		super(message, cause);
	}
}
