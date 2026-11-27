package com.tms.compliance.bootstrap;

import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.domain.Invoice;
import com.tms.compliance.domain.Series;
import com.tms.compliance.domain.SubmissionResult;
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

        // Register a series first (idempotent in both adapters).
        Series series = new Series("FT 2026/A", "invoice", LocalDate.now(), null);
        facade.registerSeries(cc, series);

        // Build a minimal test invoice.
        Invoice.Line line = new Invoice.Line(
                "Demo parcel", new BigDecimal("1"), new BigDecimal("12.50"),
                new BigDecimal("0.23"), new BigDecimal("12.50"), new BigDecimal("2.88"));
        Invoice invoice = new Invoice(
                UUID.randomUUID(),
                cc == CountryCode.PT ? "PT500100200" : "AO541700100",
                null,
                cc == CountryCode.PT ? "EUR" : "AOA",
                LocalDate.now(),
                List.of(line),
                new BigDecimal("12.50"),
                new BigDecimal("2.88"),
                new BigDecimal("15.38"));

        return facade.submit(cc, invoice);
    }
}
