package org.labubus.search.repository;

import org.labubus.core.model.BookMetadata;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface MetadataRepository {
	/**
	 * Find book metadata by ID
	 */
	Optional<BookMetadata> findById(int bookId) throws SQLException;

	/**
	 * Get all book metadata
	 */
	List<BookMetadata> findAll() throws SQLException;

	/**
	 * Find books by author (case-insensitive partial match)
	 */
	List<BookMetadata> findByAuthor(String author) throws SQLException;

	/**
	 * Find books by language
	 */
	List<BookMetadata> findByLanguage(String language) throws SQLException;

	/**
	 * Find books by year
	 */
	List<BookMetadata> findByYear(int year) throws SQLException;

	/**
	 * Find books by list of IDs
	 */
	List<BookMetadata> findByIds(List<Integer> bookIds) throws SQLException;

	/**
	 * Count total books in database
	 */
	int count() throws SQLException;

	/**
	 * Close database connection
	 */
	void close() throws SQLException;
}