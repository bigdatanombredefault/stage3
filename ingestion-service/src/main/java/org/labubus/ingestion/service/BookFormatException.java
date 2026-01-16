package org.labubus.ingestion.service;

import java.io.IOException;

/**
 * Thrown when downloaded/replicated content does not match the expected Project Gutenberg text format.
 */
public class BookFormatException extends IOException {
    public BookFormatException(String message) {
        super(message);
    }
}
