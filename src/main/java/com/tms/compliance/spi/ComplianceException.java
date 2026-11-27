package com.tms.compliance.spi;

/**
 * Thrown when a compliance request cannot be completed because of a business
 * or protocol error (malformed invoice, total mismatch, authority rejected,
 * series not registered, signature failure, etc.).
 *
 * <p>Transient infrastructure errors (timeout, 5xx, network) are NOT this
 * exception — they should be caught and retried by the caller or a decorator,
 * because re-trying with the same idempotency key is safe. This exception means
 * "do not retry as-is; something is wrong with the request".</p>
 */
public class ComplianceException extends RuntimeException {

    /** Stable machine-readable code, e.g. "TOTALS_MISMATCH", "SERIES_NOT_REGISTERED". */
    private final String code;

    public ComplianceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ComplianceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
