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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapts the AGT e-invoice REST API (DS.120 v1.1) to the country-agnostic
 * {@link InvoiceComplianceAdapter} interface.
 *
 * <p>This is the <b>Adapter</b>; the Adaptee is {@link AgtClient}; the Target is
 * {@link InvoiceComplianceAdapter}.</p>
 *
 * <h2>What this adapter absorbs (per the real DS.120 spec)</h2>
 * <ul>
 *   <li><b>Synchronous-receipt + deferred-validation model.</b>
 *       {@code registarFactura} is synchronous (§4.1.2) and returns a
 *       {@code requestID} immediately. The actual invoice validation happens
 *       later — the caller polls {@code obterEstado} and maps
 *       {@code resultCode} (0/1/2/7/8/9) to ACCEPTED/PENDING/REJECTED.
 *       The adapter's {@link #submit} therefore returns PENDING with the
 *       requestID; {@link #queryStatus} calls {@code obterEstado}.</li>
 *
 *   <li><b>Two JWS RS256 signatures on every registarFactura.</b>
 *       <ul>
 *         <li>{@code jwsSoftwareSignature} — over {@code softwareInfo}, signed
 *             with the software producer's private key
 *             ({@link SoftwareCredentialProvider}).</li>
 *         <li>{@code jwsDocumentSignature} — over the document fields
 *             (documentNo, taxRegistrationNumber, documentType, documentDate,
 *             customerTaxID, customerCountry, companyName, documentTotals),
 *             signed with the emitter's private key
 *             ({@link EmitterCredentialProvider}).</li>
 *       </ul>
 *       Subsequent calls ({@code obterEstado}, {@code solicitarSerie},
 *       {@code validarDocumento}) carry a third signature,
 *       {@code jwsSignature}, over {@code taxRegistrationNumber + requestID}
 *       (or relevant fields), also with the emitter's key.</li>
 *
 *   <li><b>Batch submission.</b> AGT accepts up to 30 invoices per
 *       {@code registarFactura} call (§4.1.6). The per-invoice
 *       {@link #submit(Invoice)} is a batch-of-one; a {@code submitBatch}
 *       would build a multi-document payload.</li>
 *
 *   <li><b>HTTP Basic Auth</b> with per-emitter Username/Password (§4.1.5),
 *       enforced by OWSM. The adapter resolves credentials per-tenant via
 *       {@link EmitterCredentialProvider}.</li>
 *
 *   <li><b>No base64 QR PNG — the artefact is a reportUrl.</b>
 *       {@code consultarFactura} returns a {@code reportUrl} inside the
 *       document object (§4.4); there is no base64 QR in the AGT spec. The
 *       printed QR is generated client-side by the emitter software from the
 *       validated document data. The adapter exposes the reportUrl via the
 *       {@link SubmissionResult#artefacts()} map under key {@code "reportUrl"}.</li>
 *
 *   <li><b>Mandatory softwareInfo on every request.</b> Every call carries
 *       the TMS vendor's identity (productId, productVersion,
 *       softwareValidationNumber) plus its JWS signature. Without
 *       softwareValidationNumber, every call fails with error E07.</li>
 *
 *   <li><b>Rich status mapping.</b> {@code obterEstado} resultCode:
 *       0→ACCEPTED, 1→partial (some valid, some invalid), 2→REJECTED (all
 *       invalid), 7→PENDING (premature, retry), 8→PENDING (still processing),
 *       9→REJECTED (cancelled).</li>
 * </ul>
 *
 * <p>Note how {@link com.tms.compliance.adapter.portugal.PortugalComplianceAdapter}
 * and this class have <em>identical</em> public interfaces but wildly different
 * internals. That is the adapter pattern paying off: the TMS Core is unaware of
 * any of this.</p>
 */
@ApplicationScoped
@CountryQualifier(CountryCode.AO)
public class AngolaComplianceAdapter implements InvoiceComplianceAdapter {

    private static final String SCHEMA_VERSION = "1.0";

    private final AgtClient agt;
    private final SoftwareCredentialProvider softwareCredential;
    private final EmitterCredentialProvider emitterCredential;

    @Inject
    public AngolaComplianceAdapter(AgtClient agt,
                                   SoftwareCredentialProvider softwareCredential,
                                   EmitterCredentialProvider emitterCredential) {
        this.agt = agt;
        this.softwareCredential = softwareCredential;
        this.emitterCredential = emitterCredential;
    }

    @Override
    public CountryCode country() {
        return CountryCode.AO;
    }

    // ---------------------------------------------------------------- submit

    @Override
    public SubmissionResult submit(Invoice invoice) {
        reconcileTotals(invoice);

        // Build the full RegistarFacturaRequest per DS.120 §4.1.
        Map<String, Object> request = buildRegistarFacturaRequest(List.of(invoice));

        Map<String, Object> response = agt.registarFactura(request);

        // AGT returns requestID synchronously (validation is deferred).
        String requestId = String.valueOf(response.get("requestID"));
        if (requestId == null || "null".equals(requestId)) {
            List<Map<String, Object>> errors = errorList(response);
            throw new ComplianceException(
                    "AGT_SUBMIT_REJECTED",
                    "AGT rejected the submission: " + errors);
        }

        // authorityReference encodes requestID + the documentNo so queryStatus
        // can poll and fetchReportUrl can later resolve the invoice.
        String documentNo = deriveDocumentNo(invoice);
        String authorityReference = requestId + "|" + documentNo;
        return SubmissionResult.pending(
                invoice.id(),
                authorityReference,
                "AGT acknowledged submission; poll obterEstado for validation result");
    }

    // -------------------------------------------------------------- registerSeries

    @Override
    public Series registerSeries(Series series) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("schemaVersion", SCHEMA_VERSION);
        request.put("submissionUUID", java.util.UUID.randomUUID().toString());
        request.put("taxRegistrationNumber", emitterCredential.nif());
        request.put("submissionTimeStamp", Instant.now().toString());
        request.put("softwareInfo", buildSoftwareInfo());
        request.put("jwsSignature", signRequest(emitterCredential.nif(), ""));
        request.put("seriesYear", String.valueOf(series.seriesYear()));
        request.put("documentType", series.documentType().name());
        request.put("establishmentNumber", "1");
        request.put("seriesContingencyIndicator", series.contingencyIndicator() != null ? series.contingencyIndicator() : "N");

        Map<String, Object> resp = agt.solicitarSerie(request);
        String resultCode = String.valueOf(resp.get("resultCode"));
        if (!"0".equals(resultCode)) {
            throw new ComplianceException(
                    "AGT_SERIES_REJECTED",
                    "AGT rejected series request with resultCode " + resultCode + ": " + errorList(resp));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> seriesResult = (Map<String, Object>) resp.get("seriesFEResult");
        return new Series(
                String.valueOf(seriesResult.get("seriesCode")),
                series.documentType(),
                series.seriesYear(),
                series.validFrom(),
                null,                                   // authorityValidationCode (PT-only concept)
                Integer.parseInt(String.valueOf(seriesResult.get("authorizedQuantity"))),
                String.valueOf(seriesResult.get("firstDocumentNo")),
                String.valueOf(seriesResult.get("lastDocumentNo")),
                series.contingencyIndicator()
        );
    }

    // ------------------------------------------------------------ queryStatus

    @Override
    public SubmissionResult queryStatus(String authorityReference) {
        String[] parts = authorityReference.split("\\|", 2);
        String requestId = parts[0];
        String documentNo = parts.length > 1 ? parts[1] : null;

        Map<String, Object> obterEstadoRequest = new LinkedHashMap<>();
        obterEstadoRequest.put("schemaVersion", SCHEMA_VERSION);
        obterEstadoRequest.put("submissionUUID", java.util.UUID.randomUUID().toString());
        obterEstadoRequest.put("taxRegistrationNumber", emitterCredential.nif());
        obterEstadoRequest.put("submissionTimeStamp", Instant.now().toString());
        obterEstadoRequest.put("softwareInfo", buildSoftwareInfo());
        obterEstadoRequest.put("jwsSignature", signRequest(emitterCredential.nif(), requestId));
        obterEstadoRequest.put("requestID", requestId);

        Map<String, Object> estado = agt.obterEstado(obterEstadoRequest);
        String resultCode = String.valueOf(estado.get("resultCode"));

        return switch (resultCode) {
            case "0" -> SubmissionResult.accepted(null, authorityReference, fetchReportUrl(documentNo));
            case "1" -> {
                // Partial: some valid, some invalid. For a single invoice we look up its documentStatus.
                SubmissionResult.Status perDoc = perDocumentStatus(estado, documentNo);
                yield perDoc == SubmissionResult.Status.ACCEPTED
                        ? SubmissionResult.accepted(null, authorityReference, fetchReportUrl(documentNo))
                        : SubmissionResult.rejected(null, "AGT marked document " + documentNo + " as invalid: " + perDocErrors(estado, documentNo));
            }
            case "2" -> SubmissionResult.rejected(null, "AGT resultCode 2: all invoices invalid");
            case "7" -> SubmissionResult.pending(null, authorityReference, "AGT resultCode 7: premature/repetitive, retry later");
            case "8" -> SubmissionResult.pending(null, authorityReference, "AGT resultCode 8: processing still in progress");
            case "9" -> SubmissionResult.rejected(null, "AGT resultCode 9: processing cancelled");
            default -> throw new ComplianceException(
                    "AGT_UNKNOWN_RESULT_CODE",
                    "AGT obterEstado returned unknown resultCode " + resultCode + " for request " + requestId);
        };
    }

    // ------------------------------------------------------ fetchQrCode (legacy)

    /**
     * AGT does not return a base64 QR PNG; the printed QR is generated
     * client-side from the validated document data. This method therefore
     * returns the reportUrl under the QR key as a convenience for callers
     * using the older single-QR-field contract, but new code should use
     * {@link SubmissionResult#artefacts()} directly.
     */
    @Override
    public Optional<String> fetchQrCode(String authorityReference) {
        String[] parts = authorityReference.split("\\|", 2);
        if (parts.length < 2) return Optional.empty();
        return Optional.ofNullable(fetchReportUrl(parts[1]).get("reportUrl"));
    }

    // ============================================================ private

    private Map<String, Object> buildRegistarFacturaRequest(List<Invoice> invoices) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("schemaVersion", SCHEMA_VERSION);
        request.put("submissionUUID", invoices.get(0).id().toString()); // UUID v4, unique per emitter
        request.put("taxRegistrationNumber", emitterCredential.nif());
        request.put("submissionTimeStamp", Instant.now().toString());
        request.put("softwareInfo", buildSoftwareInfo());
        request.put("numberOfEntries", String.valueOf(invoices.size()));
        request.put("documents", invoices.stream().map(this::toAgtDocument).toList());
        return request;
    }

    private Map<String, Object> buildSoftwareInfo() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("productId", softwareCredential.productId());
        detail.put("productVersion", softwareCredential.productVersion());
        detail.put("softwareValidationNumber", softwareCredential.softwareValidationNumber());

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("softwareInfoDetail", detail);
        info.put("jwsSoftwareSignature", signSoftwareInfo(detail));
        return info;
    }

    private Map<String, Object> toAgtDocument(Invoice invoice) {
        Map<String, Object> doc = new LinkedHashMap<>();
        String documentNo = deriveDocumentNo(invoice);
        doc.put("documentNo", documentNo);
        doc.put("documentStatus", "N"); // N = Normal
        doc.put("jwsDocumentSignature", signDocument(invoice, documentNo));
        doc.put("documentDate", invoice.issueDate().toString());
        doc.put("documentType", invoice.documentType().name());
        doc.put("systemEntryDate", Instant.now().toString());
        doc.put("customerTaxID", invoice.recipientTaxId() != null ? invoice.recipientTaxId() : "999999999");
        doc.put("customerCountry", invoice.recipientCountry() != null ? invoice.recipientCountry() : "AO");
        doc.put("companyName", invoice.issuerName());
        doc.put("lines", invoice.lines().stream().map(this::toAgtLine).toList());
        doc.put("documentTotals", buildDocumentTotals(invoice));
        return doc;
    }

    private Map<String, Object> toAgtLine(Invoice.Line l) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lineNumber", "1");
        line.put("productCode", "SVC");
        line.put("productDescription", l.description());
        line.put("quantity", l.quantity().toPlainString());
        line.put("unitOfMeasure", "UN");
        line.put("unitPrice", l.unitPrice().toPlainString());
        line.put("unitPriceBase", l.unitPrice().toPlainString());
        line.put("debitAmount", l.lineNet().toPlainString());
        line.put("creditAmount", "0");
        Map<String, Object> tax = new LinkedHashMap<>();
        tax.put("taxType", l.taxType().name());
        tax.put("taxCountryRegion", "AO");
        tax.put("taxCode", l.taxCode() != null ? l.taxCode().name() : "NOR");
        tax.put("taxPercentage", l.taxPercentage() != null ? l.taxPercentage().toPlainString() : "0");
        tax.put("taxContribution", l.lineTax().toPlainString());
        if (l.exemption() != null) tax.put("taxExemptionCode", l.exemption());
        line.put("taxes", List.of(tax));
        line.put("settlementAmount", "0");
        return line;
    }

    private Map<String, Object> buildDocumentTotals(Invoice invoice) {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("taxPayable", invoice.taxTotal().toPlainString());
        totals.put("netTotal", invoice.netTotal().toPlainString());
        totals.put("grossTotal", invoice.grossTotal().toPlainString());
        // currency object is omitted when AOA (per §4.1.6); only set for foreign currency.
        if (!"AOA".equals(invoice.currency())) {
            Map<String, Object> currency = new LinkedHashMap<>();
            currency.put("currencyCode", invoice.currency());
            currency.put("currencyAmount", invoice.grossTotal().toPlainString());
            currency.put("exchangeRate", "1");
            totals.put("currency", currency);
        }
        return totals;
    }

    private String deriveDocumentNo(Invoice invoice) {
        // In production: derived from the AGT-issued series range (firstDocumentNo + sequence).
        // Skeleton uses the invoice id for stability.
        return "FT " + invoice.issueDate().getYear() + "/A/" + invoice.id().toString().substring(0, 8);
    }

    private Map<String, String> fetchReportUrl(String documentNo) {
        if (documentNo == null) return Map.of();
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("schemaVersion", SCHEMA_VERSION);
            req.put("submissionUUID", java.util.UUID.randomUUID().toString());
            req.put("taxRegistrationNumber", emitterCredential.nif());
            req.put("submissionTimeStamp", Instant.now().toString());
            req.put("softwareInfo", buildSoftwareInfo());
            req.put("jwsSignature", signRequest(emitterCredential.nif(), documentNo));
            req.put("invoiceNo", documentNo);
            Map<String, Object> resp = agt.consultarFactura(req);
            @SuppressWarnings("unchecked")
            Map<String, Object> document = (Map<String, Object>) resp.get("document");
            if (document == null) return Map.of();
            String reportUrl = String.valueOf(document.get("reportUrl"));
            return reportUrl != null && !"null".equals(reportUrl)
                    ? Map.of("reportUrl", reportUrl)
                    : Map.of();
        } catch (Exception e) {
            // Not yet validated / not yet available — caller will retry.
            return Map.of();
        }
    }

    private SubmissionResult.Status perDocumentStatus(Map<String, Object> estado, String documentNo) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) estado.getOrDefault("documentStatusList", List.of());
        return list.stream()
                .filter(d -> documentNo.equals(String.valueOf(d.get("documentNo"))))
                .findFirst()
                .map(d -> "V".equals(String.valueOf(d.get("documentStatus")))
                        ? SubmissionResult.Status.ACCEPTED
                        : SubmissionResult.Status.REJECTED)
                .orElse(SubmissionResult.Status.PENDING);
    }

    private String perDocErrors(Map<String, Object> estado, String documentNo) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) estado.getOrDefault("documentStatusList", List.of());
        return list.stream()
                .filter(d -> documentNo.equals(String.valueOf(d.get("documentNo"))))
                .findFirst()
                .map(d -> String.valueOf(d.getOrDefault("errorList", "[]")))
                .orElse("no error detail");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> errorList(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.getOrDefault("errorList", List.of());
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
                    "Invoice " + invoice.id() + " totals do not reconcile (AGT would reject with E22/E23/E24)");
        }
    }

    // ---- JWS signing stubs --------------------------------------------------
    // In production these use nimbus-jose-jwt with the real RSA private keys
    // from SoftwareCredentialProvider / EmitterCredentialProvider. The skeleton
    // returns placeholder strings so the project compiles without the crypto
    // dependency.

    private String signSoftwareInfo(Map<String, Object> softwareInfoDetail) {
        return "jws-software-stub:" + softwareInfoDetail.hashCode();
    }

    private String signDocument(Invoice invoice, String documentNo) {
        // Signs over: documentNo, taxRegistrationNumber, documentType, documentDate,
        // customerTaxID, customerCountry, companyName, documentTotals.
        return "jws-document-stub:" + documentNo;
    }

    private String signRequest(String nif, String requestId) {
        // Signs over: taxRegistrationNumber + requestID (or relevant fields per service).
        return "jws-request-stub:" + nif + ":" + requestId;
    }
}
