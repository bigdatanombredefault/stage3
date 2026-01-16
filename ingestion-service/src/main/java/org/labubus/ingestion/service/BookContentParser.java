package org.labubus.ingestion.service;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for parsing raw Project Gutenberg book content.
 */
public final class BookContentParser {
    private BookContentParser() {}

    private static final Pattern START_MARKER = Pattern.compile("(?im)^\\*\\*\\*\\s*START\\s+OF.*$", Pattern.MULTILINE);
    private static final Pattern END_MARKER = Pattern.compile("(?im)^\\*\\*\\*\\s*END\\s+OF.*$", Pattern.MULTILINE);

    /**
     * Splits a raw book into header and body, using Project Gutenberg start/end markers.
     *
     * @param content raw downloaded content
     * @return a two-element array: [header, body]
     * @throws IOException if the expected markers are missing
     */
    public static String[] splitHeaderBody(String content) throws IOException {
        if (content == null || content.isBlank()) {
            throw new BookFormatException("Invalid book format: content is empty");
        }

        Marker start = findMarkerOrNull(START_MARKER, content);
        if (start == null) {
            throw new BookFormatException("Invalid book format: START marker not found");
        }

        Marker end = findMarkerOrNull(END_MARKER, content.substring(start.endOffset));
        if (end == null) {
            throw new BookFormatException("Invalid book format: END marker not found");
        }
        int endStartOffset = start.endOffset + end.startOffset;

        String header = content.substring(0, start.startOffset).trim();
        String body = content.substring(start.endOffset, endStartOffset).trim();

        if (header.isEmpty()) {
            throw new BookFormatException("Invalid book format: Header is empty");
        }
        if (body.isEmpty()) {
            throw new BookFormatException("Invalid book format: Body is empty");
        }

        return new String[] { header, body };
    }

    private static Marker findMarkerOrNull(Pattern markerPattern, String content) {
        Matcher m = markerPattern.matcher(content);
        if (!m.find()) {
            return null;
        }
        return new Marker(m.start(), m.end());
    }

    private record Marker(int startOffset, int endOffset) {}
}
