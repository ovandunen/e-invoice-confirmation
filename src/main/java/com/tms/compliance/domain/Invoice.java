package com.tms.compliance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Country-agnostic invoice as the TMS Core knows it.
 *
 * <p>This is the <b>Target</b> data shape that the TMS Core produces and consumes.
 * It contains NO Portuguese-specific fields (no ATCUD, no hash chain) and NO
 * Angola-specific fields (no JWS, no AGT reference). Those are concerns of the
 * Adapters, not of the Core. The whole point of the adapter pattern is that the
 * Core never has to know which country it is invoicing.</p>
 *
 * <p>Design rule: if you ever find yourself adding a field here that only makes
 * sense for one country, stop — that field belongs on the adapter's request
 * extension, not on the core invoice.</p>
 */
public final class Invoice {

    /** Internal TMS id. Stable across retries. Used as the idempotency key downstream. */
    private final UUID id;

    /** Issuer's tax id in the issuing country (NIF in PT/AO — same shape, different registry). */
    private final String issuerTaxId;

    /** Recipient's tax id. May be null for B2C retail. */
    private final String recipientTaxId;

    /** ISO 4217 currency. EUR for PT, AOA for AO. */
    private final String currency;

    /** Document date (issue date). */
    private final LocalDate issueDate;

    /** Free-form business line items. VAT/IVA/IRT treatment is resolved by the adapter. */
    private final List<Line> lines;

    /** Pre-tax total, computed by the Core. Adapters MUST re-check and reject on mismatch. */
    private final BigDecimal netTotal;

    /** Tax total. */
    private final BigDecimal taxTotal;

    /** Gross total. netTotal + taxTotal must equal this exactly. */
    private final BigDecimal grossTotal;

    public Invoice(UUID id, String issuerTaxId, String recipientTaxId, String currency,
                   LocalDate issueDate, List<Line> lines,
                   BigDecimal netTotal, BigDecimal taxTotal, BigDecimal grossTotal) {
        this.id = id;
        this.issuerTaxId = issuerTaxId;
        this.recipientTaxId = recipientTaxId;
        this.currency = currency;
        this.issueDate = issueDate;
        this.lines = List.copyOf(lines); // defensive copy; immutability is sacred here
        this.netTotal = netTotal;
        this.taxTotal = taxTotal;
        this.grossTotal = grossTotal;
    }

    public UUID id()                  { return id; }
    public String issuerTaxId()       { return issuerTaxId; }
    public String recipientTaxId()    { return recipientTaxId; }
    public String currency()          { return currency; }
    public LocalDate issueDate()      { return issueDate; }
    public List<Line> lines()         { return lines; }
    public BigDecimal netTotal()      { return netTotal; }
    public BigDecimal taxTotal()      { return taxTotal; }
    public BigDecimal grossTotal()    { return grossTotal; }

    /** A single invoice line. Kept minimal — adapters map it to whatever the backend needs. */
    public record Line(String description, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal vatRate, BigDecimal lineNet, BigDecimal lineTax) {}
}
