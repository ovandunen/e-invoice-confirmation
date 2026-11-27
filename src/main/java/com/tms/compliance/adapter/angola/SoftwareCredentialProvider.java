package com.tms.compliance.adapter.angola;

import java.security.PrivateKey;

/**
 * Holds the <b>software producer's</b> credentials — the TMS vendor's identity
 * as registered and certified with AGT.
 *
 * <p>Per DS.120 §4.1.6, every AGT request must carry a {@code softwareInfo}
 * object with four fields:</p>
 * <ul>
 *   <li>{@code productId} — the TMS product name (e.g. "TMS-Compliance")</li>
 *   <li>{@code productVersion} — e.g. "0.1.0"</li>
 *   <li>{@code softwareValidationNumber} — the AGT-issued certification number
 *       (without this, every call fails with error E07)</li>
 *   <li>{@code jwsSoftwareSignature} — RS256 signature over the three fields
 *       above, made with the software producer's private key (errors E08/E39
 *       if it doesn't match the certification record)</li>
 * </ul>
 *
 * <p>This bean is a <b>vendor-level singleton</b>: the same software credential
 * is used across all operator tenants. It is NOT the per-tenant emitter
 * credential — see {@link EmitterCredentialProvider}.</p>
 *
 * <p>In production the {@link #softwarePrivateKey()} is loaded from a secrets
 * store (Vault / Kubernetes secret), never from a flat config property. The
 * skeleton returns {@code null} because real JWS signing requires
 * {@code nimbus-jose-jwt} (listed, commented out, in the pom).</p>
 */
public class SoftwareCredentialProvider {

    private final String productId;
    private final String productVersion;
    private final String softwareValidationNumber;

    public SoftwareCredentialProvider(String productId, String productVersion, String softwareValidationNumber) {
        this.productId = productId;
        this.productVersion = productVersion;
        this.softwareValidationNumber = softwareValidationNumber;
    }

    public String productId()                  { return productId; }
    public String productVersion()             { return productVersion; }
    public String softwareValidationNumber()   { return softwareValidationNumber; }

    /**
     * The software producer's RSA private key, used to sign the
     * {@code softwareInfo} block on every request.
     *
     * <p>Returns {@code null} in the skeleton. Real implementation loads from a
     * PKCS#8 / PKCS#12 keystore backed by a secret manager.</p>
     */
    public PrivateKey softwarePrivateKey() {
        return null; // TODO: load from Vault/K8s secret in production
    }
}
