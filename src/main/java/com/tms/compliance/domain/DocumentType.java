package com.tms.compliance.domain;

/**
 * Document types controlled by AGT (§4.1.6 / §4.5.27 of DS.120) and accepted
 * by AT Portugal. The same vocabulary is used by both authorities because
 * Angola modelled its regime on Portugal's.
 *
 * <p>Not every type is relevant to a taxi/courier TMS — typically only
 * {@link #FT} (Factura), {@link #FR} (Factura/Recibo), {@link #NC} (Nota de
 * Crédito, for refunds) and {@link #ND} (Nota de Débito). The full enum is
 * here for completeness.</p>
 */
public enum DocumentType {
    FA, // Factura de Adiantamento
    FT, // Factura
    FR, // Factura/Recibo
    FG, // Factura Global
    GF, // Factura Genérica (AO only)
    AC, // Aviso de Cobrança
    AR, // Aviso de Cobrança/Recibo
    TV, // Talão de Venda
    RC, // Recibo Emitido
    RG, // Outros Recibos Emitidos
    RE, // Estorno ou Recibo de Estorno
    ND, // Nota de Débito
    NC, // Nota de Crédito
    AF, // Factura/Recibo de Autofacturação
    RP, // Prémio ou Recibo de Prémio
    RA, // Resseguro Aceite
    CS, // Imputação a Co-seguradoras
    LD  // Imputação a Co-seguradora Líder
}
