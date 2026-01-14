package org.labubus.ingestion.service;

import java.io.IOException;

import javax.jms.JMSException;

import org.labubus.ingestion.config.IngestionConfig;
import org.labubus.ingestion.distributed.DatalakeReplicationClient;
import org.labubus.ingestion.distributed.MessageProducer;
import org.labubus.ingestion.storage.DatalakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BookIngestionService {
    private static final Logger logger = LoggerFactory.getLogger(BookIngestionService.class);

    private final DatalakeStorage storage;
    private final BookDownloader downloader;
    private final MessageProducer messageProducer;
    private final DatalakeReplicationClient replicationClient;
    private final IngestionConfig.Replication replication;

    public BookIngestionService(
        DatalakeStorage storage,
        BookDownloader downloader,
        MessageProducer messageProducer,
        DatalakeReplicationClient replicationClient,
        IngestionConfig.Replication replication
    ) {
        this.storage = storage;
        this.downloader = downloader;
        this.messageProducer = messageProducer;
        this.replicationClient = replicationClient;
        this.replication = replication;
    }

    public String downloadAndSave(int bookId) throws IOException, JMSException {
        logger.info("Starting download for book {}", bookId);

        String bookContent = downloader.downloadBook(bookId);
        String[] parts = BookContentParser.splitHeaderBody(bookContent);
        String header = parts[0];
        String body = parts[1];

        String path = storage.saveBook(bookId, header, body);
        logger.info("Successfully downloaded and saved book {} to {}", bookId, path);

        replicateIfEnabled(bookId, header, bookContent);

        messageProducer.sendMessage(bookId);
        logger.info("Successfully published 'document.ingested' event for book {}", bookId);

        return path;
    }

    private void replicateIfEnabled(int bookId, String header, String bookContent) throws IOException {
        if (replication == null || !replication.enabled()) {
            return;
        }

        String title = extractTitle(header);
        replicationClient.replicateOnce(
            bookId,
            title,
            bookContent,
            replication.currentNodeIp(),
            replication.clusterNodes(),
            replication.receiverPort(),
            replication.receiverEndpoint()
        );
    }

    private static String extractTitle(String header) {
        if (header == null || header.isBlank()) {
            return "";
        }
        for (String line : header.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, "title:", 0, "title:".length())) {
                return trimmed.substring("title:".length()).trim();
            }
        }
        return "";
    }

    public boolean isBookDownloaded(int bookId) {
        return storage.isBookDownloaded(bookId);
    }

    public String getBookPath(int bookId) {
        return storage.getBookPath(bookId);
    }
}