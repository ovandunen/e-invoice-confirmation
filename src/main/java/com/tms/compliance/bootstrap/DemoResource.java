package com.tms.compliance.bootstrap;

import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.domain.DocumentType;
import com.tms.compliance.domain.Invoice;
import com.tms.compliance.domain.Series;
import com.tms.compliance.domain.SubmissionResult;
import com.tms.compliance.domain.TaxCode;
import com.tms.compliance.domain.TaxType;
import com.tms.compliance.facade.ComplianceFacade;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Demo endpoint that exercises both adapters through the facade.
 *
 * <p>Not part of the production API — exists to prove the wiring works at
 * startup. Hit {@code GET /demo/issue?country=PT} or {@code country=AO} and
 * watch the adapter pattern route the call to the right backend.</p>
 *
 * <p>For AO the response is {@link SubmissionResult.Status#PENDING} with the
 * AGT requestID — call {@code GET /demo/poll?country=AO&ref=...} to simulate
 * polling {@code obterEstado}.</p>
 */
@Path("/demo")
public class DemoResource {

    @Inject
    ComplianceFacade facade;

    @GET
    @Path("/issue")
    @Produces(MediaType.APPLICATION_JSON)
    public SubmissionResult issue(@QueryParam("country") String country) {
        CountryCode cc = CountryCode.valueOf(country.toUpperCase());
        boolean isPt = cc == CountryCode.PT;

        // Register a series first (idempotent in both adapters).
        Series series = isPt
                ? Series.forPortugal("FT 2026/A", DocumentType.FT, 2026, LocalDate.now(), null)
                : Series.forAngolaRequest(DocumentType.FT, 2026, "N");
        facade.registerSeries(cc, series);

        // Build a minimal test invoice matching the new Invoice constructor.
        Invoice.Line line = new Invoice.Line(
                "Demo parcel",
                new BigDecimal("1"),
                new BigDecimal("12.50"),
                TaxType.IVA, TaxCode.NOR, null,
                new BigDecimal("14"),                 // Angola IVA normal rate = 14%
                new BigDecimal("12.50"),
                new BigDecimal("1.75"));
        Invoice invoice = new Invoice(
                UUID.randomUUID(),
                isPt ? "PT500100200" : "AO541700100",
                isPt ? "Demo Courier Lda." : "Demo Courier Angola Lda.",
                null,                                  // recipientTaxId (B2C)
                isPt ? "PT" : "AO",                    // recipientCountry
                DocumentType.FT,
                isPt ? "EUR" : "AOA",
                LocalDate.now(),
                List.of(line),
                new BigDecimal("12.50"),
                new BigDecimal("1.75"),
                new BigDecimal("14.25"));

        return facade.submit(cc, invoice);
    }

    @GET
    @Path("/poll")
    @Produces(MediaType.APPLICATION_JSON)
    public SubmissionResult poll(@QueryParam("country") String country,
                                 @QueryParam("ref") String authorityReference) {
        CountryCode cc = CountryCode.valueOf(country.toUpperCase());
        return facade.queryStatus(cc, authorityReference);
    }
}
