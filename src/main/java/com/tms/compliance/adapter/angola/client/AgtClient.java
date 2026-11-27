package com.tms.compliance.adapter.angola.client;

import java.util.List;
import java.util.Map;

/**
 * The AGT e-invoice REST client (the <b>Adaptee</b> for Angola).
 *
 * <p>Mirrors the real AGT DS.120 specification (v1.1, Nov 2025). Base URL:
 * sandbox {@code https://sifphml.minfin.gov.ao/sigt/fe/v1/},
 * production {@code https://sifp.minfin.gov.ao/sigt/fe/v1/}.
 *
 * <p>The adapter is the only place that knows AGT exists. Key behaviors per the
 * real spec that differ from the earlier skeleton:</p>
 *
 * <ul>
 *   <li><b>All 7 services are synchronous</b> (§4.1.2, §4.2.2, etc.). There is
 *       no PENDENTE returned by {@code registarFactura} — it returns a receipt
 *       ({@code requestID}) synchronously; validation is deferred and queried
 *       via {@code obterEstado}.</li>
 *   <li><b>HTTP Basic Auth</b> with per-emitter Username/Password headers
 *       (§4.1.5), enforced by OWSM at the OSB layer.</li>
 *   <li><b>Two JWS RS256 signatures</b> per {@code registarFactura}: a software
 *       signature over {@code softwareInfo} and a per-document signature over
 *       the document fields. Subsequent calls carry a request-level
 *       {@code jwsSignature} over {@code taxRegistrationNumber + requestID}.</li>
 *   <li><b>Batch submission</b> — up to 30 invoices per {@code registarFactura}
 *       call (§4.1.6, "Prevê-se um máximo de 30 facturas").</li>
 *   <li><b>No base64 QR PNG</b> — the spec returns a {@code reportUrl} via
 *       {@code consultarFactura}, not a QR image. The printed QR is generated
 *       client-side by the emitter software from the validated document data.</li>
 * </ul>
 *
 * <h2>Endpoint map</h2>
 * <table>
 *   <tr><th>Method</th><th>Endpoint</th><th>Service §</th></tr>
 *   <tr><td>{@link #registarFactura}</td><td>POST /registarFactura</td><td>4.1</td></tr>
 *   <tr><td>{@link #obterEstado}</td><td>POST /obterEstado</td><td>4.2</td></tr>
 *   <tr><td>{@link #listarFacturas}</td><td>POST /listarFacturas</td><td>4.3</td></tr>
 *   <tr><td>{@link #consultarFactura}</td><td>POST /consultarFactura</td><td>4.4</td></tr>
 *   <tr><td>{@link #solicitarSerie}</td><td>POST /solicitarSerie</td><td>4.5</td></tr>
 *   <tr><td>{@link #listarSeries}</td><td>POST /listarSeries</td><td>4.6</td></tr>
 *   <tr><td>{@link #validarDocumento}</td><td>POST /validarDocumento</td><td>4.7</td></tr>
 * </table>
 *
 * <p>All methods are POST (even the "list"/"consult" ones — AGT uses POST with
 * a JSON body for everything, never GET). All require the
 * {@code Username}/{@code Password} headers (handled by a CDI auth filter in
 * production, set by the adapter from {@code EmitterCredentialProvider}).</p>
 *
 * <p>Request/response bodies are typed as {@code Map<String, Object>} in the
 * skeleton so the project compiles without the real REST client. In production,
 * replace with Jackson-annotated DTOs and a Quarkus {@code @RegisterRestClient}.</p>
 */
public interface AgtClient {

    /**
     * 4.1 — Submit one or more invoices for registration (deferred validation).
     *
     * <p>Synchronous: returns a {@code requestID} immediately. The actual
     * validation result comes later via {@link #obterEstado}.</p>
     *
     * @param fullRequest the complete {@code RegistarFacturaRequest} JSON:
     *                    schemaVersion, submissionUUID, taxRegistrationNumber,
     *                    submissionTimeStamp, softwareInfo (with
     *                    jwsSoftwareSignature), numberOfEntries, documents[]
     *                    (each with its own jwsDocumentSignature).
     * @return map with {@code requestID} and optional {@code errorList}.
     */
    Map<String, Object> registarFactura(Map<String, Object> fullRequest);

    /**
     * 4.2 — Poll the deferred validation status of a prior
     * {@link #registarFactura} submission.
     *
     * @param fullRequest {@code ObterEstadoRequest}: schemaVersion,
     *                    submissionUUID, taxRegistrationNumber,
     *                    submissionTimeStamp, softwareInfo, jwsSignature,
     *                    requestID.
     * @return map with {@code requestID}, {@code resultCode} (0/1/2/7/8/9),
     *         {@code documentStatusList[]} (each with documentNo,
     *         documentStatus V|I, errorList), {@code requestErrorList}.
     */
    Map<String, Object> obterEstado(Map<String, Object> fullRequest);

    /**
     * 4.3 — List invoices matching filter criteria (date range, status, etc.).
     *
     * @param fullRequest {@code ListarFacturasRequest}.
     * @return map with the list of matching invoices.
     */
    Map<String, Object> listarFacturas(Map<String, Object> fullRequest);

    /**
     * 4.4 — Consult a single validated invoice by invoiceNo.
     *
     * <p>This is where the {@code reportUrl} lives — inside the returned
     * {@code document} object.</p>
     *
     * @param fullRequest {@code ConsultarFacturaRequest}: schemaVersion,
     *                    submissionUUID, taxRegistrationNumber,
     *                    submissionTimeStamp, softwareInfo, jwsSignature,
     *                    invoiceNo.
     * @return map with {@code documentNo}, {@code documentStatus}, and the full
     *         {@code document} object (including {@code reportUrl},
     *         {@code jwsSignature}, totals, lines, etc.).
     */
    Map<String, Object> consultarFactura(Map<String, Object> fullRequest);

    /**
     * 4.5 — Request a new document series (or a contingency series).
     *
     * @param fullRequest {@code SolicitarSerieRequest}: schemaVersion,
     *                    taxRegistrationNumber, softwareInfo, jwsSignature,
     *                    seriesYear, documentType, establishmentNumber,
     *                    seriesContingencyIndicator (N|C).
     * @return map with {@code resultCode}, {@code errorList}, and on success
     *         {@code seriesFEResult} (seriesCode, authorizedQuantity,
     *         firstDocumentNo, lastDocumentNo).
     */
    Map<String, Object> solicitarSerie(Map<String, Object> fullRequest);

    /**
     * 4.6 — List the emitter's registered series.
     *
     * @param fullRequest {@code ListarSeriesRequest}.
     * @return map with the list of series.
     */
    Map<String, Object> listarSeries(Map<String, Object> fullRequest);

    /**
     * 4.7 — Buyer-side action: confirm or reject a received document.
     *
     * <p>This is the buyer's response to a document the emitter submitted.
     * Returns {@code actionResultCode} (C_OK/R_OK/C_NOK/R_NOK) and
     * {@code documentStatusCode} (S_A/S_C/S_I/S_RG/S_RJ/S_V).</p>
     *
     * @param fullRequest {@code ValidarDocumentoRequest}: schemaVersion,
     *                    taxRegistrationNumber, softwareInfo, jwsSignature,
     *                    documentNo, action (C|R),
     *                    deductibleVATPercentage | nonDeductibleAmount.
     */
    Map<String, Object> validarDocumento(Map<String, Object> fullRequest);
}
