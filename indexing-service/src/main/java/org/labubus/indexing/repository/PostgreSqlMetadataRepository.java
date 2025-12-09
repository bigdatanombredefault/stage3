package org.labubus.indexing.repository;

import org.labubus.core.model.BookMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PostgreSqlMetadataRepository implements MetadataRepository {
	private static final Logger logger = LoggerFactory.getLogger(PostgreSqlMetadataRepository.class);
	private final Connection connection;

	public PostgreSqlMetadataRepository(String url, String username, String password) throws SQLException {
		try {
			this.connection = DriverManager.getConnection(url, username, password);
			createTableIfNotExists();
			logger.info("Connected to PostgreSQL database: {}", url);
		} catch (SQLException e) {
			logger.error("Failed to connect to PostgreSQL database: {}", url, e);
			throw e;
		}
	}

	private void createTableIfNotExists() throws SQLException {
		String sql = """
            CREATE TABLE IF NOT EXISTS books (
                book_id INT PRIMARY KEY,
                title VARCHAR(500),
                author VARCHAR(300),
                language VARCHAR(10),
                year INT,
                path VARCHAR(500),
                indexed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """;

		try (Statement stmt = connection.createStatement()) {
			stmt.execute(sql);

			try {
				stmt.execute("CREATE INDEX idx_author ON books(author)");
			} catch (SQLException e) {
				logger.debug("Index idx_author may already exist");
			}

			try {
				stmt.execute("CREATE INDEX idx_language ON books(language)");
			} catch (SQLException e) {
				logger.debug("Index idx_language may already exist");
			}

			try {
				stmt.execute("CREATE INDEX idx_year ON books(year)");
			} catch (SQLException e) {
				logger.debug("Index idx_year may already exist");
			}

			logger.info("PostgreSQL tables and indexes created/verified");
		}
	}

	@Override
	public void save(BookMetadata metadata) throws SQLException {
		String sql = """
            INSERT INTO books (book_id, title, author, language, year, path)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (book_id) DO UPDATE SET
                title = EXCLUDED.title,
                author = EXCLUDED.author,
                language = EXCLUDED.language,
                year = EXCLUDED.year,
                path = EXCLUDED.path,
                indexed_at = CURRENT_TIMESTAMP
        """;

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setInt(1, metadata.bookId());
			stmt.setString(2, metadata.title());
			stmt.setString(3, metadata.author());
			stmt.setString(4, metadata.language());

			if (metadata.year() != null) {
				stmt.setInt(5, metadata.year());
			} else {
				stmt.setNull(5, Types.INTEGER);
			}

			stmt.setString(6, metadata.path());

			int rowsAffected = stmt.executeUpdate();
			logger.debug("Saved metadata for book {}: {} rows affected", metadata.bookId(), rowsAffected);
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
	public void delete(int bookId) throws SQLException {
		String sql = "DELETE FROM books WHERE book_id = ?";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setInt(1, bookId);
			int rowsAffected = stmt.executeUpdate();
			logger.debug("Deleted book {}: {} rows affected", bookId, rowsAffected);
		}
	}

	@Override
	public void close() throws SQLException {
		if (connection != null && !connection.isClosed()) {
			connection.close();
			logger.info("Closed PostgreSQL connection");
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