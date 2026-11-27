package com.tms.compliance.adapter.angola.client;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fake of AGT for local development and tests.
 *
 * <p>Simulates AGT's <b>asynchronous</b> behavior: every invoice starts in
 * PENDENTE, transitions to PROCESSADO after the second poll, and only then
 * exposes a QR code via consultarFactura. This forces the adapter to handle the
 * full async lifecycle correctly even in tests.</p>
 */
@ApplicationScoped
public class InMemoryAgtClient implements AgtClient {

    private record AgtState(int pollsSoFar, String facturaId, String status, String qr) {}

    private final Map<String, AgtState> store = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> criarSerie(String nif, String seriesCode, String documentType) {
        return Map.of(
                "nif", nif,
                "series_code", seriesCode,
                "document_type", documentType,
                "agt_reference", "AGT-SER-" + seriesCode.replace(" ", "-")
        );
    }

    @Override
    public Map<String, Object> registarFactura(String nif, String signedInvoiceJson, String jwsHeader) {
        String requestId = "req-" + UUID.randomUUID();
        String facturaId = "fct-" + UUID.randomUUID();
        // Initial state: PENDENTE — AGT never accepts synchronously.
        store.put(requestId, new AgtState(0, facturaId, "PENDENTE", null));
        return Map.of(
                "request_id", requestId,
                "factura_id", facturaId,
                "status", "PENDENTE"
        );
    }

    @Override
    public Map<String, Object> consultarEstado(String requestId) {
        AgtState current = store.get(requestId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown AGT request id: " + requestId);
        }
        // Advance the simulated state machine: PENDENTE (poll 1) -> PROCESSADO (poll 2+)
        AgtState next = (current.pollsSoFar() == 0)
                ? new AgtState(1, current.facturaId(), "PROCESSADO", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")
                : current;
        store.put(requestId, next);
        return Map.of(
                "request_id", requestId,
                "factura_id", next.facturaId(),
                "status", next.status()
        );
    }

    @Override
    public Map<String, Object> consultarFactura(String facturaId) {
        // Find by facturaId — only return QR if state is PROCESSADO.
        AgtState found = store.values().stream()
                .filter(s -> s.facturaId().equals(facturaId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown AGT factura id: " + facturaId));
        if (!"PROCESSADO".equals(found.status()) || found.qr() == null) {
            throw new IllegalStateException("Factura " + facturaId + " not yet processed");
        }
        return Map.of(
                "factura_id", facturaId,
                "status", found.status(),
                "qr_code_base64", found.qr(),
                "agt_reference", "AGT-" + facturaId
        );
    }
}
