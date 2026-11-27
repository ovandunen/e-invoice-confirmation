package com.tms.compliance.spi;

import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.domain.Invoice;
import com.tms.compliance.domain.Series;
import com.tms.compliance.domain.SubmissionResult;

import java.util.Optional;

/**
 * The <b>Target</b> interface of the adapter pattern.
 *
 * <p>This is what the Quarkus TMS Core depends on. It is country-agnostic by
 * construction: every method takes the data the Core already knows about
 * (Invoice, Series) and returns country-agnostic results (SubmissionResult).</p>
 *
 * <p>The two concrete implementations — {@code PortugalComplianceAdapter} and
 * {@code AngolaComplianceAdapter} — adapt this interface to their respective
 * Adaptees (Fiskaly / AT webservice for PT; AGT REST API for AO).</p>
 *
 * <h2>Three reasons this is the right boundary</h2>
 * <ol>
 *   <li><b>The Core never imports a country-specific class.</b> If it did, you
 *       would not be able to ship a single binary to both markets — you'd need
 *       two builds, two release trains, two bug surfaces.</li>
 *   <li><b>Each adapter owns its own async model.</b> AGT requires polling;
 *       Fiskaly returns synchronously. The interface hides that with
 *       {@link SubmissionResult.Status#PENDING}.</li>
 *   <li><b>Adding a country is a pure addition.</b> Mozambique (MZ) or Cabo
 *       Verde (CV) later = one new class + one new {@code @Alternative}. The
 *       Core and the other adapters do not change.</li>
 * </ol>
 *
 * <h2>What does NOT belong here</h2>
 * <ul>
 *   <li>Anything that returns a country-specific identifier type. Fold it into
 *       {@link SubmissionResult#authorityReference()} as a String.</li>
 *   <li>Anything that takes a country-specific request. If you genuinely need
 *       country-specific input, extend {@link Invoice} or add an overload — do
 *       not leak the Adaptee's request DTO through this interface.</li>
 *   <li>Caching, retry, idempotency. Those are cross-cutting and belong in a
 *       decorator, not baked into every adapter.</li>
 * </ul>
 */
public interface InvoiceComplianceAdapter {

    /**
     * Which country this adapter handles. Used by the {@code ComplianceSelector}
     * to route at runtime. One adapter per country per deployment.
     */
    CountryCode country();

    /**
     * Register (or look up) a document series with the authority.
     *
     * <p>Idempotent: calling twice with the same {@link Series#code()} returns
     * the same result. The adapter MUST NOT create a duplicate series if one
     * already exists.</p>
     */
    Series registerSeries(Series series);

    /**
     * Submit an invoice for fiscal validation.
     *
     * <p>The adapter is responsible for:
     * signing (RSA hash chain for PT, JWS RS256 for AO),
     * calling the authority,
     * mapping the response to a {@link SubmissionResult},
     * and retrieving the QR code if the authority returns one synchronously.</p>
     *
     * <p>If the authority is asynchronous (AGT), return
     * {@link SubmissionResult.Status#PENDING} with the authority reference, and
     * let the Core poll via {@link #queryStatus(String)}.</p>
     *
     * @throws ComplianceException if the request is malformed, totals don't reconcile,
     *         or the authority returned a hard error before accepting the request.
     */
    SubmissionResult submit(Invoice invoice);

    /**
     * Poll the current status of a previously-submitted invoice.
     *
     * <p>Used for asynchronous authorities (AGT) and as a fallback for
     * webhook loss. For synchronous authorities (Fiskaly, AT real-time), this
     * returns the cached final status.</p>
     *
     * @param authorityReference the value returned in
     *        {@link SubmissionResult#authorityReference()} from a prior submit.
     */
    SubmissionResult queryStatus(String authorityReference);

    /**
     * Fetch the QR code PNG (base64) for a previously-submitted invoice.
     *
     * <p>Useful when {@link #submit(Invoice)} returned PENDING and the QR was
     * not yet available. Returns empty if the authority has not produced it yet.
     */
    Optional<String> fetchQrCode(String authorityReference);
}
