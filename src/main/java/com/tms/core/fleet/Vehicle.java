package com.tms.core.fleet;

import com.tms.compliance.domain.CountryCode;

import java.util.UUID;

/**
 * A vehicle in the fleet. The skeleton models the minimum the dispatch and
 * billing flows need: an id, a plate, the country it operates in, and whether
 * it is currently available.
 *
 * <p>Real TMS vehicles carry far more (telematics id, capacity, fuel type,
 * driver assignment, maintenance schedule). Those concerns are orthogonal to
 * fiscal compliance and are deliberately out of scope for this skeleton —
 * the point is to show the seam between fleet and billing/fiscal, not to
 * rebuild a full transport management system.</p>
 */
public record Vehicle(
        UUID id,
        String plate,
        CountryCode country,
        boolean available) {

    public static Vehicle available(CountryCode country, String plate) {
        return new Vehicle(UUID.randomUUID(), plate, country, true);
    }

    public Vehicle reserve() { return new Vehicle(id, plate, country, false); }
    public Vehicle release() { return new Vehicle(id, plate, country, true); }
}
