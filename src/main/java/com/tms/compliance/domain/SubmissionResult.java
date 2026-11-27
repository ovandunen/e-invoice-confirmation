package com.tms.compliance.domain;

import java.util.Map;
import java.util.UUID;

/**
 * Outcome of submitting an invoice to the tax authority.
 *
 * <p>The shape is identical regardless of country. Country-specific identifiers
 * and artefacts are folded into generic fields so the TMS Core never has to know
 * whether it is dealing with a Portuguese ATCUD or an Angolan requestID.</p>
 *
 * <h2>Artefacts map</h2>
 * <p>Different authorities return different validation artefacts:</p>
 * <ul>
 *   <li><b>Portugal (Fiskaly)</b> returns a base64 PNG QR code inline → key
 *       {@code "qrCodeBase64Png"}.</li>
 *   <li><b>Angola (AGT)</b> returns a {@code reportUrl} (URL to the AGT-validated
 *       invoice report) via {@code consultarFactura} — there is NO base64 QR in
 *       the AGT spec; the printed QR is generated client-side by the emitter
 *       software from the validated document data → key {@code "reportUrl"}.</li>
 * </ul>
 * <p>The {@link #artefacts} map lets each adapter populate whatever it actually
 * received, without forcing a single QR field on authorities that don't have one.</p>
 *
 * <h2>Async model</h2>
 * <p>Both PT and AO expose a deferred-validation pattern, but at different layers:</p>
 * <ul>
 *   <li><b>Fiskaly (PT)</b> signs and returns ACCEPTED synchronously.</li>
 *   <li><b>AGT (AO)</b> returns a synchronous receipt (requestID) with validation
 *       deferred — the caller polls {@code obterEstado} and maps resultCode to
 *       ACCEPTED/PENDING/REJECTED.</li>
 * </ul>
 */
public record SubmissionResult(

        /** The TMS invoice id that was submitted. */
        UUID invoiceId,

        /** Status the authority reported for this submission. */
        Status status,

        /** Authority-side identifier (ATCUD for PT, AGT requestID for AO). */
        String authorityReference,

        /** Validation artefacts: qrCodeBase64Png, reportUrl, hash, etc. Empty if not yet available. */
        Map<String, String> artefacts,

        /** Free-form technical message — error reason, polling hint, or empty. */
        String message) {

    public enum Status {
        ACCEPTED,   // authority accepted, invoice is fiscally valid
        PENDING,    // receipt acknowledged; final validation will follow (async)
        REJECTED    // authority rejected; check message for reason code
    }

    public static SubmissionResult accepted(UUID invoiceId, String authorityReference, Map<String, String> artefacts) {
        return new SubmissionResult(invoiceId, Status.ACCEPTED, authorityReference, artefacts, "");
    }

    public static SubmissionResult pending(UUID invoiceId, String authorityReference, String message) {
        return new SubmissionResult(invoiceId, Status.PENDING, authorityReference, Map.of(), message);
    }

    public static SubmissionResult rejected(UUID invoiceId, String message) {
        return new SubmissionResult(invoiceId, Status.REJECTED, null, Map.of(), message);
    }
}
