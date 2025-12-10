package org.labubus.search.repository;

import org.labubus.model.BookMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class SqliteMetadataRepository implements MetadataRepository {
	private static final Logger logger = LoggerFactory.getLogger(SqliteMetadataRepository.class);
	private final Connection connection;

	public SqliteMetadataRepository(String dbPath) throws SQLException {
		try {
			String url = "jdbc:sqlite:" + dbPath;
			this.connection = DriverManager.getConnection(url);
			logger.info("Connected to SQLite database: {}", dbPath);
		} catch (SQLException e) {
			logger.error("Failed to connect to SQLite database: {}", dbPath, e);
			throw e;
		}
	}

	@Override
	public Optional<BookMetadata> findById(int bookId) throws SQLException {
		String sql = "SELECT book_id, title, author, language, year, path FROM books WHERE book_id = ?";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setInt(1, bookId);

			try (ResultSet rs = stmt.executeQuery()) {
				if (rs.next()) {
					return Optional.of(mapResultSetToMetadata(rs));
				}
			}
		}

		return Optional.empty();
	}

	@Override
	public List<BookMetadata> findAll() throws SQLException {
		List<BookMetadata> books = new ArrayList<>();
		String sql = "SELECT book_id, title, author, language, year, path FROM books ORDER BY book_id";

		try (Statement stmt = connection.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {

			while (rs.next()) {
				books.add(mapResultSetToMetadata(rs));
			}
		}

		return books;
	}

	@Override
	public List<BookMetadata> findByAuthor(String author) throws SQLException {
		List<BookMetadata> books = new ArrayList<>();
		String sql = "SELECT book_id, title, author, language, year, path FROM books WHERE LOWER(author) LIKE ? ORDER BY book_id";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setString(1, "%" + author.toLowerCase() + "%");

			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					books.add(mapResultSetToMetadata(rs));
				}
			}
		}

		return books;
	}

	@Override
	public List<BookMetadata> findByLanguage(String language) throws SQLException {
		List<BookMetadata> books = new ArrayList<>();
		String sql = "SELECT book_id, title, author, language, year, path FROM books WHERE LOWER(language) = ? ORDER BY book_id";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setString(1, language.toLowerCase());

			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					books.add(mapResultSetToMetadata(rs));
				}
			}
		}

		return books;
	}

	@Override
	public List<BookMetadata> findByYear(int year) throws SQLException {
		List<BookMetadata> books = new ArrayList<>();
		String sql = "SELECT book_id, title, author, language, year, path FROM books WHERE year = ? ORDER BY book_id";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setInt(1, year);

			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					books.add(mapResultSetToMetadata(rs));
				}
			}
		}

		return books;
	}

	@Override
	public List<BookMetadata> findByIds(List<Integer> bookIds) throws SQLException {
		if (bookIds.isEmpty()) {
			return new ArrayList<>();
		}

		List<BookMetadata> books = new ArrayList<>();
		String placeholders = bookIds.stream().map(id -> "?").collect(Collectors.joining(","));
		String sql = "SELECT book_id, title, author, language, year, path FROM books WHERE book_id IN (" + placeholders + ") ORDER BY book_id";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			for (int i = 0; i < bookIds.size(); i++) {
				stmt.setInt(i + 1, bookIds.get(i));
			}

			try (ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					books.add(mapResultSetToMetadata(rs));
				}
			}
		}

		return books;
	}

	@Override
	public int count() throws SQLException {
		String sql = "SELECT COUNT(*) FROM books";

		try (Statement stmt = connection.createStatement();
			 ResultSet rs = stmt.executeQuery(sql)) {

			if (rs.next()) {
				return rs.getInt(1);
			}
		}

		return 0;
	}

	@Override
	public void close() throws SQLException {
		if (connection != null && !connection.isClosed()) {
			connection.close();
			logger.info("Closed SQLite connection");
		}
	}

	private BookMetadata mapResultSetToMetadata(ResultSet rs) throws SQLException {
		int bookId = rs.getInt("book_id");
		String title = rs.getString("title");
		String author = rs.getString("author");
		String language = rs.getString("language");
		Integer year = rs.getInt("year");
		if (rs.wasNull()) {
			year = null;
		}
		String path = rs.getString("path");

		return new BookMetadata(bookId, title, author, language, year, path);
	}
}