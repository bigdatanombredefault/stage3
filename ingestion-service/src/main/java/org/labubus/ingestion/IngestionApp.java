package org.labubus.ingestion;

import io.javalin.Javalin;
import org.labubus.ingestion.controller.IngestionController;
import org.labubus.ingestion.service.BookDownloader;
import org.labubus.ingestion.service.BookIngestionService;
import org.labubus.ingestion.service.GutenbergDownloader;
import org.labubus.ingestion.storage.BucketDatalakeStorage;
import org.labubus.ingestion.storage.DatalakeStorage;
import org.labubus.ingestion.storage.TimestampDatalakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class IngestionApp {
	private static final Logger logger = LoggerFactory.getLogger(IngestionApp.class);

	public static void main(String[] args) {
		try {
			Properties config = loadConfiguration();

			parseArguments(args, config);

			int port = Integer.parseInt(config.getProperty("server.port", "7001"));

			logger.info("Starting Ingestion Service...");
			logger.info("Configuration:");
			logger.info("  Port: {}", port);

			String datalakeType = config.getProperty("datalake.type", "bucket");
			DatalakeStorage storage = createDatalakeStorage(datalakeType, config);

			String bookSource = config.getProperty("book.source", "gutenberg");
			BookDownloader downloader = createBookDownloader(bookSource, config);

			BookIngestionService ingestionService = new BookIngestionService(storage, downloader);
			IngestionController controller = new IngestionController(ingestionService, storage);

			Javalin app = Javalin.create(javalinConfig -> {
				javalinConfig.http.defaultContentType = "application/json";
				javalinConfig.showJavalinBanner = false;
			}).start(port);

			controller.registerRoutes(app);

			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				logger.info("Shutting down Ingestion Service...");
				app.stop();
				logger.info("Ingestion Service stopped");
			}));

			logger.info("Ingestion Service started on port {}, using {} storage and {} book source",
					port, datalakeType, bookSource);

		} catch (Exception e) {
			logger.error("Failed to start Ingestion Service", e);
			printUsage();
			System.exit(1);
		}
	}

	/**
	 * Parse command line arguments and update configuration
	 * Supported arguments:
	 *   --datalake.type <bucket|timestamp>
	 *   --server.port <port>
	 *   --datalake.path <path>
	 *   --datalake.bucket.size <size>
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
		System.out.println("\n=== Ingestion Service Usage ===\n");
		System.out.println("Usage: java -jar ingestion-service-1.0.0.jar [options]\n");
		System.out.println("Options:");
		System.out.println("  --datalake.type <type>        Datalake storage type (default: bucket)");
		System.out.println("                                Options: bucket, timestamp");
		System.out.println("  --server.port <port>          Server port (default: 7001)");
		System.out.println("  --datalake.path <path>        Datalake path (default: ../datalake)");
		System.out.println("  --datalake.bucket.size <size> Bucket size (default: 10)");
		System.out.println("  -h, --help                    Show this help message\n");
		System.out.println("Examples:");
		System.out.println("  # Run with bucket datalake (default)");
		System.out.println("  java -jar ingestion-service-1.0.0.jar\n");
		System.out.println("  # Run with timestamp datalake");
		System.out.println("  java -jar ingestion-service-1.0.0.jar --datalake.type timestamp\n");
		System.out.println("  # Run with custom port and bucket size");
		System.out.println("  java -jar ingestion-service-1.0.0.jar --server.port 8001 --datalake.bucket.size 20\n");
	}

	private static DatalakeStorage createDatalakeStorage(String type, Properties config) {
		String datalakePath = config.getProperty("datalake.path", "../datalake");

		if (type.equalsIgnoreCase("bucket")) {
			int bucketSize = Integer.parseInt(config.getProperty("datalake.bucket.size", "10"));
			logger.info("  Datalake: Bucket-based (size={})", bucketSize);
			return new BucketDatalakeStorage(datalakePath, bucketSize);
		} else if (type.equalsIgnoreCase("timestamp")) {
			logger.info("  Datalake: Timestamp-based");
			return new TimestampDatalakeStorage(datalakePath);
		} else {
			throw new IllegalArgumentException(
					"Unknown datalake type: " + type + ". Valid options: bucket, timestamp"
			);
		}
	}

	private static BookDownloader createBookDownloader(String source, Properties config) {
		if (source.equalsIgnoreCase("gutenberg")) {
			String baseUrl = config.getProperty("gutenberg.base.url",
					"https://www.gutenberg.org/cache/epub");
			int timeout = Integer.parseInt(config.getProperty("gutenberg.download.timeout", "30000"));
			logger.info("  Book Source: Project Gutenberg");
			logger.info("  Base URL: {}", baseUrl);
			logger.info("  Timeout: {}ms", timeout);
			return new GutenbergDownloader(baseUrl, timeout);
		} else {
			throw new IllegalArgumentException("Unknown book source: " + source);
		}
	}

	private static Properties loadConfiguration() {
		Properties properties = new Properties();

		try (InputStream input = IngestionApp.class.getClassLoader()
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