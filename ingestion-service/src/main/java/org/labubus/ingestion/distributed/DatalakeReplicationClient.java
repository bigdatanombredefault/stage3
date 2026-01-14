package org.labubus.ingestion.distributed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replicates a downloaded book to one other node via the datalake receiver endpoint.
 */
public class DatalakeReplicationClient {
    private static final Logger logger = LoggerFactory.getLogger(DatalakeReplicationClient.class);

    private final HttpClient client;
    private final Random random;
    private final Duration timeout;

    public DatalakeReplicationClient(Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(timeout).build(), new Random(), timeout);
    }

    DatalakeReplicationClient(HttpClient client, Random random, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        this.random = Objects.requireNonNull(random);
        this.timeout = Objects.requireNonNull(timeout);
    }

    /**
     * Replicates the book content to exactly one other node.
     *
     * <p>Nodes are tried in random order (excluding {@code currentNodeIp}). If a node fails,
     * another node is tried until the list is exhausted.</p>
     *
     * @param bookId book identifier
     * @param title best-effort title (may be blank)
     * @param rawBookContent full raw book content (header + body)
     * @param currentNodeIp current node IP/host (excluded from replication)
     * @param clusterNodes list of cluster node IP/hosts
     * @param targetPort HTTP port where the receiver endpoint is exposed
     * @param endpointPath receiver path (e.g. {@code /api/datalake/store})
     * @throws IOException if all replication attempts fail
     */
    public void replicateOnce(
        int bookId,
        String title,
        String rawBookContent,
        String currentNodeIp,
        List<String> clusterNodes,
        int targetPort,
        String endpointPath
    ) throws IOException {
        List<String> candidates = candidates(currentNodeIp, clusterNodes);
        if (candidates.isEmpty()) {
            throw new IOException("No replication targets available (cluster list empty or only self)");
        }

        // Randomize try order, but keep reproducible structure for debugging.
        Collections.shuffle(candidates, random);

        IOException last = null;
        for (String target : candidates) {
            try {
                postMultipart(target, targetPort, endpointPath, bookId, title, rawBookContent);
                logger.info("Replicated book {} to {}:{}{}", bookId, target, targetPort, endpointPath);
                return;
            } catch (IOException e) {
                last = e;
                logger.warn("Replication attempt failed for {}:{}{} (book {}): {}", target, targetPort, endpointPath, bookId, e.getMessage());
            }
        }

        throw new IOException("Replication failed for all targets (book " + bookId + ")", last);
    }

    private List<String> candidates(String currentNodeIp, List<String> clusterNodes) {
        String self = normalizeHost(currentNodeIp);
        List<String> result = new ArrayList<>();
        for (String node : clusterNodes) {
            String host = normalizeHost(node);
            if (host == null || host.isBlank()) {
                continue;
            }
            if (self != null && host.equalsIgnoreCase(self)) {
                continue;
            }
            result.add(host);
        }
        return result;
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String trimmed = host.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Allow inputs like "10.0.0.1:7001" by stripping an explicit port.
        int idx = trimmed.indexOf(':');
        return idx >= 0 ? trimmed.substring(0, idx) : trimmed;
    }

    private void postMultipart(
        String host,
        int port,
        String endpointPath,
        int bookId,
        String title,
        String rawBookContent
    ) throws IOException {
        String boundary = "----labubus-boundary-" + System.nanoTime();
        byte[] body = multipartBody(boundary, bookId, title, rawBookContent);

        URI uri = URI.create("http://" + host + ":" + port + endpointPath);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while replicating to " + uri, e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Receiver responded with HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private static byte[] multipartBody(String boundary, int bookId, String title, String rawBookContent) {
        String safeTitle = title == null ? "" : title;
        StringBuilder sb = new StringBuilder();

        appendFormField(sb, boundary, "bookId", String.valueOf(bookId));
        appendFormField(sb, boundary, "title", safeTitle);

        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(bookId).append(".txt\"\r\n");
        sb.append("Content-Type: text/plain; charset=utf-8\r\n\r\n");
        sb.append(rawBookContent == null ? "" : rawBookContent);
        sb.append("\r\n");

        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendFormField(StringBuilder sb, String boundary, String name, String value) {
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
        sb.append(value == null ? "" : value);
        sb.append("\r\n");
    }
}
