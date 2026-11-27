package com.tms.core.dispatch;

import com.tms.compliance.domain.CountryCode;
import com.tms.core.fleet.Vehicle;
import com.tms.core.order.Order;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assigns available vehicles to new orders.
 *
 * <p>The skeleton keeps an in-process fleet registry keyed by id. A real
 * dispatch service would pull vehicles from the fleet database, apply
 * geospatial + capacity + driver-hours rules, and emit a dispatch event. The
 * seam that matters here is: <b>dispatch produces a {@link Trip} whose
 * completion triggers billing</b>, and billing triggers fiscal compliance.</p>
 *
 * <pre>
 *   Order (NEW) ──dispatch──► Trip (ASSIGNED) ──complete──► Trip (COMPLETED)
 *                                                            │
 *                                            BillingService.invoice(trip)
 *                                                            ▼
 *                                          ComplianceFacade.submit(country, invoice)
 * </pre>
 */
@ApplicationScoped
public class DispatchService {

    private final Map<UUID, Vehicle> fleet = new ConcurrentHashMap<>();
    private final Map<UUID, Trip> trips = new ConcurrentHashMap<>();

    /** Register a vehicle so it can be dispatched. Idempotent on vehicle id. */
    public Vehicle registerVehicle(Vehicle vehicle) {
        fleet.put(vehicle.id(), vehicle);
        return vehicle;
    }

    /**
     * Assign the first available vehicle in the order's country to the order.
     * Returns empty if no vehicle is available — the order stays NEW and the
     * caller may retry or escalate.
     */
    public Optional<Trip> dispatch(Order order) {
        Vehicle vehicle = fleet.values().stream()
                .filter(v -> v.available() && v.country() == order.country())
                .findFirst()
                .map(Vehicle::reserve)
                .orElse(null);
        if (vehicle == null) return Optional.empty();
        fleet.put(vehicle.id(), vehicle);
        Trip trip = Trip.assigned(order, vehicle);
        trips.put(trip.id(), trip);
        return Optional.of(trip);
    }

    /** Mark a trip as completed — the trigger for billing. */
    public Trip complete(UUID tripId) {
        Trip trip = trips.get(tripId);
        if (trip == null) throw new IllegalArgumentException("Unknown trip: " + tripId);
        Trip completed = trip.complete();
        trips.put(tripId, completed);
        // Release the vehicle back to the pool.
        Vehicle released = fleet.get(trip.vehicleId()).release();
        fleet.put(released.id(), released);
        return completed;
    }

    public Optional<Trip> trip(UUID tripId) {
        return Optional.ofNullable(trips.get(tripId));
    }
}
