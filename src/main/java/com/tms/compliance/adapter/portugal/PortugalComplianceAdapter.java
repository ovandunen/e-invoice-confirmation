package com.tms.compliance.adapter.portugal;

import com.tms.compliance.adapter.portugal.client.FiskalyClient;
import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.domain.Invoice;
import com.tms.compliance.domain.Series;
import com.tms.compliance.domain.SubmissionResult;
import com.tms.compliance.spi.ComplianceException;
import com.tms.compliance.spi.CountryQualifier;
import com.tms.compliance.spi.InvoiceComplianceAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Adapts the Fiskaly SIGN PT REST API to the country-agnostic
 * {@link InvoiceComplianceAdapter} interface.
 *
 * <p>This is the <b>Adapter</b> in the GoF pattern. The Adaptee is
 * {@link FiskalyClient}. The Target is {@link InvoiceComplianceAdapter}.</p>
 *
 * <h2>What this adapter absorbs</h2>
 * <ul>
 *   <li><b>Synchronous response model.</b> Fiskaly signs and returns ACCEPTED
 *       immediately. The adapter never returns PENDING.</li>
 *   <li><b>Portuguese-specific signing.</b> The RSA hash-chain signing happens
 *       server-side at Fiskaly — the adapter just passes the normalized
 *       request. (If we were calling the AT webservice directly, this is where
 *       the hash-chain code would live instead.)</li>
 *   <li><b>ATCUD mapping.</b> Fiskaly's {@code atcud} field becomes
 *       {@link SubmissionResult#authorityReference()}.</li>
 *   <li><b>QR retrieval.</b> Fiskaly returns the QR inline with the signed
 *       invoice; no separate fetch needed. The QR is stored under the
 *       {@code "qrCodeBase64Png"} key in {@link SubmissionResult#artefacts()}.</li>
 *   <li><b>Totals reconciliation.</b> The adapter re-checks net+tax=gross
 *       before calling Fiskaly, because Fiskaly will reject anyway — better to
 *       fail fast with a clear code.</li>
 * </ul>
 *
 * <p>The adapter is {@code @ApplicationScoped} and {@code @PT}: at startup
 * Quarkus collects all beans implementing {@link InvoiceComplianceAdapter}
 * tagged with a {@link CountryQualifier}, and the
 * {@code ComplianceSelector} routes by country.</p>
 */
@ApplicationScoped
@CountryQualifier(CountryCode.PT)
public class PortugalComplianceAdapter implements InvoiceComplianceAdapter {

    private final FiskalyClient fiskaly;

    @Inject
    public PortugalComplianceAdapter(FiskalyClient fiskaly) {
        this.fiskaly = fiskaly;
    }

    @Override
    public CountryCode country() {
        return CountryCode.PT;
    }

    @Override
    public Series registerSeries(Series series) {
        // Authenticate (Fiskaly uses short-lived Bearer tokens).
        fiskaly.authenticate();
        Map<String, Object> resp = fiskaly.registerSeries(
                /* nif — in PT the issuer NIF is the merchant's own NIF, looked up
                   from the caller context. For the skeleton we pass a placeholder;
                   in production the adapter would hold a per-merchant Fiskaly org. */
                "PT-DEFAULT-NIF",
                series.code(),
                series.documentType().name()
        );
        String validationCode = (String) resp.get("atcud_validation");
        return Series.forPortugal(
                series.code(),
                series.documentType(),
                series.seriesYear(),
                series.validFrom(),
                validationCode);
    }

    @Override
    public SubmissionResult submit(Invoice invoice) {
        reconcileTotals(invoice);

        fiskaly.authenticate();

        Map<String, Object> request = toFiskalyRequest(invoice);
        Map<String, Object> response = fiskaly.submitSignedInvoice(invoice.issuerTaxId(), request);

        String state = String.valueOf(response.get("state"));
        if (!"ACCEPTED".equals(state)) {
            throw new ComplianceException(
                    "FISKALY_REJECTED",
                    "Fiskaly returned state " + state + " for invoice " + invoice.id());
        }

        String atcud = (String) response.get("atcud");
        String qr = (String) response.get("qr_code_base64");
        String fiskalyId = (String) response.get("fiskaly_id");

        // For PT, authorityReference encodes both the Fiskaly id (for later fetch)
        // and the ATCUD (for printing on the invoice). Pipe-separated for simplicity.
        String authorityReference = fiskalyId + "|" + atcud;
        // PT genuinely returns a base64 QR PNG inline — store it under the
        // conventional key. AO stores reportUrl instead.
        Map<String, String> artefacts = qr != null ? Map.of("qrCodeBase64Png", qr) : Map.of();
        return SubmissionResult.accepted(invoice.id(), authorityReference, artefacts);
    }

    @Override
    public SubmissionResult queryStatus(String authorityReference) {
        String[] parts = authorityReference.split("\\|", 2);
        String fiskalyId = parts[0];
        Map<String, Object> inv = fiskaly.getInvoice("PT-DEFAULT-NIF", fiskalyId);
        String state = String.valueOf(inv.get("state"));
        String qr = (String) inv.get("qr_code_base64");
        SubmissionResult.Status mapped = "ACCEPTED".equals(state)
                ? SubmissionResult.Status.ACCEPTED
                : SubmissionResult.Status.REJECTED;
        Map<String, String> artefacts = qr != null ? Map.of("qrCodeBase64Png", qr) : Map.of();
        return new SubmissionResult(null, mapped, authorityReference, artefacts, "");
    }

    @Override
    public Optional<String> fetchQrCode(String authorityReference) {
        String[] parts = authorityReference.split("\\|", 2);
        Map<String, Object> inv = fiskaly.getInvoice("PT-DEFAULT-NIF", parts[0]);
        return Optional.ofNullable((String) inv.get("qr_code_base64"));
    }

    // ---- private helpers --------------------------------------------------

    private void reconcileTotals(Invoice invoice) {
        BigDecimal computedNet = invoice.lines().stream()
                .map(Invoice.Line::lineNet)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal computedTax = invoice.lines().stream()
                .map(Invoice.Line::lineTax)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (computedNet.compareTo(invoice.netTotal()) != 0
                || computedTax.compareTo(invoice.taxTotal()) != 0
                || invoice.netTotal().add(invoice.taxTotal()).compareTo(invoice.grossTotal()) != 0) {
            throw new ComplianceException(
                    "TOTALS_MISMATCH",
                    "Invoice " + invoice.id() + " totals do not reconcile");
        }
    }

    private Map<String, Object> toFiskalyRequest(Invoice invoice) {
        // Map the core Invoice to Fiskaly's expected request shape.
        // In production this would use Fiskaly's exact schema (GOBL or Fiskaly native).
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("series_code", "FT 2026/A"); // resolved from invoice context in production
        request.put("issue_date", invoice.issueDate().toString());
        request.put("currency", invoice.currency());
        request.put("document_type", invoice.documentType().name());
        request.put("recipient_nif", invoice.recipientTaxId());
        request.put("recipient_country", invoice.recipientCountry());
        request.put("company_name", invoice.issuerName());
        request.put("lines", invoice.lines().stream()
                .map(l -> Map.<String, Object>of(
                        "description", l.description(),
                        "quantity", l.quantity(),
                        "unit_price", l.unitPrice(),
                        "tax_type", l.taxType().name(),
                        "tax_code", l.taxCode() != null ? l.taxCode().name() : "NOR",
                        "tax_percentage", l.taxPercentage() != null ? l.taxPercentage() : BigDecimal.ZERO,
                        "net", l.lineNet(),
                        "tax", l.lineTax()))
                .toList());
        request.put("net_total", invoice.netTotal());
        request.put("tax_total", invoice.taxTotal());
        request.put("gross_total", invoice.grossTotal());
        return request;
    }
}
