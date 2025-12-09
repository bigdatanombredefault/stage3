package org.labubus.search.repository;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.labubus.search.model.BookMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class MongoMetadataRepository implements MetadataRepository {
	private static final Logger logger = LoggerFactory.getLogger(MongoMetadataRepository.class);
	private final MongoClient mongoClient;
	private final MongoCollection<Document> booksCollection;

	public MongoMetadataRepository(String uri, String databaseName) {
		try {
			this.mongoClient = MongoClients.create(uri);
			MongoDatabase database = mongoClient.getDatabase(databaseName);
			this.booksCollection = database.getCollection("books");
			logger.info("Connected to MongoDB: {} / {}", uri, databaseName);
		} catch (MongoException e) {
			logger.error("Failed to connect to MongoDB", e);
			throw new RuntimeException("Failed to connect to MongoDB", e);
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
	public List<BookMetadata> findByAuthor(String author) throws SQLException {
		try {
			List<BookMetadata> books = new ArrayList<>();
			Pattern pattern = Pattern.compile(author, Pattern.CASE_INSENSITIVE);
			Bson filter = Filters.regex("author", pattern);

			booksCollection.find(filter)
					.sort(Indexes.ascending("book_id"))
					.forEach(doc -> books.add(documentToMetadata(doc)));
			return books;
		} catch (MongoException e) {
			logger.error("Failed to find books by author", e);
			throw new SQLException("MongoDB findByAuthor failed", e);
		}
	}

	@Override
	public List<BookMetadata> findByLanguage(String language) throws SQLException {
		try {
			List<BookMetadata> books = new ArrayList<>();
			Bson filter = Filters.eq("language", language.toLowerCase());

			booksCollection.find(filter)
					.sort(Indexes.ascending("book_id"))
					.forEach(doc -> books.add(documentToMetadata(doc)));
			return books;
		} catch (MongoException e) {
			logger.error("Failed to find books by language", e);
			throw new SQLException("MongoDB findByLanguage failed", e);
		}
	}

	@Override
	public List<BookMetadata> findByYear(int year) throws SQLException {
		try {
			List<BookMetadata> books = new ArrayList<>();
			Bson filter = Filters.eq("year", year);

			booksCollection.find(filter)
					.sort(Indexes.ascending("book_id"))
					.forEach(doc -> books.add(documentToMetadata(doc)));
			return books;
		} catch (MongoException e) {
			logger.error("Failed to find books by year", e);
			throw new SQLException("MongoDB findByYear failed", e);
		}
	}

	@Override
	public List<BookMetadata> findByIds(List<Integer> bookIds) throws SQLException {
		try {
			List<BookMetadata> books = new ArrayList<>();
			Bson filter = Filters.in("book_id", bookIds);

			booksCollection.find(filter)
					.sort(Indexes.ascending("book_id"))
					.forEach(doc -> books.add(documentToMetadata(doc)));
			return books;
		} catch (MongoException e) {
			logger.error("Failed to find books by IDs", e);
			throw new SQLException("MongoDB findByIds failed", e);
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