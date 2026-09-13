package com.urlshortener.exception;

/**
 * A caller-supplied custom short code that fails format validation (wrong
 * length or characters). Maps to 400 — distinct from DuplicateShortCodeException
 * (409), which is reserved for a well-formed code that is already taken.
 */
public class InvalidShortCodeException extends RuntimeException {

    public InvalidShortCodeException(String message) {
        super(message);
    }
}
