package com.tms.core.payment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Angola's Multicaixa payment rail (card payments + Multicaixa Express online).
 *
 * <p>Multicaixa is the dominant card scheme in Angola (operated by EMIS). For
 * a courier/taxi TMS it covers two flows:</p>
 * <ul>
 *   <li><b>Card-present</b> — driver taps/inserts the customer's card on a POS
 *       or a mobile card reader at delivery.</li>
 *   <li><b>Multicaixa Express</b> — online payment redirect, the customer
 *       approves in the Multicaixa app; the TMS polls the transaction status.</li>
 * </ul>
 *
 * <p>This interface is the Adaptee for the AO payment side; the
 * {@link PaymentService} adapts it behind a country-agnostic facade the same
 * way {@link com.tms.compliance.spi.InvoiceComplianceAdapter} adapts the
 * fiscal backends. PT would have a sibling {@code SibsClient} (MB WAY,
 * reference-based multibanco) under the same {@link PaymentService} boundary.</p>
 *
 * <p>The skeleton is intentionally minimal — production adds 3DS, webhook
 * signature verification, idempotency on {@code merchantTransactionId},
 * settlement reconciliation, and refunds.</p>
 */
public interface MulticaixaClient {

    /**
     * Initiate a Multicaixa Express online payment (customer pays in the app).
     *
     * @param merchantTransactionId the TMS order id — idempotency key
     * @param amount                 amount in AOA (kwanza)
     * @param description            shown to the customer in the app
     * @return map with {@code transactionId}, {@code paymentUrl} (redirect),
     *         and {@code status} = {@code PENDING}
     */
    Map<String, Object> initiateExpress(String merchantTransactionId, BigDecimal amount, String description);

    /**
     * Initiate a card-present capture (driver's POS/reader). The customer is
     * physically present; the amount is captured immediately on approval.
     *
     * @param merchantTransactionId the TMS order id — idempotency key
     * @param amount                 amount in AOA
     * @param cardLastFour           last 4 digits of the card (for the receipt)
     * @return map with {@code transactionId} and {@code status} =
     *         {@code APPROVED} / {@code DECLINED}
     */
    Map<String, Object> captureCardPresent(String merchantTransactionId, BigDecimal amount, String cardLastFour);

    /**
     * Poll the status of a previously-initiated transaction.
     *
     * @return map with {@code transactionId} and {@code status} ∈
     *         {PENDING, APPROVED, DECLINED, EXPIRED, REFUNDED}
     */
    Map<String, Object> status(String transactionId);

    /**
     * Refund a previously-approved transaction (full or partial).
     *
     * @return map with {@code refundId} and {@code status} = {@code REFUNDED}
     */
    Map<String, Object> refund(String transactionId, BigDecimal amount);
}
