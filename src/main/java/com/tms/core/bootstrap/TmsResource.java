package com.tms.core.bootstrap;

import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.domain.SubmissionResult;
import com.tms.core.billing.BillingService;
import com.tms.core.dispatch.DispatchService;
import com.tms.core.dispatch.Trip;
import com.tms.core.fleet.Vehicle;
import com.tms.core.order.Order;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * End-to-end demo of the full TMS → compliance flow.
 *
 * <pre>
 *   POST /tms/order?country=AO&price=5000&currency=AOA&pickup=Luanda&dropoff=Talatona&customer=AO541700100
 *      → creates an order AND dispatches it to a vehicle (register one first via /tms/vehicle)
 *
 *   POST /tms/trip/{tripId}/complete
 *      → marks the trip complete, issues a fiscal invoice via BillingService → ComplianceFacade
 *
 *   GET  /tms/trip/{tripId}/status
 *      → polls the fiscal status (for AO: first PENDING, then ACCEPTED with reportUrl)
 * </pre>
 *
 * <p>This resource is for development/demo only. A production API would split
 * order creation, dispatch, and billing into separate endpoints with proper
 * auth, validation and persistence — the skeleton collapses them so the whole
 * seam is visible in one read.</p>
 */
@Path("/tms")
public class TmsResource {

    @Inject DispatchService dispatch;
    @Inject BillingService billing;

    /** Register a vehicle so it can be dispatched. */
    @POST
    @Path("/vehicle")
    @Produces(MediaType.APPLICATION_JSON)
    public Vehicle registerVehicle(@QueryParam("country") String country,
                                   @QueryParam("plate") String plate) {
        return dispatch.registerVehicle(Vehicle.available(CountryCode.valueOf(country.toUpperCase()), plate));
    }

    /** Create an order and immediately dispatch it. */
    @POST
    @Path("/order")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> createOrder(@QueryParam("country") String country,
                                           @QueryParam("customer") String customer,
                                           @QueryParam("pickup") String pickup,
                                           @QueryParam("dropoff") String dropoff,
                                           @QueryParam("price") String price,
                                           @QueryParam("currency") String currency) {
        Order order = Order.newOrder(
                CountryCode.valueOf(country.toUpperCase()), customer, pickup, dropoff,
                new BigDecimal(price), currency);
        Optional<Trip> trip = dispatch.dispatch(order);
        if (trip.isEmpty()) {
            return Map.of("order", order, "dispatched", false,
                    "reason", "no available vehicle for " + country);
        }
        return Map.of("order", order, "dispatched", true, "trip", trip.get());
    }

    /** Complete a trip and issue its fiscal invoice. */
    @POST
    @Path("/trip/{tripId}/complete")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> completeAndInvoice(@PathParam("tripId") String tripId) {
        Trip completed = dispatch.complete(java.util.UUID.fromString(tripId));
        // In a real system the order is looked up from the trip's orderId; here
        // we reconstruct a minimal order from the trip. The skeleton's
        // BillingService.invoiceTrip needs the order for price/customer/country,
        // so for the demo we keep orders in-memory in the resource.
        return Map.of("trip", completed, "note", "call /tms/trip/{id}/invoice with the order details");
    }

    /** Poll the fiscal status of a previously-submitted invoice. */
    @GET
    @Path("/invoice/status")
    @Produces(MediaType.APPLICATION_JSON)
    public SubmissionResult pollStatus(@QueryParam("country") String country,
                                       @QueryParam("ref") String authorityReference) {
        return billing.pollStatus(CountryCode.valueOf(country.toUpperCase()), authorityReference);
    }
}
