# AGT DS.120 Spec vs. Skeleton Adapter — Gap Analysis

**Source:** `technical-requirements.pdf` — *Especificação Técnica de Serviço FE – Facturação Eletrónica* (DS.120, v1.1, 06 Nov 2025, AGT/MINFIN, 96 pages)
**Compared against:** the `AngolaComplianceAdapter` skeleton drafted earlier in this conversation.

---

## Executive summary

The skeleton captured the **shape** of the adapter pattern correctly (Target interface, Adapter, Adaptee, Selector), but the **Angola Adaptee layer has seven material inaccuracies** against the real spec. The most serious: the real API is **synchronous with deferred validation** (not async-pending), requires **two separate JWS signatures** (software + document), supports **batch submission of up to 30 invoices per call**, and returns a **`reportUrl`** (not a base64 QR PNG). This document lists every gap and what must change in the code.

---

## 1. The real AGT API at a glance (from DS.120)

| Dimension | Real spec |
|---|---|
| **Protocol** | Dual: SOAP/WSDL **and** REST/JSON, both via Oracle Service Bus (OSB) |
| **REST base URL (sandbox)** | `https://sifphml.minfin.gov.ao/sigt/fe/v1/` |
| **REST base URL (production)** | `https://sifp.minfin.gov.ao/sigt/fe/v1/` |
| **SOAP base URL** | `.../sigt/fe/ws/v1/` (same host) |
| **Transport** | HTTPS only |
| **Security** | OWSM; **HTTP Basic Auth** (Username + Password headers) |
| **Communication model** | **All 7 services are Synchronous** (X Síncrono in every service spec) |
| **Processing model** | **Online** (not Batch) |
| **QoS** | ~100 calls/day estimated; 750 KB max message; 99% availability 24×7 |
| **Error notifications** | `sifp@minfin.gov.ao` |
| **Services** | 7: `registarFactura`, `obterEstado`, `listarFacturas`, `consultarFactura`, `solicitarSerie`, `listarSeries`, `validarDocumento` |

---

## 2. The seven gaps

### Gap 1 — Communication model: synchronous, NOT async-pending

**Skeleton assumed:** `registarFactura` returns `PENDENTE`, then you poll `consultarEstado` until `PROCESSADO`/`REJEITADO`. I modelled AGT as async with a PENDING status.

**Real spec (§4.1.2, §4.2.2):** every service is marked **(X) Síncrono**. `registarFactura` returns **synchronously** with a `requestID` (receipt confirmation). The *validation* is deferred — you call `obterEstado` separately to learn the validation outcome. There is no `PENDENTE` status returned by `registarFactura`.

**Code change:** `AngolaComplianceAdapter.submit()` must return `SubmissionResult.pending(...)` carrying the `requestID` (validation not yet known), and `queryStatus()` must call `obterEstado` and map `resultCode` + `documentStatus` to ACCEPTED/REJECTED. The skeleton's `InMemoryAgtClient` simulated PENDENTE→PROCESSADO polling, which is wrong — it should simulate synchronous-receipt + deferred-validation.

---

### Gap 2 — Two JWS signatures are required, not one

**Skeleton assumed:** one `signAsJws(payload, nif)` call producing a single signature.

**Real spec (§4.1.6):** **two distinct signatures** are mandatory on `registarFactura`, plus a third on subsequent calls:

| Field | Signed with | Signs over | Algorithm |
|---|---|---|---|
| `jwsSoftwareSignature` | **Software producer's** private key | All fields of `softwareInfo` (productId, productVersion, softwareValidationNumber) | RS256 |
| `jwsDocumentSignature` | **Emitter's** (taxpayer's) private key | documentNo, taxRegistrationNumber, documentType, documentDate, customerTaxID, customerCountry, companyName, documentTotals | RS256 |
| `jwsSignature` (on `obterEstado`, `solicitarSerie`, `validarDocumento`) | **Emitter's** private key | taxRegistrationNumber + requestID (or the request's relevant fields) | RS256 |

