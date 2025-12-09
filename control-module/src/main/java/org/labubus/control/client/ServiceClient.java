package org.labubus.control.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ServiceClient {
	private static final Logger logger = LoggerFactory.getLogger(ServiceClient.class);
	private final HttpClient httpClient;
	private final Gson gson;
	private final int connectTimeout;
	private final int readTimeout;

	public ServiceClient(int connectTimeout, int readTimeout) {
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(connectTimeout))
				.build();
		this.gson = new Gson();
	}

	/**
	 * Send GET request
	 */
	public String get(String url) throws IOException, InterruptedException {
		logger.debug("GET {}", url);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofMillis(readTimeout))
				.GET()
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() >= 400) {
			throw new IOException("HTTP " + response.statusCode() + " for URL: " + url);
		}

		return response.body();
	}

	/**
	 * Send POST request (no body)
	 */
	public String post(String url) throws IOException, InterruptedException {
		logger.debug("POST {}", url);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofMillis(readTimeout))
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() >= 400) {
			throw new IOException("HTTP " + response.statusCode() + " for URL: " + url + " - " + response.body());
		}

		return response.body();
	}

	/**
	 * Send POST request with JSON body
	 */
	public String post(String url, String jsonBody) throws IOException, InterruptedException {
		logger.debug("POST {} with body: {}", url, jsonBody);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofMillis(readTimeout))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() >= 400) {
			throw new IOException("HTTP " + response.statusCode() + " for URL: " + url + " - " + response.body());
		}

		return response.body();
	}

	/**
	 * Check if a service is reachable
	 */
	public boolean isReachable(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(Duration.ofMillis(connectTimeout))
					.GET()
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() < 400;

		} catch (Exception e) {
			logger.debug("Service unreachable: {} - {}", url, e.getMessage());
			return false;
		}
	}

	/**
	 * Parse JSON response
	 */
	public JsonObject parseJson(String json) {
		return gson.fromJson(json, JsonObject.class);
	}

	/**
	 * Convert object to JSON
	 */
	public String toJson(Object obj) {
		return gson.toJson(obj);
	}
}