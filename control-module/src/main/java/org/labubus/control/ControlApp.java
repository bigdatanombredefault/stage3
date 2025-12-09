package org.labubus.control;

import io.javalin.Javalin;
import org.labubus.control.client.ServiceClient;
import org.labubus.control.controller.ControlController;
import org.labubus.control.service.PipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ControlApp {
	private static final Logger logger = LoggerFactory.getLogger(ControlApp.class);

	public static void main(String[] args) {
		try {
			Properties config = loadConfiguration();

			parseArguments(args, config);

			int port = Integer.parseInt(config.getProperty("server.port", "7000"));

			logger.info("Starting Control Module...");
			logger.info("Configuration:");
			logger.info("  Port: {}", port);

			String ingestionUrl = config.getProperty("service.ingestion.url", "http://localhost:7001");
			String indexingUrl = config.getProperty("service.indexing.url", "http://localhost:7002");
			String searchUrl = config.getProperty("service.search.url", "http://localhost:7003");

			logger.info("  Ingestion Service: {}", ingestionUrl);
			logger.info("  Indexing Service: {}", indexingUrl);
			logger.info("  Search Service: {}", searchUrl);

			int connectTimeout = Integer.parseInt(config.getProperty("http.connect.timeout", "10000"));
			int readTimeout = Integer.parseInt(config.getProperty("http.read.timeout", "60000"));
			ServiceClient client = new ServiceClient(connectTimeout, readTimeout);

			int checkInterval = Integer.parseInt(config.getProperty("pipeline.check.interval", "2000"));
			int maxRetries = Integer.parseInt(config.getProperty("pipeline.max.retries", "30"));

			PipelineService pipelineService = new PipelineService(
					client, ingestionUrl, indexingUrl, searchUrl, checkInterval, maxRetries
			);

			ControlController controller = new ControlController(pipelineService);

			Javalin app = Javalin.create(javalinConfig -> {
				javalinConfig.http.defaultContentType = "application/json";
				javalinConfig.showJavalinBanner = false;
			}).start(port);

			controller.registerRoutes(app);

			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				logger.info("Shutting down Control Module...");
				app.stop();
				logger.info("Control Module stopped");
			}));

			logger.info("Control Module started on port {}", port);
			logger.info("Use POST /pipeline/execute to run workflows");

		} catch (Exception e) {
			logger.error("Failed to start Control Module", e);
			printUsage();
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
		System.out.println("\n=== Control Module Usage ===\n");
		System.out.println("Usage: java -jar control-module-1.0.0.jar [options]\n");
		System.out.println("Options:");
		System.out.println("  --server.port <port>                  Server port (default: 7000)");
		System.out.println("  --service.ingestion.url <url>         Ingestion service URL (default: http://localhost:7001)");
		System.out.println("  --service.indexing.url <url>          Indexing service URL (default: http://localhost:7002)");
		System.out.println("  --service.search.url <url>            Search service URL (default: http://localhost:7003)");
		System.out.println("  -h, --help                            Show this help message\n");
		System.out.println("Examples:");
		System.out.println("  # Run with default settings");
		System.out.println("  java -jar control-module-1.0.0.jar\n");
		System.out.println("  # Run with custom port");
		System.out.println("  java -jar control-module-1.0.0.jar --server.port 8000\n");
		System.out.println("  # Run with custom service URLs");
		System.out.println("  java -jar control-module-1.0.0.jar --service.ingestion.url http://192.168.1.10:7001\n");
	}

	private static Properties loadConfiguration() {
		Properties properties = new Properties();

		try (InputStream input = ControlApp.class.getClassLoader()
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