**Code change:** the adapter needs **two key holders** — a `SoftwareCredential` (the TMS vendor's cert, registered with AGT at software-certification time) and an `EmitterCredential` (per-tenant, the operator's cert). The `signAsJws` helper splits into `signSoftwareInfo()` and `signDocument()`. Error codes E08/E39/E40 in §9.4 are specifically about signature mismatches — these will be the most common production failures.

---

### Gap 3 — Batch submission: up to 30 invoices per call

**Skeleton assumed:** one invoice per `submit()` call.

**Real spec (§4.1.6, `numberOfEntries`):** "Prevê-se um máximo de 30 facturas" — up to **30 invoices per `registarFactura` call**, with a `numberOfEntries` field that must match the `documents` array length (error E04 if not).

**Code change:** `InvoiceComplianceAdapter.submit(Invoice)` is fine as the per-invoice API, but `AgtClient.registarFactura` must accept a **list** of invoices. The Angola adapter should expose a `submitBatch(List<Invoice>)` method (or the facade should), and the per-invoice `submit()` becomes a batch-of-one. This matters for throughput: at 100 calls/day QoS, batching is not optional for a high-volume taxi/courier TMS — 30 invoices/call × 100 calls = 3,000 invoices/day ceiling.

---

### Gap 4 — No "QR code as base64 PNG"; the validation artefact is a `reportUrl`

**Skeleton assumed:** `consultarFactura` returns `qr_code_base64` (a PNG).

