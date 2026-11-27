package com.tms.core.order;

import com.tms.compliance.domain.CountryCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A transport order placed by a customer (a parcel to deliver, a ride to book).
 *
 * <p>This is the entry point of the TMS Core's domain. An order is created in
 * state {@link Status#NEW}, dispatched to a vehicle ({@link DispatchService}),
 * executed as a trip, and finally billed — the billing step produces an
 * {@link com.tms.compliance.domain.Invoice} that goes through the compliance
 * facade for fiscal validation.</p>
 *
 * <p>Every order carries its {@link CountryCode} because the same TMS binary
 * serves PT and AO; the country decides which fiscal adapter, which payment
 * rail (Multicaixa in AO, MB WAY/SIBS in PT) and which currency apply.</p>
 */
public record Order(
        UUID id,
        CountryCode country,
        String customerId,
        String pickup,
        String dropoff,
        BigDecimal quotedPrice,
        String currency,
        Instant createdAt,
        Status status) {

    public enum Status { NEW, DISPATCHED, IN_PROGRESS, COMPLETED, CANCELLED, BILLED }

    public static Order newOrder(CountryCode country, String customerId, String pickup, String dropoff,
                                 BigDecimal quotedPrice, String currency) {
        return new Order(UUID.randomUUID(), country, customerId, pickup, dropoff,
                quotedPrice, currency, Instant.now(), Status.NEW);
    }

    public Order withStatus(Status s) {
        return new Order(id, country, customerId, pickup, dropoff, quotedPrice, currency, createdAt, s);
    }
}
