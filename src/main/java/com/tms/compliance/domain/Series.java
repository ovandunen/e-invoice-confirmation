package com.tms.compliance.domain;

import java.time.LocalDate;

/**
 * A document series registered (or to be registered) with the tax authority.
 *
 * <p>Both Portugal and Angola require invoices to be issued in registered series
 * (e.g. "FT 2026/A"). The Core manages the series lifecycle; the Adapter knows
 * how to register/validate the series with the right authority.</p>
 *
 * <p>This shape intentionally avoids leaking ATCUD validation codes or AGT
 * series hashes — those are adapter-side concerns surfaced only when needed.</p>
 */
public final class Series {

    /** Human-readable code, e.g. "FT 2026/A". */
    private final String code;

    /** Document type the series is for: invoice, credit note, receipt, etc. */
    private final String documentType;

    /** First date the series is valid from. */
    private final LocalDate validFrom;

    /** Authority-issued validation code (ATCUD in PT; AGT reference in AO). Null until registered. */
    private final String authorityValidationCode;

    public Series(String code, String documentType, LocalDate validFrom, String authorityValidationCode) {
        this.code = code;
        this.documentType = documentType;
        this.validFrom = validFrom;
        this.authorityValidationCode = authorityValidationCode;
    }

    public String code()                       { return code; }
    public String documentType()               { return documentType; }
    public LocalDate validFrom()               { return validFrom; }
    public String authorityValidationCode()    { return authorityValidationCode; }
}
