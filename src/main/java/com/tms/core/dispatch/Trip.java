package com.tms.core.dispatch;

import com.tms.core.fleet.Vehicle;
import com.tms.core.order.Order;

import java.time.Instant;
import java.util.UUID;

/**
 * A dispatched trip: one order, one vehicle, a lifecycle from ASSIGNED to
 * COMPLETED. Completing a trip is the trigger for {@link com.tms.core.billing.BillingService}
 * to issue a fiscal invoice.
 *
 * <p>The trip carries the {@link Order#quotedPrice()} as the basis for billing.
 * Real systems recompute the final price from distance/time telematics; here
 * the quoted price stands as the billed amount so the skeleton stays focused
 * on the fiscal seam.</p>
 */
public record Trip(
        UUID id,
        UUID orderId,
        UUID vehicleId,
        Instant assignedAt,
        Instant completedAt,
        Status status) {

    public enum Status { ASSIGNED, COMPLETED, CANCELLED }

    public static Trip assigned(Order order, Vehicle vehicle) {
        return new Trip(UUID.randomUUID(), order.id(), vehicle.id(), Instant.now(), null, Status.ASSIGNED);
    }

    public Trip complete() {
        return new Trip(id, orderId, vehicleId, assignedAt, Instant.now(), Status.COMPLETED);
    }
}
