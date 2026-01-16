package org.labubus.ingestion.service;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final IngestionConfig.Async async;

    private final ExecutorService executor;
    private final ConcurrentHashMap<Integer, JobStatus> jobs = new ConcurrentHashMap<>();

    public record JobStatus(int bookId, String status, String path, String message, long updatedAtMs) {}

    public BookIngestionService(
        DatalakeStorage storage,
        BookDownloader downloader,
        MessageProducer messageProducer,
        DatalakeReplicationClient replicationClient,
        IngestionConfig.Replication replication,
        IngestionConfig.Async async
    ) {
        this.storage = storage;
        this.downloader = downloader;
        this.messageProducer = messageProducer;
        this.replicationClient = replicationClient;
        this.replication = replication;
        this.async = async;
        this.executor = Executors.newFixedThreadPool(async.workers(), r -> {
            Thread t = new Thread(r);
            t.setName("ingest-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isAsyncEnabledByDefault() {
        return async != null && async.enabled();
    }

    public JobStatus startAsyncIngestion(int bookId) {
        if (isBookDownloaded(bookId)) {
            return new JobStatus(bookId, "available", getBookPath(bookId), null, System.currentTimeMillis());
        }

        AtomicBoolean shouldSchedule = new AtomicBoolean(false);
        JobStatus status = jobs.compute(bookId, (id, existing) -> {
            if (existing != null && !isTerminal(existing.status())) {
                return existing;
            }

            shouldSchedule.set(true);
            return new JobStatus(bookId, "queued", null, null, System.currentTimeMillis());
        });

        if (shouldSchedule.get()) {
            executor.submit(() -> runIngestionJob(bookId));
        }

        return status;
    }

    public JobStatus getJobStatus(int bookId) {
        JobStatus job = jobs.get(bookId);
        if (job != null) {
            return job;
        }
        if (isBookDownloaded(bookId)) {
            return new JobStatus(bookId, "available", getBookPath(bookId), null, System.currentTimeMillis());
        }
        return new JobStatus(bookId, "not_found", null, null, System.currentTimeMillis());
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static boolean isTerminal(String status) {
        return "available".equals(status) || "failed".equals(status);
    }

    private void runIngestionJob(int bookId) {
        try {
            update(bookId, "downloading", null, null);
            String path = downloadAndSave(bookId);
            update(bookId, "available", path, null);
        } catch (BookNotFoundException e) {
            logger.info("Book {} not found on source: {}", bookId, e.getMessage());
            update(bookId, "failed", null, e.getMessage());
        } catch (BookFormatException e) {
            logger.warn("Book {} has unsupported format: {}", bookId, e.getMessage());
            update(bookId, "failed", null, e.getMessage());
        } catch (IOException | JMSException | RuntimeException e) {
            logger.error("Async ingestion failed for book {}: {}", bookId, e.getMessage(), e);
            update(bookId, "failed", null, e.getMessage());
        }
    }

    private void update(int bookId, String status, String path, String message) {
        jobs.put(bookId, new JobStatus(bookId, status, path, message, System.currentTimeMillis()));
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