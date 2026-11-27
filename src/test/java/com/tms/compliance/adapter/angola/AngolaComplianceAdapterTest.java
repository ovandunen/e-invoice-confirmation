package com.tms.compliance.adapter.angola;

import com.tms.compliance.adapter.angola.client.InMemoryAgtClient;
import com.tms.compliance.adapter.angola.crypto.JwsSigner;
import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.domain.DocumentType;
import com.tms.compliance.domain.Invoice;
import com.tms.compliance.domain.SubmissionResult;
import com.tms.compliance.domain.TaxCode;
import com.tms.compliance.domain.TaxType;
import com.tms.compliance.spi.ComplianceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves the AGT lifecycle end-to-end against {@link InMemoryAgtClient},
 * without spinning up a CDI container. The adapter is constructed by hand with
 * in-memory credentials + a real (generated) RSA key pair, so the JWS signing
 * path is exercised with nimbus-jose-jwt, not just the stub fallback.
 */
class AngolaComplianceAdapterTest {

    private InMemoryAgtClient agt;
    private AngolaComplianceAdapter adapter;
    private PrivateKey emitterKey;

    @BeforeEach
    void setUp() throws Exception {
        agt = new InMemoryAgtClient();
        // Real RSA key so JwsSigner exercises nimbus-jose-jwt, not the stub.
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        emitterKey = pair.getPrivate();

        SoftwareCredentialProvider sw = new SoftwareCredentialProvider(
                "TMS-Compliance", "0.1.0", "AGT-VALID-TEST");
        EmitterCredentialProvider em = new EmitterCredentialProvider(
                "AO541700100", "user", "pass", emitterKey);
        adapter = new AngolaComplianceAdapter(agt, sw, em);
    }

    @Test
    void submit_returnsPendingWithRequestId_thenPollingTransitionsToAccepted() {
        Invoice invoice = sampleInvoice();
        SubmissionResult submitted = adapter.submit(invoice);

        // AGT returns a synchronous receipt → PENDING, never ACCEPTED directly.
        assertThat(submitted.status()).isEqualTo(SubmissionResult.Status.PENDING);
        assertThat(submitted.authorityReference()).contains("|");
        String ref = submitted.authorityReference();

        // First poll → resultCode 8 (still in progress) → PENDING.
        SubmissionResult firstPoll = adapter.queryStatus(ref);
        assertThat(firstPoll.status()).isEqualTo(SubmissionResult.Status.PENDING);

        // Second poll → resultCode 0 → ACCEPTED, with reportUrl artefact.
        SubmissionResult secondPoll = adapter.queryStatus(ref);
        assertThat(secondPoll.status()).isEqualTo(SubmissionResult.Status.ACCEPTED);
        assertThat(secondPoll.artefacts()).containsKey("reportUrl");
        assertThat(secondPoll.artefacts().get("reportUrl")).startsWith("https://sifp.minfin.gov.ao/report/");
    }

    @Test
    void submitBatch_sharesOneRequestIdAcrossAllDocuments() {
        List<Invoice> batch = List.of(sampleInvoice(), sampleInvoice(), sampleInvoice());
        List<SubmissionResult> results = adapter.submitBatch(batch);

        assertThat(results).hasSize(3);
        // All share the same requestID (the part before '|').
        String firstReq = results.get(0).authorityReference().split("\\|", 2)[0];
        assertThat(results).allSatisfy(r ->
                assertThat(r.authorityReference()).startsWith(firstReq + "|"));
        assertThat(results).allSatisfy(r ->
                assertThat(r.status()).isEqualTo(SubmissionResult.Status.PENDING));
    }

    @Test
    void submitBatch_rejectsMoreThan30Invoices() {
        List<Invoice> tooMany = new ArrayList<>();
        for (int i = 0; i < 31; i++) tooMany.add(sampleInvoice());
        assertThatThrownBy(() -> adapter.submitBatch(tooMany))
                .isInstanceOf(ComplianceException.class)
                .hasMessageContaining("30");
    }

    @Test
    void submitBatch_rejectsEmptyBatch() {
        assertThatThrownBy(() -> adapter.submitBatch(List.of()))
                .isInstanceOf(ComplianceException.class);
    }

    @Test
    void submit_rejectsInvoicesThatDontReconcile() {
        Invoice bad = new Invoice(
                UUID.randomUUID(), "AO541700100", "Bad Co.", null, "AO",
                DocumentType.FT, "AOA", LocalDate.now(),
                List.of(new Invoice.Line("x", new BigDecimal("1"), new BigDecimal("10"),
                        TaxType.IVA, TaxCode.NOR, null, new BigDecimal("14"),
                        new BigDecimal("10"), new BigDecimal("1.40"))),
                new BigDecimal("10"), new BigDecimal("1.40"),
                new BigDecimal("99.99")); // gross is wrong
        Throwable thrown = catchThrowable(() -> adapter.submit(bad));
        assertThat(thrown).isInstanceOf(ComplianceException.class);
        assertThat(((ComplianceException) thrown).code()).isEqualTo("TOTALS_MISMATCH");
    }

    @Test
    void registerSeries_returnsAgtAuthorizedRange() {
        var request = com.tms.compliance.domain.Series.forAngolaRequest(DocumentType.FT, 2026, "N");
        var registered = adapter.registerSeries(request);

        assertThat(registered.code()).contains("FT 2026/");
        assertThat(registered.authorizedQuantity()).isEqualTo(1000);
        assertThat(registered.firstDocumentNo()).startsWith("FT/2026/");
        assertThat(registered.lastDocumentNo()).contains("1000");
    }

    @Test
    void country_isAngola() {
        assertThat(adapter.country()).isEqualTo(CountryCode.AO);
    }

    @Test
    void jwsSigner_isDeterministicForSameClaims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("a", "1");
        claims.put("b", "2");
        String s1 = JwsSigner.sign(claims, emitterKey);
        String s2 = JwsSigner.sign(new LinkedHashMap<>(claims), emitterKey);
        // Same key + same canonical claims → identical compact JWS.
        assertThat(s1).isEqualTo(s2);
        // Real JWS (not the stub) — stub starts with "jws-stub:".
        assertThat(s1).startsWith("ey");
    }

    @Test
    void jwsSigner_returnsStubWhenNoKey() {
        Map<String, Object> claims = Map.of("a", "1");
        String s = JwsSigner.sign(claims, null);
        assertThat(s).startsWith("jws-stub:");
    }

    @Test
    void jwsSigner_canonicalJsonIgnoresInsertionOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", "1"); a.put("a", "2");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", "2"); b.put("z", "1");
        // Both produce the same signature with the same key.
        assertThat(JwsSigner.sign(a, emitterKey)).isEqualTo(JwsSigner.sign(b, emitterKey));
    }

    // --- helpers ------------------------------------------------------------

    private Invoice sampleInvoice() {
        Invoice.Line line = new Invoice.Line(
                "Taxi ride Luanda → Talatona",
                new BigDecimal("1"), new BigDecimal("5000.00"),
                TaxType.IVA, TaxCode.NOR, null,
                new BigDecimal("14"),
                new BigDecimal("5000.00"), new BigDecimal("700.00"));
        return new Invoice(
                UUID.randomUUID(),
                "AO541700100",
                "Demo Courier Angola Lda.",
                null,
                "AO",
                DocumentType.FT,
                "AOA",
                LocalDate.now(),
                List.of(line),
                new BigDecimal("5000.00"),
                new BigDecimal("700.00"),
                new BigDecimal("5700.00"));
    }
}
