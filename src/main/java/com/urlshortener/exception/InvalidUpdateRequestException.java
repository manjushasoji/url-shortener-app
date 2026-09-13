package com.urlshortener.exception;

public class InvalidUpdateRequestException extends RuntimeException {

    public InvalidUpdateRequestException(String message) {
        super(message);
    }
}
