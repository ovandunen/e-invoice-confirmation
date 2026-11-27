package com.tms.compliance.spi;

import com.tms.compliance.domain.CountryCode;
import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI qualifier that tags an {@link InvoiceComplianceAdapter} bean with the
 * country it implements.
 *
 * <p>This is the routing key the {@link com.tms.compliance.selector.ComplianceSelector}
 * uses to find the right adapter at runtime. One adapter bean per country,
 * annotated with the matching {@link CountryCode}.</p>
 *
 * <pre>
 *   &#64;ApplicationScoped
 *   &#64;CountryQualifier(CountryCode.PT)
 *   public class PortugalComplianceAdapter implements InvoiceComplianceAdapter { ... }
 *
 *   &#64;ApplicationScoped
 *   &#64;CountryQualifier(CountryCode.AO)
 *   public class AngolaComplianceAdapter implements InvoiceComplianceAdapter { ... }
 * </pre>
 *
 * <p>Using a CDI qualifier (rather than just calling {@code adapter.country()})
 * lets the container fail fast at startup if no adapter is registered for a
 * country the deployment expects to support — instead of failing at the first
 * runtime call.</p>
 *
 * <p>Lives in the {@code spi} package (not under a specific country) because it
 * is shared by every concrete adapter regardless of country.</p>
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface CountryQualifier {
    CountryCode value();
}
