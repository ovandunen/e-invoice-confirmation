package com.tms.compliance.adapter.angola;

import com.tms.compliance.adapter.angola.client.AgtClient;
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
 * Adapts the AGT e-invoice REST API to the country-agnostic
 * {@link InvoiceComplianceAdapter} interface.
 *
 * <p>This is the <b>Adapter</b>; the Adaptee is {@link AgtClient}; the Target is
 * {@link InvoiceComplianceAdapter}.</p>
 *
 * <h2>What this adapter absorbs (and PT does not have to)</h2>
 * <ul>
 *   <li><b>Asynchronous lifecycle.</b> AGT's {@code registarFactura} returns
 *       PENDENTE. The adapter returns {@link SubmissionResult.Status#PENDING}
 *       with a {@code authorityReference} encoding the AGT request id and
 *       factura id. The caller polls {@link #queryStatus(String)} later.</li>
 *   <li><b>JWS RS256 detached signing.</b> The adapter signs the invoice JSON
 *       with the taxpayer's RSA private key before calling
 *       {@link AgtClient#registarFactura}. PT does not do this because Fiskaly
 *       signs server-side.</li>
 *   <li><b>Two-step QR retrieval.</b> AGT only returns the QR after the factura
 *       is PROCESSADO. The adapter's {@link #submit} therefore returns
 *       {@code null} for QR; the caller fetches it via {@link #fetchQrCode}
 *       once {@link #queryStatus} returns ACCEPTED.</li>
 *   <li><b>AGT reference mapping.</b> The AGT factura id becomes
 *       {@link SubmissionResult#authorityReference()}.</li>
 * </ul>
 *
 * <p>Note how {@link PortugalComplianceAdapter} and this class have <em>identical</em>
 * public interfaces but wildly different internals. That is the adapter pattern
 * paying off: the TMS Core is unaware of any of this.</p>
 */
@ApplicationScoped
@CountryQualifier(CountryCode.AO)
public class AngolaComplianceAdapter implements InvoiceComplianceAdapter {

    private final AgtClient agt;

    @Inject
    public AngolaComplianceAdapter(AgtClient agt) {
        this.agt = agt;
    }

    @Override
    public CountryCode country() {
        return CountryCode.AO;
    }

    @Override
    public Series registerSeries(Series series) {
        Map<String, Object> resp = agt.criarSerie(
                series.code(), // AGT identifies the series by its code; NIF comes from Basic Auth
                series.code(),
                series.documentType()
        );
        String agtRef = (String) resp.get("agt_reference");
        return new Series(series.code(), series.documentType(), series.validFrom(), agtRef);
    }

    @Override
    public SubmissionResult submit(Invoice invoice) {
        reconcileTotals(invoice);

        // 1. Build the AGT invoice JSON (the AGT-specific request shape).
        String invoiceJson = toAgtInvoiceJson(invoice);

        // 2. Sign it as a detached JWS RS256 with the taxpayer's private key.
        //    In production this uses nimbus-jose-jwt or similar; here it's a stub.
        String jwsHeader = signAsJws(invoiceJson, invoice.issuerTaxId());

        // 3. Submit. AGT returns PENDENTE synchronously — never ACCEPTED.
        Map<String, Object> resp = agt.registarFactura(invoice.issuerTaxId(), invoiceJson, jwsHeader);
        String status = String.valueOf(resp.get("status"));
        if (!"PENDENTE".equals(status)) {
            throw new ComplianceException(
                    "AGT_UNEXPECTED_INITIAL_STATUS",
                    "AGT returned " + status + " (expected PENDENTE) for invoice " + invoice.id());
        }

        String requestId = (String) resp.get("request_id");
        String facturaId = (String) resp.get("factura_id");
        // authorityReference encodes both ids so queryStatus can poll and
        // fetchQrCode can later resolve the factura.
        String authorityReference = requestId + "|" + facturaId;
        return SubmissionResult.pending(invoice.id(), authorityReference, "AGT accepted for processing; poll for PROCESSADO");
    }

    @Override
    public SubmissionResult queryStatus(String authorityReference) {
        String[] parts = authorityReference.split("\\|", 2);
        String requestId = parts[0];
        String facturaId = parts.length > 1 ? parts[1] : null;

        Map<String, Object> estado = agt.consultarEstado(requestId);
        String status = String.valueOf(estado.get("status"));

        return switch (status) {
            case "PENDENTE"  -> SubmissionResult.pending(null, authorityReference, "Still pending at AGT");
            case "PROCESSADO" -> SubmissionResult.accepted(null, authorityReference, fetchQrOrEmpty(facturaId));
            case "REJEITADO" -> SubmissionResult.rejected(null, "AGT rejected factura " + facturaId);
            default -> throw new ComplianceException(
                    "AGT_UNKNOWN_STATUS",
                    "AGT returned unknown status " + status + " for request " + requestId);
        };
    }

    @Override
    public Optional<String> fetchQrCode(String authorityReference) {
        String[] parts = authorityReference.split("\\|", 2);
        if (parts.length < 2) return Optional.empty();
        String facturaId = parts[1];
        try {
            Map<String, Object> factura = agt.consultarFactura(facturaId);
            return Optional.ofNullable((String) factura.get("qr_code_base64"));
        } catch (IllegalStateException e) {
            // Not yet PROCESSADO — QR not available yet.
            return Optional.empty();
        }
    }

    // ---- private helpers --------------------------------------------------

    private String fetchQrOrEmpty(String facturaId) {
        if (facturaId == null) return null;
        try {
            return (String) agt.consultarFactura(facturaId).get("qr_code_base64");
        } catch (IllegalStateException e) {
            return null; // processed but QR not yet published — caller will fetch later
        }
    }

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

    private String toAgtInvoiceJson(Invoice invoice) {
        // In production: use Jackson to serialize to AGT's exact JSON schema.
        // Skeleton just shows the shape.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serie", "FT 2026/A");
        body.put("data", invoice.issueDate().toString());
        body.put("moeda", invoice.currency());
        body.put("nif_emitente", invoice.issuerTaxId());
        body.put("nif_destinatario", invoice.recipientTaxId());
        body.put("linhas", invoice.lines().stream()
                .map(l -> Map.<String, Object>of(
                        "descricao", l.description(),
                        "quantidade", l.quantity(),
                        "preco_unitario", l.unitPrice(),
                        "taxa_irt", l.vatRate(),
                        "liquido", l.lineNet(),
                        "imposto", l.lineTax()))
                .toList());
        body.put("total_liquido", invoice.netTotal());
        body.put("total_imposto", invoice.taxTotal());
        body.put("total", invoice.grossTotal());
        return body.toString(); // placeholder serialization
    }

    private String signAsJws(String payload, String nif) {
        // In production: load the taxpayer's RSA private key from a secrets store
        // (Vault / Kubernetes secret), build a JWS object with nimbus-jose-jwt,
        // sign detached (header.payload.signature with empty payload segment
        // since the payload is sent in the body), and return the protected header.
        // Skeleton returns a fake header.
        return "{\"alg\":\"RS256\",\"kid\":\"" + nif + "\",\"x5c\":[\"MIIB...\"]}";
    }
}
