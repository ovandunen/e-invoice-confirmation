package com.tms.core.billing;

import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.domain.DocumentType;
import com.tms.compliance.domain.Invoice;
import com.tms.compliance.domain.Series;
import com.tms.compliance.domain.SubmissionResult;
import com.tms.compliance.domain.TaxCode;
import com.tms.compliance.domain.TaxType;
import com.tms.compliance.facade.ComplianceFacade;
import com.tms.core.dispatch.Trip;
import com.tms.core.order.Order;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Turns a completed trip into a fiscally-valid invoice and submits it to the
 * right authority via the compliance facade.
 *
 * <p>This is the <b>seam</b> between the TMS Core and the compliance subsystem.
 * Everything country-specific (JWS, ATCUD, QR, reportUrl, async polling) is
 * hidden behind {@link ComplianceFacade}; the BillingService only knows about
 * orders, trips, prices and the standard IVA rate per country.</p>
 *
 * <h2>VAT rate per country</h2>
 * <ul>
 *   <li>Portugal: 23% IVA (NOR).</li>
 *   <li>Angola: 14% IVA (NOR), per the Angolan VAT Code (Lei n.º 7/19).</li>
 * </ul>
 *
 * <h2>Flow</h2>
 * <pre>
 *   Trip(COMPLETED) ──► buildInvoice(trip, order)
 *                  ──► ComplianceFacade.submit(country, invoice)
 *                  ──► SubmissionResult (ACCEPTED for PT, PENDING for AO)
 * </pre>
 * For AO the caller polls {@link #pollStatus(CountryCode, String)} later;
 * for PT the result is final immediately.
 */
@ApplicationScoped
public class BillingService {

    /** Standard IVA rate by country. In production this is per-product/per-region. */
    private static final BigDecimal PT_VAT = new BigDecimal("0.23");
    private static final BigDecimal AO_VAT = new BigDecimal("0.14");

    private final ComplianceFacade compliance;

    @Inject
    public BillingService(ComplianceFacade compliance) {
        this.compliance = compliance;
    }

    /**
     * Issue and submit a fiscal invoice for a completed trip.
     *
     * @param trip  the completed trip
     * @param order the originating order (carries price, customer, country)
     * @return the compliance submission result
     */
    public SubmissionResult invoiceTrip(Trip trip, Order order) {
        if (trip.status() != Trip.Status.COMPLETED) {
            throw new IllegalStateException("Cannot invoice a trip that is not COMPLETED: " + trip.id());
        }
        Invoice invoice = buildInvoice(trip, order);
        return compliance.submit(order.country(), invoice);
    }

    /**
     * Poll the fiscal status of a previously-submitted invoice (AGT deferred
     * validation). For PT this just returns the cached ACCEPTED.
     */
    public SubmissionResult pollStatus(CountryCode country, String authorityReference) {
        return compliance.queryStatus(country, authorityReference);
    }

    /** Build the country-agnostic {@link Invoice} from a trip + order. */
    Invoice buildInvoice(Trip trip, Order order) {
        boolean isPt = order.country() == CountryCode.PT;
        BigDecimal vatRate = isPt ? PT_VAT : AO_VAT;
        BigDecimal net = order.quotedPrice();
        BigDecimal tax = net.multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross = net.add(tax);

        Invoice.Line line = new Invoice.Line(
                "Transport service: " + order.pickup() + " → " + order.dropoff(),
                BigDecimal.ONE,
                net,
                TaxType.IVA, TaxCode.NOR, null,
                vatRate.multiply(new BigDecimal("100")), // percentage, not fraction
                net, tax);

        return new Invoice(
                UUID.randomUUID(),
                order.customerId(),                 // issuerTaxId — in production the operator's NIF
                "TMS Operator " + order.country().name(),
                null,                               // recipientTaxId (B2C)
                order.country().name(),             // recipientCountry
                DocumentType.FT,
                order.currency(),
                java.time.LocalDate.now(),
                List.of(line),
                net, tax, gross);
    }

    /** Register the default series for a country (idempotent; safe to call at startup). */
    public Series registerDefaultSeries(CountryCode country) {
        Series request = country == CountryCode.PT
                ? Series.forPortugal("FT 2026/A", DocumentType.FT, 2026, java.time.LocalDate.now(), null)
                : Series.forAngolaRequest(DocumentType.FT, 2026, "N");
        return compliance.registerSeries(country, request);
    }
}
