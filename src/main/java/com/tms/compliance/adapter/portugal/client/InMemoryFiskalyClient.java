package com.tms.compliance.adapter.portugal.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fake of Fiskaly for local development and tests.
 *
 * <p>Quarkus picks this up automatically as a CDI bean implementing
 * {@link FiskalyClient}; in production you'd replace it with a
 * {@code @RegisterRestClient}-annotated interface and exclude this class via a
 * profile (e.g. put it under {@code src/test/java} or guard with
 * {@code @IfBuildProfile("dev")}).</p>
 *
 * <p>It simulates Fiskaly's synchronous behavior: invoice submission returns
 * ACCEPTED immediately with a generated ATCUD and a fake QR. No polling needed.</p>
 */
@ApplicationScoped
public class InMemoryFiskalyClient implements FiskalyClient {

    @ConfigProperty(name = "tms.compliance.pt.fiskaly.api-key", defaultValue = "test-key")
    String apiKey;

    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    @Override
    public String authenticate() {
        return "fake-bearer-" + UUID.randomUUID();
    }

    @Override
    public Map<String, Object> registerSeries(String nif, String seriesCode, String documentType) {
        return Map.of(
                "nif", nif,
                "series_code", seriesCode,
                "document_type", documentType,
                "atcud_validation", "AT-VALID-" + seriesCode.replace(" ", "-")
        );
    }

    @Override
    public Map<String, Object> submitSignedInvoice(String nif, Map<String, Object> request) {
        String fiskalyId = "fsk-" + UUID.randomUUID();
        String atcud = "ATCUD:" + request.getOrDefault("series_code", "X") + ":" + UUID.randomUUID();
        Map<String, Object> response = Map.of(
                "fiskaly_id", fiskalyId,
                "atcud", atcud,
                "qr_code_base64", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
                "hash", "sha256:" + UUID.randomUUID(),
                "state", "ACCEPTED"
        );
        store.put(fiskalyId, response);
        return response;
    }

    @Override
    public Map<String, Object> getInvoice(String nif, String fiskalyId) {
        Map<String, Object> found = store.get(fiskalyId);
        if (found == null) {
            throw new IllegalArgumentException("Unknown fiskaly id: " + fiskalyId);
        }
        return found;
    }
}
