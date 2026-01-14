package org.labubus.ingestion.service;

import java.io.IOException;

/**
 * Utilities for parsing raw Project Gutenberg book content.
 */
public final class BookContentParser {
    private BookContentParser() {}

    /**
     * Splits a raw book into header and body, using Project Gutenberg start/end markers.
     *
     * @param content raw downloaded content
     * @return a two-element array: [header, body]
     * @throws IOException if the expected markers are missing
     */
    public static String[] splitHeaderBody(String content) throws IOException {
        String startMarker = "*** START OF";
        int startIndex = content.indexOf(startMarker);

        if (startIndex == -1) {
            throw new IOException("Invalid book format: START marker not found.");
        }

        String endMarker = "*** END OF";
        int endIndex = content.indexOf(endMarker, startIndex);

        if (endIndex == -1) {
            throw new IOException("Invalid book format: END marker not found.");
        }

        String header = content.substring(0, startIndex).trim();
        String body = content.substring(startIndex, endIndex).trim();

        body = body.replaceFirst("\\*\\*\\* START OF[^\\n]*\\n", "").trim();

        if (header.isEmpty()) {
            throw new IOException("Invalid book format: Header is empty");
        }
        if (body.isEmpty()) {
            throw new IOException("Invalid book format: Body is empty");
        }

        return new String[] { header, body };
    }
}
