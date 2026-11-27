package com.tms.core.billing;

import com.tms.compliance.adapter.angola.AngolaComplianceAdapter;
import com.tms.compliance.adapter.angola.EmitterCredentialProvider;
import com.tms.compliance.adapter.angola.SoftwareCredentialProvider;
import com.tms.compliance.adapter.angola.client.InMemoryAgtClient;
import com.tms.compliance.adapter.portugal.PortugalComplianceAdapter;
import com.tms.compliance.adapter.portugal.client.InMemoryFiskalyClient;
import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.domain.SubmissionResult;
import com.tms.compliance.facade.ComplianceFacade;
import com.tms.compliance.selector.ComplianceSelector;
import com.tms.compliance.spi.CountryQualifier;
import com.tms.core.dispatch.DispatchService;
import com.tms.core.dispatch.Trip;
import com.tms.core.fleet.Vehicle;
import com.tms.core.order.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: Order → Dispatch → Trip complete → BillingService.invoices it →
 * ComplianceFacade submits to the right authority → returns ACCEPTED (PT) or
 * PENDING then ACCEPTED (AO).
 *
 * <p>Wires real adapters with in-memory clients and a hand-built selector so
 * no CDI container is needed. This is the test that proves the whole skeleton
 * hangs together across both countries.</p>
 */
class BillingIntegrationTest {

    private DispatchService dispatch;
    private BillingService billing;
    private AngolaComplianceAdapter aoAdapter;
    private PortugalComplianceAdapter ptAdapter;

    @BeforeEach
    void setUp() throws Exception {
        // Real RSA key so AGT JWS signing is exercised (not the stub).
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();

        aoAdapter = new AngolaComplianceAdapter(
                new InMemoryAgtClient(),
                new SoftwareCredentialProvider("TMS-Compliance", "0.1.0", "AGT-VALID-TEST"),
                new EmitterCredentialProvider("AO541700100", "u", "p", pair.getPrivate()));
        ptAdapter = new PortugalComplianceAdapter(new InMemoryFiskalyClient());

        // A hand-built selector standing in for the CDI-built ComplianceSelector.
        // The real one collects adapters via Instance<InvoiceComplianceAdapter>;
        // here we just route by country. Same seam, no container.
        ComplianceSelector selector = new ComplianceSelector(null) {
            @Override
            public com.tms.compliance.spi.InvoiceComplianceAdapter forCountry(CountryCode c) {
                return c == CountryCode.AO ? aoAdapter : ptAdapter;
            }
            @Override
            public boolean supports(CountryCode c) {
                return c == CountryCode.AO || c == CountryCode.PT;
            }
        };
        billing = new BillingService(new ComplianceFacade(selector));
        dispatch = new DispatchService();
    }

    @Test
    void fullFlow_angola_dispatchCompleteInvoiceAndPoll() {
        dispatch.registerVehicle(Vehicle.available(CountryCode.AO, "LD-1234-AB"));

        Order order = Order.newOrder(CountryCode.AO, "AO541700100", "Luanda", "Talatona",
                new BigDecimal("5000"), "AOA");
        Trip trip = dispatch.dispatch(order).orElseThrow();
        Trip completed = dispatch.complete(trip.id());

        // Invoice the trip → AO returns PENDING (deferred validation).
        SubmissionResult submitted = billing.invoiceTrip(completed, order);
        assertThat(submitted.status()).isEqualTo(SubmissionResult.Status.PENDING);
        String ref = submitted.authorityReference();

        // Poll twice: first PENDING (resultCode 8), then ACCEPTED (resultCode 0) + reportUrl.
        assertThat(billing.pollStatus(CountryCode.AO, ref).status())
                .isEqualTo(SubmissionResult.Status.PENDING);
        SubmissionResult final_ = billing.pollStatus(CountryCode.AO, ref);
        assertThat(final_.status()).isEqualTo(SubmissionResult.Status.ACCEPTED);
        assertThat(final_.artefacts()).containsKey("reportUrl");
    }

    @Test
    void fullFlow_portugal_dispatchCompleteInvoiceAcceptedImmediately() {
        dispatch.registerVehicle(Vehicle.available(CountryCode.PT, "AB-12-CD"));

        Order order = Order.newOrder(CountryCode.PT, "PT500100200", "Lisboa", "Porto",
                new BigDecimal("50"), "EUR");
        Trip trip = dispatch.dispatch(order).orElseThrow();
        Trip completed = dispatch.complete(trip.id());

        // Invoice the trip → PT returns ACCEPTED immediately with inline QR.
        SubmissionResult submitted = billing.invoiceTrip(completed, order);
        assertThat(submitted.status()).isEqualTo(SubmissionResult.Status.ACCEPTED);
        assertThat(submitted.artefacts()).containsKey("qrCodeBase64Png");
        assertThat(submitted.authorityReference()).contains("ATCUD:");
    }

    @Test
    void dispatch_failsWhenNoVehicleAvailable() {
        Order order = Order.newOrder(CountryCode.AO, "AO541700100", "Luanda", "Talatona",
                new BigDecimal("5000"), "AOA");
        // No vehicle registered → dispatch returns empty.
        assertThat(dispatch.dispatch(order)).isEmpty();
    }

    @Test
    void cannotInvoiceATripThatIsNotCompleted() {
        dispatch.registerVehicle(Vehicle.available(CountryCode.AO, "LD-1234-AB"));
        Order order = Order.newOrder(CountryCode.AO, "AO541700100", "Luanda", "Talatona",
                new BigDecimal("5000"), "AOA");
        Trip assigned = dispatch.dispatch(order).orElseThrow();
        // Not completed → billing must refuse.
        try {
            billing.invoiceTrip(assigned, order);
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("not COMPLETED");
        }
    }
}
