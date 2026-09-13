package com.urlshortener.util;

/**
 * The one definition of what a short code looks like. Used both to validate a
 * caller-supplied custom code and to constrain the public redirect's path
 * variable, so a root-level path that isn't shaped like a short code (e.g.
 * /favicon.ico, /swagger-ui.html) never reaches the redirect controller.
 */
public final class ShortCodes {

    /** Letters, digits, and hyphen; 3–20 characters. Generated codes are 8 alphanumerics. */
    public static final String PATTERN = "[A-Za-z0-9-]{3,20}";

    private ShortCodes() {
    }
}
