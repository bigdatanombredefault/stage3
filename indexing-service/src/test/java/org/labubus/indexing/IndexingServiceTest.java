package org.labubus.indexing;

import org.junit.jupiter.api.Test;
import org.labubus.indexing.service.InvertedIndexBuilder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class IndexingServiceTest {

	@Test
	public void testStopWordsParser() {
		String stopWordsStr = "the,a,an,and,or,but";
		Set<String> stopWords = InvertedIndexBuilder.parseStopWords(stopWordsStr);

		assertEquals(6, stopWords.size());
		assertTrue(stopWords.contains("the"));
		assertTrue(stopWords.contains("and"));
		assertFalse(stopWords.contains("hello"));

		System.out.println("✅ Stop words parser test passed!");
	}

	@Test
	public void testEmptyStopWords() {
		Set<String> stopWords = InvertedIndexBuilder.parseStopWords("");
		assertEquals(0, stopWords.size());

		stopWords = InvertedIndexBuilder.parseStopWords(null);
		assertEquals(0, stopWords.size());

		System.out.println("✅ Empty stop words test passed!");
	}

	@Test
	public void testMetadataExtraction() {
		String header = """
            Title: Alice's Adventures in Wonderland
            
            Author: Lewis Carroll
            
            Release Date: June 25, 2008 [EBook #11]
            
            Language: English
            """;

		org.labubus.indexing.service.MetadataExtractor extractor =
				new org.labubus.indexing.service.MetadataExtractor();

		var metadata = extractor.extractMetadata(11, header, "test/path");

		assertEquals(11, metadata.bookId());
		assertEquals("Alice's Adventures in Wonderland", metadata.title());
		assertEquals("Lewis Carroll", metadata.author());
		assertEquals("english", metadata.language());
		assertEquals(2008, metadata.year());

		System.out.println("✅ Metadata extraction test passed!");
		System.out.println("   " + metadata);
	}
}