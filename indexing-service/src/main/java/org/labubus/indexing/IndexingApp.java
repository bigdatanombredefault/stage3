package org.labubus.indexing;

import io.javalin.Javalin;
import org.labubus.indexing.controller.IndexingController;
import org.labubus.indexing.indexer.InvertedIndexWriter;
import org.labubus.indexing.indexer.JsonIndexWriter;
import org.labubus.indexing.repository.MetadataRepository;
import org.labubus.indexing.repository.MongoMetadataRepository;
import org.labubus.indexing.repository.PostgreSqlMetadataRepository;
import org.labubus.indexing.repository.SqliteMetadataRepository;
import org.labubus.indexing.service.IndexingService;
import org.labubus.indexing.service.InvertedIndexBuilder;
import org.labubus.indexing.service.MetadataExtractor;
import org.labubus.indexing.storage.DatalakeReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Properties;
import java.util.Set;

public class IndexingApp {
	private static final Logger logger = LoggerFactory.getLogger(IndexingApp.class);

	public static void main(String[] args) {
		MetadataRepository metadataRepository = null;

		try {
			Properties config = loadConfiguration();

			parseArguments(args, config);

			int port = Integer.parseInt(config.getProperty("server.port", "7002"));

			logger.info("Starting Indexing Service...");
			logger.info("Configuration:");
			logger.info("  Port: {}", port);

			String datalakePath = config.getProperty("datalake.path", "../datalake");
			DatalakeReader datalakeReader = new DatalakeReader(datalakePath);
			logger.info("  Datalake Path: {}", datalakePath);

			MetadataExtractor metadataExtractor = new MetadataExtractor();

			String dbType = config.getProperty("db.type", "mysql");
			metadataRepository = createMetadataRepository(dbType, config);

			String indexType = config.getProperty("index.type", "json");
			InvertedIndexWriter indexWriter = createIndexWriter(indexType, config);

			int minWordLength = Integer.parseInt(config.getProperty("index.min.word.length", "3"));
			int maxWordLength = Integer.parseInt(config.getProperty("index.max.word.length", "50"));
			String stopWordsStr = config.getProperty("index.stop.words", "");
			Set<String> stopWords = InvertedIndexBuilder.parseStopWords(stopWordsStr);

			InvertedIndexBuilder indexBuilder = new InvertedIndexBuilder(
					indexWriter, minWordLength, maxWordLength, stopWords
			);
			logger.info("  Index Builder: min length={}, max length={}, stop words={}",
					minWordLength, maxWordLength, stopWords.size());

			try {
				indexWriter.load();
				logger.info("  Loaded existing index: {} unique words", indexWriter.getIndex().size());
			} catch (IOException e) {
				logger.info("  No existing index found, will create new one");
			}

			IndexingService indexingService = new IndexingService(
					datalakeReader,
					metadataExtractor,
					metadataRepository,
					indexBuilder,
					indexWriter
			);

			IndexingController controller = new IndexingController(indexingService);

			Javalin app = Javalin.create(javalinConfig -> {
				javalinConfig.http.defaultContentType = "application/json";
				javalinConfig.showJavalinBanner = false;
			}).start(port);

			controller.registerRoutes(app);

			MetadataRepository finalMetadataRepository = metadataRepository;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				logger.info("Shutting down Indexing Service...");
				app.stop();
				try {
					finalMetadataRepository.close();
				} catch (SQLException e) {
					logger.error("Error closing database connection", e);
				}
				logger.info("Indexing Service stopped");
			}));

			logger.info("Indexing Service started on port {}, using {} database and {} index",
					port, dbType, indexType);

		} catch (Exception e) {
			logger.error("Failed to start Indexing Service", e);
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
		System.out.println("\n=== Indexing Service Usage ===\n");
		System.out.println("Usage: java -jar indexing-service-1.0.0.jar [options]\n");
		System.out.println("Options:");
		System.out.println("  --db.type <type>              Database type (default: sqlite)");
		System.out.println("                                Options: sqlite, postgresql, mongodb");
		System.out.println("  --index.type <type>           Index storage type (default: json)");
		System.out.println("                                Options: json, folder");
		System.out.println("  --server.port <port>          Server port (default: 7002)");
		System.out.println("  --datalake.path <path>        Datalake path (default: ../datalake)");
		System.out.println("  --datamart.path <path>        Datamart path (default: ../datamart)");
		System.out.println("  -h, --help                    Show this help message\n");
		System.out.println("Examples:");
		System.out.println("  # Run with SQLite (default)");
		System.out.println("  java -jar indexing-service-1.0.0.jar\n");
		System.out.println("  # Run with PostgreSQL");
		System.out.println("  java -jar indexing-service-1.0.0.jar --db.type postgresql\n");
		System.out.println("  # Run with MongoDB");
		System.out.println("  java -jar indexing-service-1.0.0.jar --db.type mongodb\n");
		System.out.println("  # Run with custom port and JSON index");
		System.out.println("  java -jar indexing-service-1.0.0.jar --server.port 8002 --index.type json\n");
	}

	private static MetadataRepository createMetadataRepository(String type, Properties config) throws SQLException {
		if (type.equalsIgnoreCase("sqlite")) {
			String dbPath = config.getProperty("db.sqlite.path", "../datamart/bookdb.sqlite");

			try {
				java.nio.file.Files.createDirectories(java.nio.file.Paths.get(dbPath).getParent());
			} catch (java.io.IOException e) {
				logger.warn("Failed to create datamart directory", e);
			}

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

	private static InvertedIndexWriter createIndexWriter(String type, Properties config) {
		String datamartPath = config.getProperty("datamart.path", "../datamart");

		if (type.equalsIgnoreCase("json")) {
			String indexFilename = config.getProperty("index.filename", "inverted_index.json");
			logger.info("  Index: JSON file ({}/{})", datamartPath, indexFilename);
			return new JsonIndexWriter(datamartPath, indexFilename);
		} else {
			throw new IllegalArgumentException("Unknown index type: " + type);
		}
	}

	private static Properties loadConfiguration() {
		Properties properties = new Properties();

		try (InputStream input = IndexingApp.class.getClassLoader()
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