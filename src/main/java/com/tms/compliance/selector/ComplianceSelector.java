package com.tms.compliance.selector;

import com.tms.compliance.domain.CountryCode;
import com.tms.compliance.spi.ComplianceException;
import com.tms.compliance.spi.CountryQualifier;
import com.tms.compliance.spi.InvoiceComplianceAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Routes a {@link CountryCode} to the right {@link InvoiceComplianceAdapter}
 * at runtime.
 *
 * <p>This is the runtime half of the adapter pattern. The compile-time half is
 * the {@link InvoiceComplianceAdapter} interface plus the two concrete adapters;
 * the runtime half is the dispatch from country to adapter. The TMS Core never
 * sees concrete adapter classes — it asks the selector.</p>
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li>At construction, CDI injects {@code Instance<InvoiceComplianceAdapter>}
 *       — all beans implementing the interface, across all countries.</li>
 *   <li>The selector iterates them and builds a {@link Map} keyed by
 *       {@link InvoiceComplianceAdapter#country()}.</li>
 *   <li>On each call, it looks up the adapter for the requested country. If
 *       none is registered, it throws with a clear code rather than returning
 *       null.</li>
 * </ul>
 *
 * <h2>Why a selector and not direct {@code @CountryQualifier} injection?</h2>
 * The qualifier approach would require every caller to already know which
 * country they want at the injection point — fine for a single-tenant deployment
 * but wrong for a multi-tenant SaaS where the country comes from the operator's
 * tenant context per request. The selector centralizes that lookup.
 */
@ApplicationScoped
public class ComplianceSelector {

    private final Map<CountryCode, InvoiceComplianceAdapter> byCountry;

    @Inject
    public ComplianceSelector(Instance<InvoiceComplianceAdapter> adapters) {
        Map<CountryCode, InvoiceComplianceAdapter> map = new EnumMap<>(CountryCode.class);
        // Null-safe so test subclasses can override forCountry() without a CDI Instance.
        if (adapters != null) {
            for (InvoiceComplianceAdapter adapter : adapters) {
                CountryCode c = adapter.country();
                if (map.put(c, adapter) != null) {
                    throw new IllegalStateException(
                            "Duplicate InvoiceComplianceAdapter registered for country " + c);
                }
            }
        }
        this.byCountry = map;
    }

    /**
     * Look up the adapter for a country.
     *
     * @throws ComplianceException with code {@code NO_ADAPTER_FOR_COUNTRY} if no
     *         adapter is registered. This usually means a deployment misconfiguration
     *         (e.g. trying to issue Angolan invoices from a PT-only build).
     */
    public InvoiceComplianceAdapter forCountry(CountryCode country) {
        InvoiceComplianceAdapter adapter = byCountry.get(country);
        if (adapter == null) {
            throw new ComplianceException(
                    "NO_ADAPTER_FOR_COUNTRY",
                    "No InvoiceComplianceAdapter registered for country " + country
                            + ". Registered: " + byCountry.keySet());
        }
        return adapter;
    }

    /** True if this deployment can issue invoices for the given country. */
    public boolean supports(CountryCode country) {
        return byCountry.containsKey(country);
    }
}
