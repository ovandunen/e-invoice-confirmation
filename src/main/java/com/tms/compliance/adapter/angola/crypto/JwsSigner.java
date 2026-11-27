package com.tms.compliance.adapter.angola.crypto;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;

import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real JWS RS256 signing for AGT, using nimbus-jose-jwt.
 *
 * <p>DS.120 §4.1.6 mandates three distinct signatures, each signing a different
 * payload with a different key:</p>
 * <ol>
 *   <li><b>jwsSoftwareSignature</b> — RS256 over the canonical JSON of
 *       {@code softwareInfoDetail} (productId, productVersion,
 *       softwareValidationNumber), signed with the software producer's private
 *       key. Errors E08/E39 if it doesn't match the certification record.</li>
 *   <li><b>jwsDocumentSignature</b> — RS256 over the canonical JSON of the
 *       document fields (documentNo, taxRegistrationNumber, documentType,
 *       documentDate, customerTaxID, customerCountry, companyName,
 *       documentTotals), signed with the emitter's private key.</li>
 *   <li><b>jwsSignature</b> (on obterEstado/solicitarSerie/validarDocumento) —
 *       RS256 over {@code taxRegistrationNumber + requestID} (or the relevant
 *       fields), signed with the emitter's private key.</li>
 * </ol>
 *
 * <h2>Detached payload convention</h2>
 * <p>The spec shows the signature in a field of the JSON body, not as an
 * Authorization header. We therefore produce a compact JWS
 * {@code header.payload.signature} where the payload is the canonical JSON
 * (base64url). AGT re-serializes the same fields and verifies.</p>
 *
 * <h2>Graceful skeleton fallback</h2>
 * <p>If no {@link PrivateKey} is supplied (the skeleton case — no real keystore
 * available), {@link #sign(Map, PrivateKey)} returns a deterministic stub
 * string so the project compiles and runs end-to-end. Production supplies real
 * keys via {@link com.tms.compliance.adapter.angola.SoftwareCredentialProvider}
 * and {@link com.tms.compliance.adapter.angola.EmitterCredentialProvider}.</p>
 */
public final class JwsSigner {

    private JwsSigner() {}

    /**
     * Sign the canonical form of {@code claims} with {@code key} as a compact
     * JWS RS256. Returns a stub when {@code key} is null.
     *
     * @param claims the fields to sign (insertion order matters for the
     *               canonical JSON — use a {@link LinkedHashMap})
     * @param key    the RSA private key; null returns a stub
     * @return compact JWS string, or {@code "jws-stub:<hash>"} when no key
     */
    public static String sign(Map<String, Object> claims, PrivateKey key) {
        if (key == null) {
            return "jws-stub:" + canonicalJson(claims).hashCode();
        }
        try {
            // Canonical JSON: sorted keys, no whitespace, so AGT can reproduce
            // the exact bytes for verification. DS.120 does not nail down a JCS
            // profile; this is a sensible deterministic encoding.
            String payload = canonicalJson(claims);

            // JWSObject (not SignedJWT) because we sign an arbitrary canonical
            // JSON payload, not a JWT claims set. The compact serialization is
            // header.payload.signature — AGT re-serializes the same canonical
            // bytes and verifies the signature.
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(JOSEObjectType.JWT)
                    .build();
            JWSObject jws = new JWSObject(header, new Payload(payload));
            JWSSigner signer = new RSASSASigner(key);
            jws.sign(signer);
            return jws.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("JWS RS256 signing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Deterministic JSON serialization for signing. Keys sorted ascending,
     * values quoted/escaped minimally. Good enough for a stable canonical form
     * across JVMs; a production deployment would substitute a strict RFC 8785
     * (JCS) implementation if AGT requires byte-exact reproducibility.
     */
    static String canonicalJson(Map<String, Object> claims) {
        // Defensive copy with sorted keys so two callers passing the same
        // logical claims always produce the same canonical bytes.
        Map<String, Object> sorted = new java.util.TreeMap<>();
        for (var e : claims.entrySet()) {
            sorted.put(e.getKey(), e.getValue());
        }
        return toJson(sorted);
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escape(s) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> sm = new java.util.TreeMap<>();
            for (var e : m.entrySet()) sm.put(String.valueOf(e.getKey()), e.getValue());
            return sm.entrySet().stream()
                    .map(e -> "\"" + escape(e.getKey()) + "\":" + toJson(e.getValue()))
                    .reduce("{", (a, b) -> a.equals("{") ? "{" + b : a + "," + b) + "}";
        }
        if (value instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(",");
                sb.append(toJson(o));
                first = false;
            }
            return sb.append("]").toString();
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
