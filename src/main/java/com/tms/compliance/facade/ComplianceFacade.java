package com.tms.compliance.facade;

import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.domain.Invoice;
import com.tms.compliance.domain.Series;
import com.tms.compliance.domain.SubmissionResult;
import com.tms.compliance.selector.ComplianceSelector;
import com.tms.compliance.spi.InvoiceComplianceAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The single entry point the Quarkus TMS Core calls.
 *
 * <p>This is the public <b>Facade</b> over the whole adapter subsystem. The TMS
 * Core injects {@code ComplianceFacade} (and only this), passes the
 * {@link CountryCode} on every call, and gets back country-agnostic results.</p>
 *
 * <h2>Why a facade on top of the selector?</h2>
 * <ol>
 *   <li><b>Stable API.</b> If we later add cross-cutting concerns — logging,
 *       metrics, retry, idempotency-key dedup, per-tenant credential lookup —
 *       they go here as decorators, and the TMS Core never changes.</li>
 *   <li><b>Single dependency.</b> The Core depends on one class, not on the
 *       selector + the SPI + the domain. Cleaner module graph.</li>
 *   <li><b>Test boundary.</b> Tests of the Core mock the facade; tests of the
 *       adapters don't need the Core. Two clean test pyramids.</li>
 * </ol>
 *
 * <h2>Per-request country routing</h2>
 * In a multi-tenant deployment the {@link CountryCode} comes from the operator's
 * tenant context (typically a JWT claim or a tenant lookup by NIF). The Core is
 * responsible for resolving it; the facade just dispatches.
 */
@ApplicationScoped
public class ComplianceFacade {

    private final ComplianceSelector selector;

    @Inject
    public ComplianceFacade(ComplianceSelector selector) {
        this.selector = selector;
    }

    /**
     * Register a document series for a given country.
     */
    public Series registerSeries(CountryCode country, Series series) {
        return adapter(country).registerSeries(series);
    }

    /**
     * Submit an invoice for fiscal validation in the given country.
     *
     * <p>Returns a {@link SubmissionResult} that is either:
     * <ul>
     *   <li>{@link SubmissionResult.Status#ACCEPTED ACCEPTED} — synchronous
     *       authority (Fiskaly/AT); the QR is already in the result.</li>
     *   <li>{@link SubmissionResult.Status#PENDING PENDING} — asynchronous
     *       authority (AGT); call {@link #queryStatus} later.</li>
     *   <li>{@link SubmissionResult.Status#REJECTED REJECTED} — never returned
     *       here; on rejection the adapter throws {@link com.tms.compliance.spi.ComplianceException}.</li>
     * </ul>
     * </p>
     */
    public SubmissionResult submit(CountryCode country, Invoice invoice) {
        return adapter(country).submit(invoice);
    }

    /**
     * Poll the status of a previously-submitted invoice.
     */
    public SubmissionResult queryStatus(CountryCode country, String authorityReference) {
        return adapter(country).queryStatus(authorityReference);
    }

    /**
     * Fetch the QR code for a previously-submitted invoice. Returns empty if
     * the authority has not produced it yet (common for AGT before PROCESSADO).
     */
    public java.util.Optional<String> fetchQrCode(CountryCode country, String authorityReference) {
        return adapter(country).fetchQrCode(authorityReference);
    }

    private InvoiceComplianceAdapter adapter(CountryCode country) {
        return selector.forCountry(country);
    }
}
