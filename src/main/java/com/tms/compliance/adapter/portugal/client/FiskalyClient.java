package com.tms.compliance.adapter.portugal.client;

import java.util.Map;

/**
 * Skeleton of the Fiskaly SIGN PT REST client (the <b>Adaptee</b> for Portugal).
 *
 * <p>This is NOT a real HTTP client — it is a typed contract showing exactly
 * which Fiskaly calls the {@code PortugalComplianceAdapter} depends on. In
 * production you'd back this with a Quarkus REST client
 * ({@code @RegisterRestClient}) pointing at {@code https://api.fiskaly.com}.</p>
 *
 * <p>The methods here mirror Fiskaly's Unified API: authentication by api_key /
 * api_secret, Bearer token, per-organization-per-NIF state. The adapter is the
 * only place that knows Fiskaly exists.</p>
 */
public interface FiskalyClient {

    /** Exchange api_key/api_secret for a 24h access_token. */
    String authenticate();

    /** Create or look up a series (Fiskaly "tx_threshold" / "sequence" object). */
    Map<String, Object> registerSeries(String nif, String seriesCode, String documentType);

    /**
     * Submit an invoice for signing. Fiskaly signs it (ATCUD + hash chain) and
     * returns the signed document synchronously — Fiskaly is NOT async.
     *
     * @param signedInvoiceRequest Fiskaly-shaped request body.
     * @return Map with keys: atcud, qr_code_base64, hash, state.
     */
    Map<String, Object> submitSignedInvoice(String nif, Map<String, Object> signedInvoiceRequest);

    /** Fetch a previously-submitted invoice by Fiskaly id (used for QR retrieval / reconciliation). */
    Map<String, Object> getInvoice(String nif, String fiskalyId);
}
