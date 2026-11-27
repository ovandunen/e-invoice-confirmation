package com.tms.compliance.adapter.angola.client;

import java.util.Map;

/**
 * Skeleton of the AGT e-invoice REST client (the <b>Adaptee</b> for Angola).
 *
 * <p>Mirrors the AGT API at {@code https://sifp.minfin.gov.ao/sigt/fe/v1/}.
 * The adapter is the only place that knows AGT exists. Key behaviors that
 * differ from Fiskaly and that the adapter must absorb:</p>
 *
 * <ul>
 *   <li><b>HTTP Basic Auth</b> with a per-taxpayer user/password (not Bearer).</li>
 *   <li><b>JWS RS256 detached signature</b> on the invoice JSON, signed with the
 *       taxpayer's RSA private key (the public cert is pre-registered with AGT).</li>
 *   <li><b>Asynchronous</b>: {@code registarFactura} returns PENDENTE; the
 *       adapter must poll {@code consultarEstado} until PROCESSADO / REJEITADO.</li>
 *   <li><b>QR code</b> is returned separately by {@code consultarFactura} once
 *       the invoice reaches PROCESSADO state.</li>
 * </ul>
 */
public interface AgtClient {

    /** Create a document series with AGT (criarSerie endpoint). */
    Map<String, Object> criarSerie(String nif, String seriesCode, String documentType);

    /**
     * Submit a JWS-signed invoice for validation (registarFactura).
     * Returns a request id and the initial PENDENTE status — never ACCEPTED
     * directly.
     *
     * @param signedInvoiceJson the invoice JSON, already signed as JWS RS256 detached.
     * @param jwsHeader         the protected JWS header (alg, kid, x5c).
     */
    Map<String, Object> registarFactura(String nif, String signedInvoiceJson, String jwsHeader);

    /**
     * Poll the status of a previously-submitted invoice (consultarEstado).
     * Returns one of: PENDENTE, PROCESSADO, REJEITADO.
     */
    Map<String, Object> consultarEstado(String requestId);

    /**
     * Fetch the validated invoice with QR code (consultarFactura).
     * Only meaningful once consultarEstado returns PROCESSADO.
     */
    Map<String, Object> consultarFactura(String facturaId);
}
