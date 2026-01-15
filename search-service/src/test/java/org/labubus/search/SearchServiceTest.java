package org.labubus.search;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.labubus.model.BookMetadata;
import org.labubus.search.model.SearchResult;
import org.labubus.search.service.SearchService;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.multimap.MultiMap;

class SearchServiceTest {

	@Test
	void searchService_search_returnsRankedResults() {
		Config config = new Config();
		config.setClusterName("test-" + UUID.randomUUID());
		HazelcastInstance hazelcast = Hazelcast.newHazelcastInstance(config);
		try {

		String metadataMapName = "metadata";
		String invertedIndexName = "inverted";

		BookMetadata book1 = new BookMetadata(1, "Magic Mountain", "Thomas Mann", "en", 1924, "/tmp/1");
		BookMetadata book2 = new BookMetadata(2, "Plain Book", "Anon", "en", 2000, "/tmp/2");
		hazelcast.getMap(metadataMapName).put(1, book1);
		hazelcast.getMap(metadataMapName).put(2, book2);

		MultiMap<String, String> index = hazelcast.getMultiMap(invertedIndexName);
		index.put("magic", "1");
		index.put("mountain", "1");
		index.put("plain", "2");

		SearchService service = new SearchService(hazelcast, 10, metadataMapName, invertedIndexName);
		List<SearchResult> results = service.search("magic", null, null, null, 10);
		assertEquals(1, results.size());
		assertEquals(1, results.get(0).bookId());
		assertTrue(results.get(0).score() > 0);
		} finally {
			hazelcast.shutdown();
		}
	}

	@Test
	void searchService_filtersByYear() {
		Config config = new Config();
		config.setClusterName("test-" + UUID.randomUUID());
		HazelcastInstance hazelcast = Hazelcast.newHazelcastInstance(config);
		try {

		String metadataMapName = "metadata";
		String invertedIndexName = "inverted";

		BookMetadata book1 = new BookMetadata(1, "Test", "A", "en", 1999, "/tmp/1");
		BookMetadata book2 = new BookMetadata(2, "Test", "B", "en", 2000, "/tmp/2");
		hazelcast.getMap(metadataMapName).put(1, book1);
		hazelcast.getMap(metadataMapName).put(2, book2);

		MultiMap<String, String> index = hazelcast.getMultiMap(invertedIndexName);
		index.put("test", "1");
		index.put("test", "2");

		SearchService service = new SearchService(hazelcast, 10, metadataMapName, invertedIndexName);
		List<SearchResult> results = service.search("test", null, null, 2000, 10);
		assertEquals(1, results.size());
		assertEquals(2, results.get(0).bookId());
		} finally {
			hazelcast.shutdown();
		}
	}
}