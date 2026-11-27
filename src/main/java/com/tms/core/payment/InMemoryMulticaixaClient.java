package com.tms.core.payment;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fake of EMIS Multicaixa for local development and tests.
 *
 * <p>Simulates the two AO payment flows:</p>
 * <ul>
 *   <li><b>Express</b> — {@code initiateExpress} returns PENDING with a fake
 *       redirect URL; the first {@code status} poll stays PENDING, the second
 *       returns APPROVED (mimicking the customer approving in the app).</li>
 *   <li><b>Card-present</b> — {@code captureCardPresent} returns APPROVED
 *       immediately (the customer is present).</li>
 * </ul>
 *
 * <p>In production replace with a {@code @RegisterRestClient} interface hitting
 * EMIS's Multicaixa Express API ({@code https://api.multicaixa.express/...})
 * with mTLS + HMAC-signed webhooks.</p>
 */
@ApplicationScoped
public class InMemoryMulticaixaClient implements MulticaixaClient {

    private record Txn(int polls, String merchantTxnId, BigDecimal amount, String status) {}

    private final Map<String, Txn> store = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> initiateExpress(String merchantTransactionId, BigDecimal amount, String description) {
        String txnId = "mcx-" + UUID.randomUUID();
        store.put(txnId, new Txn(0, merchantTransactionId, amount, "PENDING"));
        return Map.of(
                "transactionId", txnId,
                "paymentUrl", "https://express.multicaixa.express/pay/" + txnId,
                "status", "PENDING",
                "description", description);
    }

    @Override
    public Map<String, Object> captureCardPresent(String merchantTransactionId, BigDecimal amount, String cardLastFour) {
        String txnId = "mcx-pos-" + UUID.randomUUID();
        // Card-present is synchronous: approve immediately.
        store.put(txnId, new Txn(0, merchantTransactionId, amount, "APPROVED"));
        return Map.of(
                "transactionId", txnId,
                "status", "APPROVED",
                "cardLastFour", cardLastFour,
                "authorizationCode", "AUTH-" + txnId.substring(8));
    }

    @Override
    public Map<String, Object> status(String transactionId) {
        Txn t = store.get(transactionId);
        if (t == null) throw new IllegalArgumentException("Unknown Multicaixa transaction: " + transactionId);
        // First poll stays PENDING, second flips to APPROVED (customer approved in the app).
        if (!"PENDING".equals(t.status())) {
            return Map.of("transactionId", transactionId, "status", t.status());
        }
        Txn next = (t.polls() == 0)
                ? new Txn(1, t.merchantTxnId(), t.amount(), "PENDING")
                : new Txn(2, t.merchantTxnId(), t.amount(), "APPROVED");
        store.put(transactionId, next);
        return Map.of("transactionId", transactionId, "status", next.status());
    }

    @Override
    public Map<String, Object> refund(String transactionId, BigDecimal amount) {
        Txn t = store.get(transactionId);
        if (t == null) throw new IllegalArgumentException("Unknown Multicaixa transaction: " + transactionId);
        store.put(transactionId, new Txn(t.polls(), t.merchantTxnId(), t.amount(), "REFUNDED"));
        return Map.of("refundId", "rfnd-" + UUID.randomUUID(), "status", "REFUNDED", "amount", amount);
    }
}
