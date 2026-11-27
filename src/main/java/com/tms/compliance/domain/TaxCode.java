package com.tms.compliance.domain;

/**
 * Tax codes recognised by AGT (§4.1.6 of DS.120). The permitted value depends
 * on the {@link TaxType}:
 *
 * <ul>
 *   <li><b>IVA</b> → NOR (normal), INT (intermédia), RED (reduzida),
 *       ISE (isento), OUT (outra).</li>
 *   <li><b>IS</b> → optional IS verba code (see Annex 9.6 of DS.120), or ISE.</li>
 *   <li><b>IEC</b> → optional pautal code (see Annex 9.7 of DS.120), or ISE.</li>
 *   <li><b>NS</b> → not applicable; the adapter must still set taxExemptionCode.</li>
 * </ul>
 *
 * <p>For a taxi/courier TMS the common case is {@link #NOR} (14% IVA in Angola).</p>
 */
public enum TaxCode {
    NOR, // Taxa normal
    INT, // Taxa intermédia
    RED, // Taxa reduzida
    ISE, // Isento
    OUT  // Outra
}
