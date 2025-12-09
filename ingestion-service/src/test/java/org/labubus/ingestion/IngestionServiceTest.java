package org.labubus.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.labubus.ingestion.service.BookDownloader;
import org.labubus.ingestion.service.BookIngestionService;
import org.labubus.ingestion.service.GutenbergDownloader;
import org.labubus.ingestion.storage.BucketDatalakeStorage;
import org.labubus.ingestion.storage.DatalakeStorage;
import org.labubus.ingestion.storage.TimestampDatalakeStorage;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class IngestionServiceTest {

	@Test
	public void testBucketDatalakeStorage(@TempDir Path tempDir) throws Exception {
		DatalakeStorage storage = new BucketDatalakeStorage(tempDir.toString(), 10);

		String path = storage.saveBook(5, "Header content", "Body content");

		assertTrue(storage.isBookDownloaded(5));
		assertNotNull(storage.getBookPath(5));
		assertEquals(1, storage.getDownloadedBooksCount());

		System.out.println("Bucket DatalakeStorage test passed!");
	}

	@Test
	public void testTimestampDatalakeStorage(@TempDir Path tempDir) throws Exception {
		DatalakeStorage storage = new TimestampDatalakeStorage(tempDir.toString());

		String path = storage.saveBook(5, "Header content", "Body content");

		assertTrue(storage.isBookDownloaded(5));
		assertNotNull(storage.getBookPath(5));
		assertEquals(1, storage.getDownloadedBooksCount());
		assertTrue(path.contains("2025")); // Should contain year

		System.out.println("Timestamp DatalakeStorage test passed!");
	}

	@Test
	public void testBookDownload(@TempDir Path tempDir) throws Exception {
		DatalakeStorage storage = new BucketDatalakeStorage(tempDir.toString(), 10);
		BookDownloader downloader = new GutenbergDownloader(
				"https://www.gutenberg.org/cache/epub",
				30000
		);
		BookIngestionService service = new BookIngestionService(storage, downloader);

		System.out.println("Downloading book 11 (Alice in Wonderland)...");
		String path = service.downloadAndSave(11);

		assertNotNull(path);
		assertTrue(service.isBookDownloaded(11));

		System.out.println("Book download test passed!");
		System.out.println("Saved to: " + path);
	}

	@Test
	public void testBookNotFound(@TempDir Path tempDir) {
		DatalakeStorage storage = new BucketDatalakeStorage(tempDir.toString(), 10);
		BookDownloader downloader = new GutenbergDownloader(
				"https://www.gutenberg.org/cache/epub",
				30000
		);
		BookIngestionService service = new BookIngestionService(storage, downloader);

		// Try to download a non-existent book
		IOException exception = assertThrows(IOException.class, () -> {
			service.downloadAndSave(999999);
		});

		System.out.println("\nTest: Book Not Found");
		System.out.println("Error message: " + exception.getMessage());

		// Should mention attempted URLs
		assertTrue(exception.getMessage().contains("Failed to download book 999999"));
		assertTrue(exception.getMessage().contains("Attempted URLs"));

		// Verify nothing was saved
		assertFalse(service.isBookDownloaded(999999));

		System.out.println("Book not found test passed!\n");
	}
}