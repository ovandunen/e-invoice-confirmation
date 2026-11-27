package com.tms.compliance.adapter.angola.client;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory fake of AGT for local development and tests.
 *
 * <p>Simulates the real DS.120 lifecycle:</p>
 * <ol>
 *   <li>{@code registarFactura} returns a {@code requestID} synchronously
 *       (validation deferred).</li>
 *   <li>First {@code obterEstado} call returns resultCode 8 (still in progress).</li>
 *   <li>Second {@code obterEstado} call returns resultCode 0 with documentStatus V
 *       for each document (validated successfully).</li>
 *   <li>{@code consultarFactura} returns the full document with a
 *       {@code reportUrl}.</li>
 * </ol>
 *
 * <p>This is the real shape of the AGT protocol per DS.120 — no PENDENTE returned
 * by registarFactura, no base64 QR anywhere; the artefact is a reportUrl.</p>
 */
@ApplicationScoped
public class InMemoryAgtClient implements AgtClient {

    /** Per-requestID state: how many obterEstado polls so far + the documents + their final status. */
    private record AgtRequest(int pollsSoFar, List<String> documentNos, boolean validated) {}

    private final Map<String, AgtRequest> requests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> documentsByNo = new ConcurrentHashMap<>();
    private final AtomicInteger seriesCounter = new AtomicInteger(0);

    @Override
    public Map<String, Object> registarFactura(Map<String, Object> fullRequest) {
        String requestId = "req-" + UUID.randomUUID();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docs = (List<Map<String, Object>>) fullRequest.getOrDefault("documents", List.of());
        List<String> documentNos = docs.stream()
                .map(d -> String.valueOf(d.get("documentNo")))
                .toList();
        // Store each document so consultarFactura can return it later.
        for (Map<String, Object> d : docs) {
            documentsByNo.put(String.valueOf(d.get("documentNo")), d);
        }
        requests.put(requestId, new AgtRequest(0, documentNos, false));
        return Map.of("requestID", requestId);
    }

    @Override
    public Map<String, Object> obterEstado(Map<String, Object> fullRequest) {
        String requestId = String.valueOf(fullRequest.get("requestID"));
        AgtRequest current = requests.get(requestId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown AGT requestID: " + requestId);
        }
        // First poll → resultCode 8 (in progress). Second poll → resultCode 0 (complete, all valid).
        AgtRequest next = (current.pollsSoFar() == 0)
                ? new AgtRequest(1, current.documentNos(), false)
                : new AgtRequest(current.pollsSoFar() + 1, current.documentNos(), true);
        requests.put(requestId, next);

        int resultCode = next.validated() ? 0 : 8;
        List<Map<String, Object>> statusList = next.documentNos().stream()
                .map(docNo -> Map.<String, Object>of(
                        "documentNo", docNo,
                        "documentStatus", next.validated() ? "V" : "I",
                        "errorList", List.of()))
                .toList();
        return Map.of(
                "requestID", requestId,
                "resultCode", String.valueOf(resultCode),
                "documentStatusList", statusList,
                "requestErrorList", List.of()
        );
    }

    @Override
    public Map<String, Object> listarFacturas(Map<String, Object> fullRequest) {
        return Map.of("invoices", List.copyOf(documentsByNo.values()));
    }

    @Override
    public Map<String, Object> consultarFactura(Map<String, Object> fullRequest) {
        String invoiceNo = String.valueOf(fullRequest.get("invoiceNo"));
        Map<String, Object> doc = documentsByNo.get(invoiceNo);
        if (doc == null) {
            throw new IllegalArgumentException("Unknown AGT invoiceNo: " + invoiceNo);
        }
        // The real spec returns the full document + reportUrl (NOT a base64 QR).
        Map<String, Object> document = new java.util.LinkedHashMap<>(doc);
        document.put("reportUrl", "https://sifp.minfin.gov.ao/report/" + invoiceNo);
        document.put("jwsSignature", "fake-jws-" + UUID.randomUUID());
        return Map.of(
                "documentNo", invoiceNo,
                "documentStatus", "V",
                "document", document
        );
    }

    @Override
    public Map<String, Object> solicitarSerie(Map<String, Object> fullRequest) {
        int n = seriesCounter.incrementAndGet();
        return Map.of(
                "resultCode", "0",
                "errorList", List.of(),
                "seriesFEResult", Map.of(
                        "seriesCode", String.valueOf(fullRequest.get("documentType")) + " " + fullRequest.get("seriesYear") + "/" + (char) ('A' + n - 1),
                        "authorizedQuantity", "1000",
                        "firstDocumentNo", String.format("%s/%s/1", fullRequest.get("documentType"), fullRequest.get("seriesYear")),
                        "lastDocumentNo", String.format("%s/%s/1000", fullRequest.get("documentType"), fullRequest.get("seriesYear"))
                )
        );
    }

    @Override
    public Map<String, Object> listarSeries(Map<String, Object> fullRequest) {
        return Map.of("series", List.of());
    }

    @Override
    public Map<String, Object> validarDocumento(Map<String, Object> fullRequest) {
        return Map.of(
                "actionResultCode", "C_OK",
                "documentStatusCode", "S_C",
                "errorList", List.of()
        );
    }
}
