package com.github.safetytrick.sqlexplorer.commentary;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A bulk tag request that cannot be carried out as asked.
 *
 * <p>Annotated so it reaches the caller as a 400 rather than the 500 a plain
 * {@code IllegalArgumentException} produces here. Every message it carries
 * names something the caller can fix — a malformed tag name, an empty set, a
 * set over the cap — and reporting those as server faults sends people to logs
 * they cannot read (sqlx-agl, and the same defect fixed on the upload path in
 * sqlx-djj).
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BulkTagRequestException extends RuntimeException {
    public BulkTagRequestException(String message) {
        super(message);
    }
}
