package com.tms.compliance.domain;

/**
 * ISO 3166-1 alpha-2 country code, restricted to the markets the TMS supports.
 *
 * <p>This is the <em>only</em> discriminator used by the adapter layer to pick
 * the correct compliance backend. Keep it small and explicit — never use a raw
 * String for country, you will regret it the moment Brazil is added.</p>
 */
public enum CountryCode {
    PT, // Portugal — Autoridade Tributária (AT)
    AO  // Angola  — Administração Geral Tributária (AGT)
}
