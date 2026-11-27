package com.tms.core.payment;

import com.tms.compliance.domain.CountryCode;
import com.tms.core.order.Order;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Country-agnostic facade over the payment rails, mirroring the shape of
 * {@link com.tms.compliance.facade.ComplianceFacade}.
 *
 * <p>For AO it delegates to {@link MulticaixaClient}; for PT it would delegate
 * to a {@code SibsClient} (MB WAY / multibanco reference). The skeleton ships
 * the AO side only — adding PT is a pure addition (one new client + one branch
 * in {@link #collect(Order)}), exactly like the fiscal adapter pattern.</p>
 *
 * <p>The billing flow is: collect payment → confirm paid → issue fiscal invoice.
 * The two are decoupled so a trip can be invoiced on credit (paid later) or
 * paid-up-front (invoiced after capture).</p>
 */
@ApplicationScoped
public class PaymentService {

    private final MulticaixaClient multicaixa;

    @Inject
    public PaymentService(MulticaixaClient multicaixa) {
        this.multicaixa = multicaixa;
    }

    /**
     * Collect payment for an order via the country-appropriate rail.
     *
     * @param order   the order to charge
     * @param channel "EXPRESS" (online/app) or "CARD_PRESENT" (driver's reader)
     * @return the raw rail response (transactionId + status + ...)
     */
    public Map<String, Object> collect(Order order, String channel) {
        BigDecimal amount = order.quotedPrice();
        String ref = order.id().toString();
        if (order.country() == CountryCode.AO) {
            return "CARD_PRESENT".equalsIgnoreCase(channel)
                    ? multicaixa.captureCardPresent(ref, amount, "0000")
                    : multicaixa.initiateExpress(ref, amount, "Transport " + order.pickup() + "→" + order.dropoff());
        }
        // PT would go to SIBS/MB WAY here.
        throw new UnsupportedOperationException("PT payment rail not yet implemented (skeleton ships AO only)");
    }

    /** Poll the payment status of a previously-initiated AO transaction. */
    public Map<String, Object> status(CountryCode country, String transactionId) {
        if (country != CountryCode.AO) {
            throw new UnsupportedOperationException("PT payment poll not yet implemented");
        }
        return multicaixa.status(transactionId);
    }
}
