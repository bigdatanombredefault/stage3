package org.labubus.search;

import org.junit.jupiter.api.Test;
import org.labubus.search.indexer.InvertedIndexReader;
import org.labubus.search.indexer.JsonIndexReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class SearchServiceTest {

	@Test
	public void testIndexReaderStats() throws Exception {
		// Create a temporary index file
		Path tempDir = Files.createTempDirectory("test-index");
		Path indexFile = tempDir.resolve("test_index.json");

		String testIndex = """
            {
              "alice": [11],
              "wonderland": [11],
              "pride": [1342],
              "prejudice": [1342],
              "darcy": [1342]
            }
        """;

		Files.writeString(indexFile, testIndex);

		// Test JsonIndexReader
		JsonIndexReader reader = new JsonIndexReader(tempDir.toString(), "test_index.json");
		reader.load();

		assertTrue(reader.isLoaded());

		InvertedIndexReader.IndexStats stats = reader.getStats();
		assertEquals(5, stats.uniqueWords());
		assertTrue(stats.sizeInMB() > 0);

		System.out.println("Index reader stats test passed!");
		System.out.println("Unique words: " + stats.uniqueWords());
		System.out.println("Total mappings: " + stats.totalMappings());

		// Cleanup
		Files.delete(indexFile);
		Files.delete(tempDir);
	}

	@Test
	public void testIndexSearch() throws Exception {
		// Create a temporary index file
		Path tempDir = Files.createTempDirectory("test-search");
		Path indexFile = tempDir.resolve("test_index.json");

		String testIndex = """
            {
              "alice": [11, 42],
              "wonderland": [11],
              "rabbit": [11, 84],
              "pride": [1342],
              "prejudice": [1342]
            }
        """;

		Files.writeString(indexFile, testIndex);

		// Test searching
		JsonIndexReader reader = new JsonIndexReader(tempDir.toString(), "test_index.json");
		reader.load();

		Set<Integer> aliceResults = reader.search("alice");
		assertEquals(2, aliceResults.size());
		assertTrue(aliceResults.contains(11));
		assertTrue(aliceResults.contains(42));

		Set<Integer> rabbitResults = reader.search("rabbit");
		assertEquals(2, rabbitResults.size());

		Set<Integer> notFoundResults = reader.search("notfound");
		assertEquals(0, notFoundResults.size());

		System.out.println("Index search test passed!");

		// Cleanup
		Files.delete(indexFile);
		Files.delete(tempDir);
	}

	@Test
	public void testEmptyQuery() throws Exception {
		Path tempDir = Files.createTempDirectory("test-empty");
		Path indexFile = tempDir.resolve("test_index.json");

		String testIndex = "{\"test\": [1]}";
		Files.writeString(indexFile, testIndex);

		JsonIndexReader reader = new JsonIndexReader(tempDir.toString(), "test_index.json");
		reader.load();

		Set<Integer> results = reader.search("");
		assertEquals(0, results.size());

		System.out.println("Empty query test passed!");

		// Cleanup
		Files.delete(indexFile);
		Files.delete(tempDir);
	}
}