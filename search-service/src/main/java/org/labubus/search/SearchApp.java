package org.labubus.search;

import io.javalin.Javalin;
import org.labubus.search.controller.SearchController;
import org.labubus.search.indexer.InvertedIndexReader;
import org.labubus.search.indexer.JsonIndexReader;
import org.labubus.search.repository.*;
import org.labubus.search.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Properties;

public class SearchApp {
	private static final Logger logger = LoggerFactory.getLogger(SearchApp.class);

	public static void main(String[] args) {
		MetadataRepository metadataRepository = null;

		try {
			Properties config = loadConfiguration();

			parseArguments(args, config);

			int port = Integer.parseInt(config.getProperty("server.port", "7003"));

			logger.info("Starting Search Service...");
			logger.info("Configuration:");
			logger.info("  Port: {}", port);

			String dbType = config.getProperty("db.type", "sqlite");
			metadataRepository = createMetadataRepository(dbType, config);

			String indexType = config.getProperty("index.type", "json");
			InvertedIndexReader indexReader = createIndexReader(indexType, config);

			try {
				indexReader.load();
				logger.info("  Index loaded: {} unique words", indexReader.getStats().uniqueWords());
			} catch (IOException e) {
                logger.warn("Failed to load inverted index - service will start but searches will fail until index is created", e);
			}

			int maxResults = Integer.parseInt(config.getProperty("search.max.results", "100"));
			int defaultResults = Integer.parseInt(config.getProperty("search.default.results", "10"));

			SearchService searchService = new SearchService(metadataRepository, indexReader, maxResults);
			logger.info("  Max results: {}, Default results: {}", maxResults, defaultResults);

			SearchController controller = new SearchController(searchService, defaultResults);

			Javalin app = Javalin.create(javalinConfig -> {
				javalinConfig.http.defaultContentType = "application/json";
				javalinConfig.showJavalinBanner = false;
			}).start(port);

			controller.registerRoutes(app);

			MetadataRepository finalMetadataRepository = metadataRepository;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				logger.info("Shutting down Search Service...");
				app.stop();
				try {
					finalMetadataRepository.close();
				} catch (SQLException e) {
					logger.error("Error closing database connection", e);
				}
				logger.info("Search Service stopped");
			}));

			logger.info("Search Service started on port {}, using {} database and {} index",
					port, dbType, indexType);

		} catch (Exception e) {
			logger.error("Failed to start Search Service", e);
			printUsage();
			if (metadataRepository != null) {
				try {
					metadataRepository.close();
				} catch (SQLException ex) {
					logger.error("Error closing database connection", ex);
				}
			}
			System.exit(1);
		}
	}

	/**
	 * Parse command line arguments and update configuration
	 */
	private static void parseArguments(String[] args, Properties config) {
		for (int i = 0; i < args.length; i++) {
			if (args[i].startsWith("--") && i + 1 < args.length) {
				String key = args[i].substring(2);
				String value = args[i + 1];
				config.setProperty(key, value);
				logger.info("Command line argument: {} = {}", key, value);
				i++;
			} else if (args[i].equals("-h") || args[i].equals("--help")) {
				printUsage();
				System.exit(0);
			}
		}
	}

	/**
	 * Print usage information
	 */
	private static void printUsage() {
		System.out.println("\n=== Search Service Usage ===\n");
		System.out.println("Usage: java -jar search-service-1.0.0.jar [options]\n");
		System.out.println("Options:");
		System.out.println("  --db.type <type>              Database type (default: sqlite)");
		System.out.println("                                Options: sqlite, postgresql, mongodb");
		System.out.println("  --index.type <type>           Index storage type (default: json)");
		System.out.println("                                Options: json, folder");
		System.out.println("  --server.port <port>          Server port (default: 7003)");
		System.out.println("  --datamart.path <path>        Datamart path (default: ../datamart)");
		System.out.println("  -h, --help                    Show this help message\n");
		System.out.println("Examples:");
		System.out.println("  # Run with SQLite (default)");
		System.out.println("  java -jar search-service-1.0.0.jar\n");
		System.out.println("  # Run with PostgreSQL");
		System.out.println("  java -jar search-service-1.0.0.jar --db.type postgresql\n");
		System.out.println("  # Run with MongoDB");
		System.out.println("  java -jar search-service-1.0.0.jar --db.type mongodb\n");
		System.out.println("  # Run with custom port");
		System.out.println("  java -jar search-service-1.0.0.jar --server.port 8003\n");
	}

	private static MetadataRepository createMetadataRepository(String type, Properties config) throws SQLException {
		if (type.equalsIgnoreCase("sqlite")) {
			String dbPath = config.getProperty("db.sqlite.path", "../datamart/bookdb.sqlite");
			logger.info("  Database: SQLite");
			logger.info("  Path: {}", dbPath);
			return new SqliteMetadataRepository(dbPath);

		} else if (type.equalsIgnoreCase("postgresql")) {
			String url = config.getProperty("db.postgresql.url");
			String username = config.getProperty("db.postgresql.username");
			String password = config.getProperty("db.postgresql.password");

			logger.info("  Database: PostgreSQL");
			logger.info("  URL: {}", url);
			return new PostgreSqlMetadataRepository(url, username, password);

		} else if (type.equalsIgnoreCase("mongodb")) {
			String uri = config.getProperty("db.mongodb.uri", "mongodb://localhost:27017");
			String database = config.getProperty("db.mongodb.database", "bookdb");

			logger.info("  Database: MongoDB");
			logger.info("  URI: {}", uri);
			logger.info("  Database: {}", database);
			return new MongoMetadataRepository(uri, database);

		} else {
			throw new IllegalArgumentException("Unknown database type: " + type + ". Valid options: sqlite, postgresql, mongodb");
		}
	}

	private static InvertedIndexReader createIndexReader(String type, Properties config) {
		String datamartPath = config.getProperty("datamart.path", "../datamart");

		if (type.equalsIgnoreCase("json")) {
			String indexFilename = config.getProperty("index.filename", "inverted_index.json");
			logger.info("  Index: JSON file ({}/{})", datamartPath, indexFilename);
			return new JsonIndexReader(datamartPath, indexFilename);
		} else {
			throw new IllegalArgumentException("Unknown index type: " + type);
		}
	}

	private static Properties loadConfiguration() {
		Properties properties = new Properties();

		try (InputStream input = SearchApp.class.getClassLoader()
				.getResourceAsStream("application.properties")) {

			if (input != null) {
				properties.load(input);
				logger.info("Loaded configuration from application.properties");
			} else {
				logger.warn("application.properties not found, using defaults");
			}

		} catch (IOException e) {
			logger.warn("Failed to load application.properties", e);
		}

		return properties;
	}
}