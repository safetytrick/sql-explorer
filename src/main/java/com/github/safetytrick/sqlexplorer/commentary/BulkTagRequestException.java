package com.github.safetytrick.sqlexplorer.commentary;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BulkTagRequestException extends RuntimeException {
    public BulkTagRequestException(String message) {
        super(message);
    }
}
