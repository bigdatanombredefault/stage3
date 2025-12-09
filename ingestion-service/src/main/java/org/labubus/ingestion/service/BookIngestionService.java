package org.labubus.ingestion.service;

import org.labubus.ingestion.distributed.MessageProducer;
import org.labubus.ingestion.storage.DatalakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.JMSException;
import java.io.IOException;

public class BookIngestionService {
    private static final Logger logger = LoggerFactory.getLogger(BookIngestionService.class);

    private final DatalakeStorage storage;
    private final BookDownloader downloader;
    private final MessageProducer messageProducer;

    public BookIngestionService(DatalakeStorage storage, BookDownloader downloader, MessageProducer messageProducer) {
        this.storage = storage;
        this.downloader = downloader;
        this.messageProducer = messageProducer;
    }

    public String downloadAndSave(int bookId) throws IOException, JMSException {
        logger.info("Starting download for book {}", bookId);

        String bookContent = downloader.downloadBook(bookId);

        String[] parts = splitHeaderBody(bookContent);
        String header = parts[0];
        String body = parts[1];

        String path = storage.saveBook(bookId, header, body);
        logger.info("Successfully downloaded and saved book {} to {}", bookId, path);

        messageProducer.sendMessage(bookId);
        logger.info("Successfully published 'document.ingested' event for book {}", bookId);

        return path;
    }

    private String[] splitHeaderBody(String content) throws IOException {
        String startMarker = "*** START OF";
        int startIndex = content.indexOf(startMarker);

        if (startIndex == -1) {
            throw new IOException("Invalid book format: START marker not found.");
        }

        String endMarker = "*** END OF";
        int endIndex = content.indexOf(endMarker, startIndex);

        if (endIndex == -1) {
            throw new IOException("Invalid book format: END marker not found.");
        }

        String header = content.substring(0, startIndex).trim();
        String body = content.substring(startIndex, endIndex).trim();

        body = body.replaceFirst("\\*\\*\\* START OF[^\\n]*\\n", "").trim();

        if (header.isEmpty()) {
            throw new IOException("Invalid book format: Header is empty");
        }
        if (body.isEmpty()) {
            throw new IOException("Invalid book format: Body is empty");
        }

        logger.debug("Successfully split book - Header: {} chars, Body: {} chars", header.length(), body.length());
        return new String[]{header, body};
    }

    public boolean isBookDownloaded(int bookId) {
        return storage.isBookDownloaded(bookId);
    }

    public String getBookPath(int bookId) {
        return storage.getBookPath(bookId);
    }
}