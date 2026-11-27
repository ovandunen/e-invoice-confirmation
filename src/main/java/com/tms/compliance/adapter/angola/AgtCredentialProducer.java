package com.tms.compliance.adapter.angola;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CDI producer for the Angola credential providers.
 *
 * <p>{@link SoftwareCredentialProvider} is a vendor-level singleton (one TMS
 * vendor, one software certificate) — produced here from flat config.
 * {@link EmitterCredentialProvider} is per-tenant (one per operator NIF) — in
 * the single-tenant skeleton we produce a single default emitter from flat
 * config; in production replace with a {@code TenantEmitterRegistry} that
 * looks up the emitter by NIF from the request context.</p>
 *
 * <p>Putting both producers in one place keeps the credential story visible:
 * anyone auditing the Angola adapter's auth can read this file end-to-end.</p>
 */
@ApplicationScoped
public class AgtCredentialProducer {

    @ConfigProperty(name = "tms.compliance.ao.software.product-id", defaultValue = "TMS-Compliance")
    String productId;

    @ConfigProperty(name = "tms.compliance.ao.software.product-version", defaultValue = "0.1.0")
    String productVersion;

    @ConfigProperty(name = "tms.compliance.ao.software.validation-number", defaultValue = "AGT-VALID-0000")
    String softwareValidationNumber;

    @ConfigProperty(name = "tms.compliance.ao.agt.nif", defaultValue = "AO541700100")
    String emitterNif;

    @ConfigProperty(name = "tms.compliance.ao.agt.username", defaultValue = "demo-user")
    String agtUsername;

    @ConfigProperty(name = "tms.compliance.ao.agt.password", defaultValue = "demo-pass")
    String agtPassword;

    @Produces
    @ApplicationScoped
    public SoftwareCredentialProvider produceSoftwareCredential() {
        return new SoftwareCredentialProvider(
                productId, productVersion, softwareValidationNumber);
    }

    /**
     * Single-tenant default emitter. In production, replace with a per-tenant
     * registry that returns the right {@link EmitterCredentialProvider} for the
     * operator NIF in the request context.
     */
    @Produces
    @ApplicationScoped
    public EmitterCredentialProvider produceEmitterCredential() {
        return new EmitterCredentialProvider(
                emitterNif, agtUsername, agtPassword, null /* private key loaded from Vault in prod */);
    }
}
