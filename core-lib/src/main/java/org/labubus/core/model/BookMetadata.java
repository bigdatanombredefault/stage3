package org.labubus.core.model;

import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;

public record BookMetadata(
        int bookId,
        String title,
        String author,
        String language,
        Integer year,
        String path
) implements Serializable {

    @NotNull
    @Override
    public String toString() {
        return String.format("BookMetadata{id=%d, title='%s', author='%s', lang='%s', year=%d}",
                bookId, title, author, language, year);
    }

    @Serial
    private static final long serialVersionUID = 1L;
}