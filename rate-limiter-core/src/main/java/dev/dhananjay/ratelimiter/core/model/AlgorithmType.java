package dev.dhananjay.ratelimiter.core.model;

public enum AlgorithmType {

    TOKEN_BUCKET,
    SLIDING_WINDOW;

    public static AlgorithmType fromString(String value) {
        if (value == null || value.isBlank()) {
            return TOKEN_BUCKET;
        }
        try {
            return AlgorithmType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return TOKEN_BUCKET;
        }
    }
}
