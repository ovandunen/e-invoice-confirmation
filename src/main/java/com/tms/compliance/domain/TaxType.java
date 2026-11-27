package com.tms.compliance.domain;

/**
 * Tax systems recognised by AGT (§4.1.6 of DS.120) and AT Portugal.
 *
 * <p>AO uses IVA (VAT), IS (Imposto de Selo, stamp duty), IEC (Imposto Especial
 * de Consumo, excise), and NS (não sujeito — not subject to any of the above).
 * PT uses IVA and IS; IEC is not a PT concept but the enum stays shared for
 * simplicity.</p>
 */
public enum TaxType {
    IVA, // Imposto s/ o Valor Acrescentado (VAT)
    IS,  // Imposto de Selo (stamp duty)
    IEC, // Imposto Especial de Consumo (excise, AO)
    NS   // Não sujeito a IVA, IS ou IEC
}
