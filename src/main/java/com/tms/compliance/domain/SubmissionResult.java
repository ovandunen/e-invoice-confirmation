package com.tms.compliance.domain;

import java.util.UUID;

/**
 * Outcome of submitting an invoice to the tax authority.
 *
 * <p>The shape is identical regardless of country. Country-specific identifiers
 * (ATCUD, AGT reference) are folded into {@link #authorityReference}.</p>
 *
 * <p>Both PT and AO are asynchronous-friendly: a submission can return
 * {@link Status#PENDING} immediately, with the caller polling or waiting for a
 * webhook later. This matches reality — AGT returns PENDENTE and must be polled;
 * Fiskaly/AT are near-real-time but still benefit from the same shape.</p>
 */
public record SubmissionResult(

        /** The TMS invoice id that was submitted. */
        UUID invoiceId,

        /** Status the authority reported for this submission. */
        Status status,

        /** Authority-side identifier (ATCUD in PT, AGT factura id in AO). */
        String authorityReference,

        /** QR code as base64 PNG, ready to embed in a PDF or print on a thermal receipt. Null if not yet available. */
        String qrCodeBase64Png,

        /** Free-form technical message — error reason, polling hint, or empty. */
        String message) {

    public enum Status {
        ACCEPTED,   // authority accepted, invoice is fiscally valid
        PENDING,    // authority accepted the request, final status will follow (async)
        REJECTED    // authority rejected; check message for reason code
    }

    public static SubmissionResult accepted(UUID invoiceId, String authorityReference, String qrCodeBase64Png) {
        return new SubmissionResult(invoiceId, Status.ACCEPTED, authorityReference, qrCodeBase64Png, "");
    }

    public static SubmissionResult pending(UUID invoiceId, String authorityReference, String message) {
        return new SubmissionResult(invoiceId, Status.PENDING, authorityReference, null, message);
    }

    public static SubmissionResult rejected(UUID invoiceId, String message) {
        return new SubmissionResult(invoiceId, Status.REJECTED, null, null, message);
    }
}
