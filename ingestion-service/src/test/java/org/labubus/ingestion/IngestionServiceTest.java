package org.labubus.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.labubus.ingestion.distributed.DatalakeReplicationClient;
import org.labubus.ingestion.storage.BucketDatalakeStorage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

class IngestionServiceTest {

	@Test
	void bucketDatalakeStorage_savesFilesAndTracksBook(@TempDir Path tempDir) throws IOException {
		BucketDatalakeStorage storage = new BucketDatalakeStorage(tempDir.toString(), 1000, "downloaded.txt");

		int bookId = 123;
		String header = "Title: Test Book\nAuthor: Someone\n";
		String body = "hello world";
		String savedPath = storage.saveBook(bookId, header, body);
		assertNotNull(savedPath);

		Path headerPath = tempDir.resolve("bucket_0").resolve("123_header.txt");
		Path bodyPath = tempDir.resolve("bucket_0").resolve("123_body.txt");
		assertTrue(Files.exists(headerPath));
		assertTrue(Files.exists(bodyPath));
		assertEquals(header, Files.readString(headerPath));
		assertEquals(body, Files.readString(bodyPath));

		assertTrue(storage.isBookDownloaded(bookId));
		assertEquals(1, storage.getDownloadedBooksCount());
		assertEquals(List.of(bookId), storage.getDownloadedBooksList());
	}

	@Test
	void datalakeReplicationClient_replicateOnce_succeedsAgainstLocalReceiver() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try {
			server.createContext("/api/datalake/store", new Always201Handler());
			server.start();

			int port = server.getAddress().getPort();
			DatalakeReplicationClient client = new DatalakeReplicationClient(Duration.ofSeconds(2));

			client.replicateOnce(
				1,
				"title",
				"header\n\nbody",
				"10.0.0.2",
				List.of("127.0.0.1"),
				port,
				"/api/datalake/store"
			);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void datalakeReplicationClient_replicateOnce_throwsWhenNoTargets() {
		DatalakeReplicationClient client = new DatalakeReplicationClient(Duration.ofSeconds(1));
		assertThrows(IOException.class, () -> client.replicateOnce(
			1,
			"",
			"content",
			"127.0.0.1",
			List.of("127.0.0.1"),
			8080,
			"/api/datalake/store"
		));
	}

	private static final class Always201Handler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try {
				assertEquals("POST", exchange.getRequestMethod());
				exchange.getRequestBody().readAllBytes();
				byte[] response = "ok".getBytes();
				exchange.sendResponseHeaders(201, response.length);
				exchange.getResponseBody().write(response);
			} finally {
				exchange.close();
			}
		}
	}
}