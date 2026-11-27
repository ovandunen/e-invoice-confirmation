package com.tms.compliance.adapter.angola;

import java.security.PrivateKey;

/**
 * Holds the <b>emitter's</b> (taxpayer's) credentials — per-tenant, the
 * operator's identity as registered with AGT for electronic invoicing.
 *
 * <p>Per DS.120 §4.1.6, two signatures are required on every
 * {@code registarFactura} call:</p>
 * <ul>
 *   <li>{@code jwsSoftwareSignature} — signed with the software producer's key
 *       (see {@link SoftwareCredentialProvider}).</li>
 *   <li>{@code jwsDocumentSignature} — signed with the <b>emitter's</b> private
 *       key, over the document fields: documentNo, taxRegistrationNumber,
 *       documentType, documentDate, customerTaxID, customerCountry, companyName,
 *       documentTotals.</li>
 * </ul>
 *
 * <p>And a third signature on subsequent calls ({@code obterEstado},
 * {@code solicitarSerie}, {@code validarDocumento}): {@code jwsSignature} over
 * {@code taxRegistrationNumber + requestID}, signed with the emitter's key.</p>
 *
 * <p>Error codes E40/E08 cover signature-mismatch failures — these will be the
 * most common production errors.</p>
 *
 * <h2>Multi-tenant lookup</h2>
 * <p>The skeleton is single-tenant: one emitter per deployment. In production,
 * replace this with a {@code TenantEmitterRegistry} that looks up the emitter
 * credential by the operator's NIF (passed in the request context), loading
 * each operator's private key from a per-tenant secret. The HTTP Basic
 * {@code Username}/{@code Password} pair issued by AGT is also per-emitter.</p>
 */
public class EmitterCredentialProvider {

    /** The operator's Angolan NIF (taxRegistrationNumber in DS.120). */
    private final String nif;

    /** HTTP Basic Username issued by AGT to this emitter. */
    private final String username;

    /** HTTP Basic Password (token) issued by AGT to this emitter. */
    private final String password;

    /** The emitter's RSA private key, used for jwsDocumentSignature and jwsSignature. */
    private final PrivateKey emitterPrivateKey;

    public EmitterCredentialProvider(String nif, String username, String password,
                                     PrivateKey emitterPrivateKey) {
        this.nif = nif;
        this.username = username;
        this.password = password;
        this.emitterPrivateKey = emitterPrivateKey;
    }

    public String nif()                       { return nif; }
    public String username()                  { return username; }
    public String password()                  { return password; }
    public PrivateKey emitterPrivateKey()     { return emitterPrivateKey; }
}
