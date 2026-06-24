package org.labubus.ingestion.service;

import java.io.IOException;

public class BookFormatException extends IOException {
    public BookFormatException(String message) {
        super(message);
    }
}
