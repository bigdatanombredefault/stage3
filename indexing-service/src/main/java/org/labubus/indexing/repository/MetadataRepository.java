package org.labubus.indexing.repository;

import org.labubus.indexing.model.BookMetadata;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface MetadataRepository {
	/**
	 * Save or update book metadata
	 */
	void save(BookMetadata metadata) throws SQLException;

	/**
	 * Find book metadata by ID
	 */
	Optional<BookMetadata> findById(int bookId) throws SQLException;

	/**
	 * Get all book metadata
	 */
	List<BookMetadata> findAll() throws SQLException;

	/**
	 * Count total books in database
	 */
	int count() throws SQLException;

	/**
	 * Delete book metadata
	 */
	void delete(int bookId) throws SQLException;

	/**
	 * Close database connection
	 */
	void close() throws SQLException;
}