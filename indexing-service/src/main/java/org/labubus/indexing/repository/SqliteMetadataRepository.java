package org.labubus.indexing.repository;

import org.labubus.model.BookMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteMetadataRepository implements MetadataRepository {
	private static final Logger logger = LoggerFactory.getLogger(SqliteMetadataRepository.class);
	private final Connection connection;
	private final String dbPath;

	public SqliteMetadataRepository(String dbPath) throws SQLException {
		this.dbPath = dbPath;
		try {
			String url = "jdbc:sqlite:" + dbPath;
			this.connection = DriverManager.getConnection(url);

			createTableIfNotExists();

			logger.info("Connected to SQLite database: {}", dbPath);
		} catch (SQLException e) {
			logger.error("Failed to connect to SQLite database: {}", dbPath, e);
			throw e;
		}
	}

	private void createTableIfNotExists() throws SQLException {
		String sql = """
            CREATE TABLE IF NOT EXISTS books (
                book_id INTEGER PRIMARY KEY,
                title TEXT,
                author TEXT,
                language TEXT,
                year INTEGER,
                path TEXT,
                indexed_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """;

		try (Statement stmt = connection.createStatement()) {
			stmt.execute(sql);

			stmt.execute("CREATE INDEX IF NOT EXISTS idx_author ON books(author)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_language ON books(language)");
			stmt.execute("CREATE INDEX IF NOT EXISTS idx_year ON books(year)");

			logger.info("SQLite tables and indexes created/verified");
		}
	}

	@Override
	public void save(BookMetadata metadata) throws SQLException {
		String sql = """
            INSERT OR REPLACE INTO books (book_id, title, author, language, year, path, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
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