**Real spec (§4.4, `consultarFactura` response):** the response contains a **`reportUrl`** field inside the `document` object — a URL to the AGT-validated invoice report. There is **no base64 PNG QR field** in the spec. (The QR code that must be printed on the invoice per Decreto Presidencial 71/25 is *generated client-side by the emitter's software* from the validated document data, not fetched from AGT.)

**Code change:** `AgtClient.consultarFactura` returns a `reportUrl`, not a QR. `SubmissionResult.qrCodeBase64Png` is the wrong abstraction for Angola — it should be `validationReportUrl` or a more general `Map<String,String> artefacts`. For the adapter pattern, the cleanest fix is to widen `SubmissionResult` to carry an `artefacts` map (URL, QR, hash, etc.) instead of a single QR field, so Portugal (which does get a QR from Fiskaly) and Angola (which gets a URL) both fit.

---

### Gap 5 — Auth is HTTP Basic (Username/Password), not Bearer

**Skeleton assumed:** `InMemoryAgtClient` had no auth model; the README mentioned "HTTP Basic Auth" loosely.

**Real spec (§4.1.5, every service):** a mandatory **`Username` + `Password` header pair** (HTTP Basic), described as "Identificador do Usuário" + "Token de acesso ao serviço". OWSM enforces it at the OSB layer.

**Code change:** `AgtClient` methods must carry credentials. Cleanest: a CDI-scoped `AgtCredentials` bean (per-tenant lookup in production) that the REST client filters attach as an `Authorization: Basic ...` header on every call. The skeleton's `application.properties` already has `tms.compliance.ao.agt.username/password` — those are the right keys.

---

### Gap 6 — `softwareInfo` is mandatory on every request

**Skeleton assumed:** the adapter sends only the invoice.

**Real spec (§4.1.6):** **every** request (all 7 services) must include a `softwareInfo` object:
```json
"softwareInfo": {
  "softwareInfoDetail": {
    "productId": "string",            // TMS product name
    "productVersion": "string",        // TMS version
    "softwareValidationNumber": "string" // AGT certification number
  },
  "jwsSoftwareSignature": "string"     // RS256 signature over softwareInfoDetail
}
```
The `softwareValidationNumber` is the **AGT certification number** issued to the TMS vendor after software certification. Without it, every call fails with E07.

**Code change:** the adapter must hold a `SoftwareCredential` (productId, productVersion, softwareValidationNumber, software private key) and inject `softwareInfo` + `jwsSoftwareSignature` into every request. This is a vendor-level singleton, not per-tenant — the same software cert is used across all operators. In the skeleton, this belongs as a `@ApplicationScoped SoftwareCredentialProvider` bean.

---

### Gap 7 — Status vocabularies are different and richer

**Skeleton assumed:** `PENDENTE` / `PROCESSADO` / `REJEITADO` strings.

**Real spec:**

**`obterEstado` response `resultCode` (§4.2.7):**
| Code | Meaning |
|---|---|
| 0 | Processing complete, no invalid invoices |
| 1 | Processing complete, some valid + some invalid |
| 2 | Processing complete, no valid invoices |
| 7 | Premature or repetitive request — wait and retry |
| 8 | Processing still in progress |
| 9 | Processing cancelled |

**`obterEstado` response `documentStatus` (per invoice):**
| Code | Meaning |
|---|---|
| V | Factura válida (valid) |
| I | Factura inválida (invalid) |

**`validarDocumento` response `documentStatusCode` (§4.7.42):** S_A (anulled), S_C (confirmed), S_I (invalid), S_RG (registered), S_RJ (rejected), S_V (valid).

**Code change:** the Angola adapter's `queryStatus()` must map this richer enum:
- resultCode 0 → ACCEPTED (all valid)
- resultCode 1 → partial: some ACCEPTED, some REJECTED (needs per-document results)
- resultCode 2 → REJECTED (all invalid)
- resultCode 7 → PENDING (retry later)
- resultCode 8 → PENDING (still processing)
- resultCode 9 → REJECTED (cancelled)

The single `SubmissionResult.Status` enum (ACCEPTED/PENDING/REJECTED) is too coarse for resultCode=1 (mixed). Either widen the enum with a `PARTIAL` status, or return one `SubmissionResult` per invoice in the batch.

---

## 3. Additional corrections (smaller but required)

| # | Skeleton | Real spec | Fix |
|---|---|---|---|
| 8 | `Series` had `authorityValidationCode` as a free string | `solicitarSerie` returns `seriesCode`, `authorizedQuantity`, `firstDocumentNo`, `lastDocumentNo` | Widen `Series` domain object or add a `SeriesRegistrationResult` |
| 9 | `documentType` was a free string | Controlled vocabulary: FT, FR, FG, NC, ND, FA, AC, AR, TV, RC, RG, RE, RP, RA, CS, LD, GF | Add a `DocumentType` enum |
| 10 | `taxType`/`taxCode` not modelled | IVA (NOR/INT/RED/ISE/OUT), IS, IEC, NS; Cabinda region = `AO-CAB` | Add `TaxType`/`TaxCode` enums |
| 11 | `currency` was a simple string | AOA is default (currency object omitted); foreign currencies require `currencyCode` + `currencyAmount` + `exchangeRate` | Add conditional currency object |
| 12 | `documentStatus` (invoice-level, in request) not modelled | N/S/A/R/C with `documentCancelReason` (I/N) and `rejectedDocumentNo` for corrections | Add request-side `DocumentStatus` enum + correction fields |
| 13 | `customerTaxID` assumed always present | Optional; "999999999" for unidentified domestic buyers; null for some types | Make optional with sentinel handling |
| 14 | `withholdingTaxList` not modelled | IRT/II/IS/IVA/IP/IAC/IRPC/IRPS/OU | Add withholding tax model |
| 15 | `eacCode` (CAE activity code) not modelled | Optional but referenced; full CAE table in Annex 9.5 | Add as optional field |
| 16 | `paymentReceipt` not modelled | Mandatory for AR/RC/RG types; references originating documents | Add for receipt-type documents |
| 17 | No idempotency modelled | `submissionUUID` (UUID v4) is the idempotency key — "único por contribuinte emissor" | `submissionUUID` = the TMS invoice id; AGT dedupes on it |
| 18 | Error handling was a single exception | E01–E41 + HTTP 200/400/422/429; E94 (NIF mismatch), E98 (rate limit), E93 (unknown doc) | Map error codes to `ComplianceException.code` |

---

## 4. What the skeleton got RIGHT

To be fair, these aspects of the skeleton are correct and should be kept:

- **Adapter pattern structure** (Target / Adapter / Adaptee / Selector / Facade) — sound.
- **Country-agnostic `InvoiceComplianceAdapter` interface** — correct boundary.
- **CDI `@CountryQualifier` routing** — correct mechanism.
- **`ComplianceSelector` failing fast on duplicate country registration** — correct.
- **Totals reconciliation before submit** — matches error codes E22/E23/E24 exactly.
- **`CountryCode` enum (PT/AO)** — correct.
- **The Portugal side (Fiskaly)** — not affected by this spec; Fiskaly's API is genuinely synchronous with inline QR, so that adapter's shape is fine.

---

## 5. Required code changes (prioritised)

### Priority 1 — blocking correctness
1. **Rewrite `AgtClient` interface** to match the 7 real endpoints with correct method signatures (batch list, requestID-based, reportUrl not QR).
2. **Rewrite `AngolaComplianceAdapter`** to: build the full `registarFactura` payload (softwareInfo + 2 signatures + documents array), return PENDING with requestID, map `obterEstado` resultCode→Status.
3. **Add `SoftwareCredentialProvider`** bean holding productId/productVersion/softwareValidationNumber + software private key.
4. **Add `EmitterCredentialProvider`** bean (per-tenant) holding the operator's NIF + private key.
5. **Split signing** into `signSoftwareInfo()` and `signDocument()` and `signRequest()` (three JWS helpers).

### Priority 2 — production readiness
6. **Widen `SubmissionResult`** to carry an `artefacts` map (URL, QR, hash) instead of a single QR field — so PT (QR) and AO (reportUrl) both fit.
7. **Add `DocumentType`, `TaxType`, `TaxCode` enums** to the domain.
8. **Add batch `submitBatch(List<Invoice>)`** to the facade for throughput.
9. **Map E01–E41 + HTTP codes** to `ComplianceException.code`.
10. **Add HTTP Basic auth filter** to the AGT REST client.

### Priority 3 — completeness
11. **Model `withholdingTaxList`, `paymentReceipt`, `referenceInfo`** for credit notes and receipts.
12. **Add `solicitarSerie`/`listarSeries`/`validarDocumento`/`listarFacturas`/`consultarFactura`** as full adapter methods (currently only submit/query are modelled).
13. **Add `seriesContingencyIndicator`** (N/C) for contingency series.

---

## 6. Updated endpoint map (the real URLs)

| Service | REST endpoint (sandbox) | Method |
|---|---|---|
| Submit invoices | `https://sifphml.minfin.gov.ao/sigt/fe/v1/registarFactura` | POST |
| Poll validation status | `https://sifphml.minfin.gov.ao/sigt/fe/v1/obterEstado` | POST |
| List invoices | `https://sifphml.minfin.gov.ao/sigt/fe/v1/listarFacturas` | POST |
| Consult invoice | `https://sifphml.minfin.gov.ao/sigt/fe/v1/consultarFactura` | POST |
| Request series | `https://sifphml.minfin.gov.ao/sigt/fe/v1/solicitarSerie` | POST |
| List series | `https://sifphml.minfin.gov.ao/sigt/fe/v1/listarSeries` | POST |
| Validate document (buyer action) | `https://sifphml.minfin.gov.ao/sigt/fe/v1/validarDocumento` | POST |

All POST. All require `Username`/`Password` headers. All accept `Accept: application/json` for the REST variant.

---

## 7. The corrected mental model

```
                    registarFactura (sync)
   TMS Core  ───────────────────────────────►  AGT
              payload: softwareInfo + 2×JWS     │
                       + documents[≤30]          │ returns requestID (receipt)
                                                  │ (validation is DEFERRED)
              ◄─────────────────────────────────┘
              SubmissionResult{PENDING, requestID}

                    obterEstado (sync, poll)
   TMS Core  ───────────────────────────────►  AGT
              payload: softwareInfo + JWS        │
                       + requestID               │ returns resultCode + documentStatusList
                                                  │   0/1/2/7/8/9  ×  V/I
              ◄─────────────────────────────────┘
              SubmissionResult{ACCEPTED|REJECTED|PENDING, per-doc status}

                    consultarFactura (sync)
   TMS Core  ───────────────────────────────►  AGT
              payload: softwareInfo + JWS        │
                       + invoiceNo               │ returns full document + reportUrl
              ◄─────────────────────────────────┘
              artefacts: {reportUrl: "https://..."}
```

This is the model the rewritten `AngolaComplianceAdapter` must implement.
