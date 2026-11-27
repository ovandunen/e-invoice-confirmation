package com.tms.compliance.adapter.portugal;

import com.tms.compliance.adapter.portugal.client.InMemoryFiskalyClient;
import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.domain.DocumentType;
import com.tms.compliance.domain.Invoice;
import com.tms.compliance.domain.Series;
import com.tms.compliance.domain.SubmissionResult;
import com.tms.compliance.domain.TaxCode;
import com.tms.compliance.domain.TaxType;
import com.tms.compliance.spi.ComplianceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves the Portugal/Fiskaly synchronous path against
 * {@link InMemoryFiskalyClient} without a CDI container.
 *
 * <p>Unlike AGT, Fiskaly returns ACCEPTED immediately with an inline QR — no
 * polling. The {@code InMemoryFiskalyClient} has {@code @ConfigProperty}
 * fields, so we set them reflectively (they default anyway). The point of this
 * test is the adapter's behaviour, not the client's wiring.</p>
 */
class PortugalComplianceAdapterTest {

    private PortugalComplianceAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryFiskalyClient fiskaly = new InMemoryFiskalyClient();
        adapter = new PortugalComplianceAdapter(fiskaly);
    }

    @Test
    void submit_returnsAcceptedImmediatelyWithQr() {
        Invoice invoice = sampleInvoice();
        SubmissionResult result = adapter.submit(invoice);

        assertThat(result.status()).isEqualTo(SubmissionResult.Status.ACCEPTED);
        assertThat(result.authorityReference()).contains("ATCUD:");
        // PT genuinely returns an inline base64 QR PNG (AO returns a reportUrl instead).
        assertThat(result.artefacts()).containsKey("qrCodeBase64Png");
        assertThat(result.artefacts().get("qrCodeBase64Png")).startsWith("iVBOR");
    }

    @Test
    void queryStatus_returnsCachedFinalState() {
        SubmissionResult submitted = adapter.submit(sampleInvoice());
        SubmissionResult status = adapter.queryStatus(submitted.authorityReference());

        // PT is synchronous — queryStatus just re-reads the cached ACCEPTED.
        assertThat(status.status()).isEqualTo(SubmissionResult.Status.ACCEPTED);
        assertThat(status.artefacts()).containsKey("qrCodeBase64Png");
    }

    @Test
    void fetchQrCode_returnsTheBase64Png() {
        SubmissionResult submitted = adapter.submit(sampleInvoice());
        String qr = adapter.fetchQrCode(submitted.authorityReference()).orElseThrow();
        assertThat(qr).startsWith("iVBOR");
    }

    @Test
    void registerSeries_returnsAtcudValidationCode() {
        Series request = Series.forPortugal("FT 2026/A", DocumentType.FT, 2026, LocalDate.now(), null);
        Series registered = adapter.registerSeries(request);

        assertThat(registered.authorityValidationCode()).startsWith("AT-VALID-");
        assertThat(registered.firstDocumentNo()).isNull(); // AGT-only field
        assertThat(registered.authorizedQuantity()).isZero();
    }

    @Test
    void submit_rejectsInvoicesThatDontReconcile() {
        Invoice bad = new Invoice(
                UUID.randomUUID(), "PT500100200", "Bad Lda.", null, "PT",
                DocumentType.FT, "EUR", LocalDate.now(),
                List.of(new Invoice.Line("x", new BigDecimal("1"), new BigDecimal("10"),
                        TaxType.IVA, TaxCode.NOR, null, new BigDecimal("23"),
                        new BigDecimal("10"), new BigDecimal("2.30"))),
                new BigDecimal("10"), new BigDecimal("2.30"),
                new BigDecimal("99.99"));
        Throwable thrown = catchThrowable(() -> adapter.submit(bad));
        assertThat(thrown).isInstanceOf(ComplianceException.class);
        assertThat(((ComplianceException) thrown).code()).isEqualTo("TOTALS_MISMATCH");
    }

    @Test
    void submitBatch_loopsPerInvoiceSinceFiskalyDoesNotBatch() {
        List<Invoice> batch = List.of(sampleInvoice(), sampleInvoice());
        List<SubmissionResult> results = adapter.submitBatch(batch);

        assertThat(results).hasSize(3 - 1); // 2
        assertThat(results).allSatisfy(r ->
                assertThat(r.status()).isEqualTo(SubmissionResult.Status.ACCEPTED));
        // Each gets its own ATCUD (no shared requestID for PT).
        assertThat(results.get(0).authorityReference())
                .isNotEqualTo(results.get(1).authorityReference());
    }

    @Test
    void country_isPortugal() {
        assertThat(adapter.country()).isEqualTo(CountryCode.PT);
    }

    private Invoice sampleInvoice() {
        Invoice.Line line = new Invoice.Line(
                "Courier delivery Lisboa → Porto",
                new BigDecimal("1"), new BigDecimal("50.00"),
                TaxType.IVA, TaxCode.NOR, null,
                new BigDecimal("23"),
                new BigDecimal("50.00"), new BigDecimal("11.50"));
        return new Invoice(
                UUID.randomUUID(),
                "PT500100200",
                "Demo Courier Lda.",
                null,
                "PT",
                DocumentType.FT,
                "EUR",
                LocalDate.now(),
                List.of(line),
                new BigDecimal("50.00"),
                new BigDecimal("11.50"),
                new BigDecimal("61.50"));
    }
}
