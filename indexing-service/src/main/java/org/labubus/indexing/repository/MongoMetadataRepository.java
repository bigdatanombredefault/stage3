package org.labubus.indexing.repository;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.labubus.indexing.model.BookMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MongoMetadataRepository implements MetadataRepository {
	private static final Logger logger = LoggerFactory.getLogger(MongoMetadataRepository.class);
	private final MongoClient mongoClient;
	private final MongoCollection<Document> booksCollection;

	public MongoMetadataRepository(String uri, String databaseName) {
		try {
			this.mongoClient = MongoClients.create(uri);
			MongoDatabase database = mongoClient.getDatabase(databaseName);
			this.booksCollection = database.getCollection("books");

			createIndexes();

			logger.info("Connected to MongoDB: {} / {}", uri, databaseName);
		} catch (MongoException e) {
			logger.error("Failed to connect to MongoDB", e);
			throw new RuntimeException("Failed to connect to MongoDB", e);
		}
	}

	private void createIndexes() {
		try {
			booksCollection.createIndex(Indexes.ascending("author"));
			booksCollection.createIndex(Indexes.ascending("language"));
			booksCollection.createIndex(Indexes.ascending("year"));
			logger.info("MongoDB indexes created/verified");
		} catch (MongoException e) {
			logger.warn("Failed to create indexes (may already exist)", e);
		}
	}

	@Override
	public void save(BookMetadata metadata) throws SQLException {
		try {
			Document doc = new Document("_id", metadata.bookId())
					.append("book_id", metadata.bookId())
					.append("title", metadata.title())
					.append("author", metadata.author())
					.append("language", metadata.language())
					.append("year", metadata.year())
					.append("path", metadata.path())
					.append("indexed_at", new java.util.Date());

			booksCollection.replaceOne(
					Filters.eq("_id", metadata.bookId()),
					doc,
					new ReplaceOptions().upsert(true)
			);

			logger.debug("Saved metadata for book {}", metadata.bookId());
		} catch (MongoException e) {
			logger.error("Failed to save metadata for book {}", metadata.bookId(), e);
			throw new SQLException("MongoDB save failed", e);
		}
	}

	@Override
	public Optional<BookMetadata> findById(int bookId) throws SQLException {
		try {
			Document doc = booksCollection.find(Filters.eq("_id", bookId)).first();
			if (doc != null) {
				return Optional.of(documentToMetadata(doc));
			}
			return Optional.empty();
		} catch (MongoException e) {
			logger.error("Failed to find book {}", bookId, e);
			throw new SQLException("MongoDB find failed", e);
		}
	}

	@Override
	public List<BookMetadata> findAll() throws SQLException {
		try {
			List<BookMetadata> books = new ArrayList<>();
			booksCollection.find()
					.sort(Indexes.ascending("book_id"))
					.forEach(doc -> books.add(documentToMetadata(doc)));
			return books;
		} catch (MongoException e) {
			logger.error("Failed to find all books", e);
			throw new SQLException("MongoDB findAll failed", e);
		}
	}

	@Override
	public int count() throws SQLException {
		try {
			return (int) booksCollection.countDocuments();
		} catch (MongoException e) {
			logger.error("Failed to count books", e);
			throw new SQLException("MongoDB count failed", e);
		}
	}

	@Override
	public void delete(int bookId) throws SQLException {
		try {
			booksCollection.deleteOne(Filters.eq("_id", bookId));
			logger.debug("Deleted book {}", bookId);
		} catch (MongoException e) {
			logger.error("Failed to delete book {}", bookId, e);
			throw new SQLException("MongoDB delete failed", e);
		}
	}

	@Override
	public void close() {
		if (mongoClient != null) {
			mongoClient.close();
			logger.info("Closed MongoDB connection");
		}
	}

	private BookMetadata documentToMetadata(Document doc) {
		int bookId = doc.getInteger("book_id");
		String title = doc.getString("title");
		String author = doc.getString("author");
		String language = doc.getString("language");
		Integer year = doc.getInteger("year");
		String path = doc.getString("path");

		return new BookMetadata(bookId, title, author, language, year, path);
	}
}