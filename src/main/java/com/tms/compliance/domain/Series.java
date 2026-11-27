package com.tms.compliance.domain;

import java.time.LocalDate;

/**
 * A document series registered (or to be registered) with the tax authority.
 *
 * <p>Both Portugal and Angola require invoices to be issued in registered series
 * (e.g. "FT 2026/A"). The Core manages the series lifecycle; the Adapter knows
 * how to register/validate the series with the right authority.</p>
 *
 * <h2>AGT-specific fields (§4.5 of DS.120)</h2>
 * <p>AGT's {@code solicitarSerie} returns a {@code seriesFEResult} object with
 * four fields that the TMS must persist to know which document numbers it is
 * allowed to issue:</p>
 * <ul>
 *   <li>{@code seriesCode} — the AGT-assigned series code (becomes {@link #code}).</li>
 *   <li>{@code authorizedQuantity} — how many invoices AGT authorised in this series.</li>
 *   <li>{@code firstDocumentNo} — first allowed document number.</li>
 *   <li>{@code lastDocumentNo} — last allowed document number.</li>
 * </ul>
 * <p>For Portugal (ATCUD), only {@link #authorityValidationCode} is populated.</p>
 */
public final class Series {

    /** Human-readable / AGT-assigned code, e.g. "FT 2026/A". */
    private final String code;

    /** Document type the series is for. */
    private final DocumentType documentType;

    /** Year the series is valid for (AGT {@code seriesYear}). */
    private final int seriesYear;

    /** First date the series is valid from. */
    private final LocalDate validFrom;

    /** Authority-issued validation code (ATCUD in PT; null for AO). */
    private final String authorityValidationCode;

    /** AGT-only: how many invoices AGT authorised in this series. 0 for PT. */
    private final int authorizedQuantity;

    /** AGT-only: first document number allowed in this series. Null for PT. */
    private final String firstDocumentNo;

    /** AGT-only: last document number allowed in this series. Null for PT. */
    private final String lastDocumentNo;

    /** AGT-only: contingency indicator — "N" (normal) or "C" (contingency). Null for PT. */
    private final String contingencyIndicator;

    public Series(String code, DocumentType documentType, int seriesYear, LocalDate validFrom,
                  String authorityValidationCode,
                  int authorizedQuantity, String firstDocumentNo, String lastDocumentNo,
                  String contingencyIndicator) {
        this.code = code;
        this.documentType = documentType;
        this.seriesYear = seriesYear;
        this.validFrom = validFrom;
        this.authorityValidationCode = authorityValidationCode;
        this.authorizedQuantity = authorizedQuantity;
        this.firstDocumentNo = firstDocumentNo;
        this.lastDocumentNo = lastDocumentNo;
        this.contingencyIndicator = contingencyIndicator;
    }

    /** Convenience constructor for PT (ATCUD only, no AGT series-result fields). */
    public static Series forPortugal(String code, DocumentType documentType, int seriesYear,
                                     LocalDate validFrom, String atcudValidationCode) {
        return new Series(code, documentType, seriesYear, validFrom, atcudValidationCode,
                0, null, null, null);
    }

    /** Convenience constructor for AO before registration (no AGT-assigned fields yet). */
    public static Series forAngolaRequest(DocumentType documentType, int seriesYear,
                                          String contingencyIndicator) {
        return new Series(null, documentType, seriesYear, LocalDate.now(), null,
                0, null, null, contingencyIndicator);
    }

    public String code()                       { return code; }
    public DocumentType documentType()         { return documentType; }
    public int seriesYear()                    { return seriesYear; }
    public LocalDate validFrom()               { return validFrom; }
    public String authorityValidationCode()    { return authorityValidationCode; }
    public int authorizedQuantity()            { return authorizedQuantity; }
    public String firstDocumentNo()            { return firstDocumentNo; }
    public String lastDocumentNo()             { return lastDocumentNo; }
    public String contingencyIndicator()       { return contingencyIndicator; }
}
