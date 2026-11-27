package com.tms.core.payment;

import com.tms.compliance.domain.CountryCode;
import com.tms.core.order.Order;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the AO Multicaixa payment flows against the in-memory fake:
 * card-present approves immediately; Express is PENDING then APPROVED on the
 * second poll (mimicking the customer approving in the app).
 */
class MulticaixaClientTest {

    private final InMemoryMulticaixaClient mcx = new InMemoryMulticaixaClient();
    private final PaymentService payments = new PaymentService(mcx);

    @Test
    void cardPresent_approvesImmediately() {
        Order order = Order.newOrder(CountryCode.AO, "AO541700100", "Luanda", "Talatona",
                new BigDecimal("5000"), "AOA");
        Map<String, Object> result = payments.collect(order, "CARD_PRESENT");

        assertThat(result.get("status")).isEqualTo("APPROVED");
        assertThat(result.get("transactionId")).asString().startsWith("mcx-pos-");
        assertThat(result.get("authorizationCode")).asString().startsWith("AUTH-");
    }

    @Test
    void express_isPendingThenApprovedOnSecondPoll() {
        Order order = Order.newOrder(CountryCode.AO, "AO541700100", "Luanda", "Talatona",
                new BigDecimal("5000"), "AOA");
        Map<String, Object> init = payments.collect(order, "EXPRESS");

        assertThat(init.get("status")).isEqualTo("PENDING");
        assertThat(init.get("paymentUrl")).asString().startsWith("https://express.multicaixa.express/pay/");

        String txnId = (String) init.get("transactionId");
        // First poll — still pending (customer hasn't approved yet).
        assertThat(payments.status(CountryCode.AO, txnId).get("status")).isEqualTo("PENDING");
        // Second poll — approved.
        assertThat(payments.status(CountryCode.AO, txnId).get("status")).isEqualTo("APPROVED");
    }

    @Test
    void refund_flipsTransactionToRefunded() {
        Order order = Order.newOrder(CountryCode.AO, "AO541700100", "Luanda", "Talatona",
                new BigDecimal("5000"), "AOA");
        Map<String, Object> paid = payments.collect(order, "CARD_PRESENT");
        String txnId = (String) paid.get("transactionId");

        Map<String, Object> refund = mcx.refund(txnId, new BigDecimal("5000"));
        assertThat(refund.get("status")).isEqualTo("REFUNDED");
        assertThat(refund.get("refundId")).asString().startsWith("rfnd-");
        // Subsequent status confirms refunded.
        assertThat(mcx.status(txnId).get("status")).isEqualTo("REFUNDED");
    }

    @Test
    void pt_paymentRail_notYetImplemented() {
        Order order = Order.newOrder(CountryCode.PT, "PT500100200", "Lisboa", "Porto",
                new BigDecimal("50"), "EUR");
        try {
            payments.collect(order, "EXPRESS");
        } catch (UnsupportedOperationException expected) {
            assertThat(expected).hasMessageContaining("PT payment rail not yet implemented");
        }
    }
}
