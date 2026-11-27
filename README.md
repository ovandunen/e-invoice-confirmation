# TMS Compliance Adapter — Quarkus Starter

A working skeleton of the **adapter pattern** for routing e-invoice compliance
calls between **Portugal (AT / Fiskaly)** and **Angola (AGT)** from a single
Quarkus TMS Core.

## Why this exists

The TMS Core must issue fiscally valid invoices in two countries whose rules are
~70% similar but differ in the parts that hurt:

| Concern | Portugal (AT via Fiskaly) | Angola (AGT) |
|---|---|---|
| Auth | Bearer token (24h, refreshable) | HTTP Basic (per-taxpayer) |
| Signing | RSA hash chain (server-side at Fiskaly) | JWS RS256 detached (client-side) |
| Submission model | Synchronous — ACCEPTED immediately | Asynchronous — PENDENTE, then poll |
| QR code | Returned inline with the signed invoice | Returned only after PROCESSADO |
| Reference | ATCUD | AGT factura id |

Baking any of this into the Core would force two builds, two release trains,
and a Core that knows about countries it shouldn't. The adapter pattern keeps
the Core clean: **one interface, two implementations, runtime dispatch by
country.**

## Module map

```
com.tms.compliance
├── domain                     # Country-agnostic DTOs
│   ├── CountryCode            # The discriminator (PT | AO)
│   ├── Invoice                # What the Core produces
│   ├── Series                 # Document series
│   └── SubmissionResult       # What the Core gets back
│
├── spi                        # The Target (port) + shared qualifier
│   ├── InvoiceComplianceAdapter   # THE interface the Core depends on
│   ├── CountryQualifier           # CDI qualifier tagging adapters by country
│   └── ComplianceException
│
├── adapter
│   ├── portugal
│   │   ├── PortugalComplianceAdapter   # Adapter #1 (wraps FiskalyClient)
│   │   └── client
│   │       ├── FiskalyClient          # Adaptee interface
│   │       └── InMemoryFiskalyClient  # Fake for dev/tests
│   │
│   └── angola
│       ├── AngolaComplianceAdapter     # Adapter #2 (wraps AgtClient)
│       └── client
│           ├── AgtClient               # Adaptee interface
│           └── InMemoryAgtClient       # Fake for dev/tests
│
├── selector
│   └── ComplianceSelector         # Runtime dispatch: country -> adapter
│
├── facade
│   └── ComplianceFacade           # The ONLY class the TMS Core injects
│
└── bootstrap
    └── DemoResource               # GET /demo/issue?country=PT|AO
```

## The pattern, in plain English

```
            +------------------------+
            |   Quarkus TMS Core     |
            |  (orders, dispatch…)   |
            +-----------+------------+
                        |
                        | injects
                        v
            +------------------------+
            |   ComplianceFacade     |   ← single entry point
            +-----------+------------+
                        |
                        | dispatches by CountryCode
                        v
            +------------------------+
            |  ComplianceSelector    |   ← registry of adapters
            +-----+-------------+----+
                  |             |
        +---------+             +-----------+
        |                                   |
        v                                   v
+-------------------+              +-------------------+
| PortugalAdapter   |              | AngolaAdapter     |   ← Adapters
|  (Adapter #1)     |              |  (Adapter #2)     |     implement
+---------+---------+              +---------+---------+     InvoiceComplianceAdapter
          |                                  |
          | delegates                         | delegates
          v                                  v
+-------------------+              +-------------------+
| FiskalyClient     |              | AgtClient         |   ← Adaptees
| (Fiskaly REST)    |              | (AGT REST)        |     (country-specific)
+---------+---------+              +---------+---------+
          |                                  |
          v                                  v
+-------------------+              +-------------------+
| AT / Fiskaly      |              | AGT Angola        |   ← Real backends
+-------------------+              +-------------------+
```

| GoF role | Class in this project |
|---|---|
| **Target** | `InvoiceComplianceAdapter` (the interface) |
| **Adapter** | `PortugalComplianceAdapter`, `AngolaComplianceAdapter` |
| **Adaptee** | `FiskalyClient`, `AgtClient` (and their in-memory fakes) |
| **Client** | `ComplianceFacade` (which the TMS Core calls) |
| **Selector** | `ComplianceSelector` (runtime dispatch by country) |

## Key design decisions

### 1. The Core depends on one interface, never on a country class
`InvoiceComplianceAdapter` is the only SPI the TMS Core imports. Adding
Mozambique (MZ) later = one new class + one new `@CountryQualifier(CountryCode.MZ)`.
The Core and the existing adapters don't change.

### 2. Country is an enum, not a String
`CountryCode` is a closed enum. A typo at the call site is a compile error, not
a runtime `NO_ADAPTER_FOR_COUNTRY`. Never widen to String — the moment you do,
every consumer has to handle every possible value defensively.

### 3. The interface is async-friendly by default
`submit` returns `SubmissionResult`, whose `Status` can be `ACCEPTED`,
`PENDING`, or `REJECTED`. Fiskaly (synchronous) always returns `ACCEPTED`;
AGT (asynchronous) returns `PENDING` and the caller polls via `queryStatus`.
Same interface, two real-world behaviors. This is the single most important
abstraction in the file — without it, AGT's polling would leak into the Core.

### 4. QR retrieval is split out
`fetchQrCode(authorityReference)` is a separate method because AGT returns the
QR only after PROCESSADO, while Fiskaly returns it inline. The Core calls
`fetchQrCode` only when `submit` or `queryStatus` returned `null` for the QR.
One method, two execution paths, same return type (`Optional<String>`).

### 5. Adapters absorb all country-specific complexity
- **Signing**: JWS RS256 (Angola) lives inside `AngolaComplianceAdapter`.
  Hash-chain signing (Portugal, if going direct to AT) would live inside
  `PortugalComplianceAdapter`. The Core never sees a private key.
- **Auth**: Fiskaly's Bearer-token refresh is internal to `FiskalyClient`.
  AGT's HTTP Basic is internal to `AgtClient`. The interface has no auth concept.
- **Response mapping**: AGT's `PROCESSADO`/`PENDENTE`/`REJEITADO` strings become
  `SubmissionResult.Status` enum values inside the Angola adapter. Fiskaly's
  `state: "ACCEPTED"` becomes the same enum inside the Portugal adapter.

### 6. Routing is a CDI selector, not direct `@Qualifier` injection
`@CountryQualifier(CountryCode.PT)` tags the bean, but the actual dispatch goes
through `ComplianceSelector.forCountry(country)`. This lets the country come
from the per-request tenant context (JWT claim, NIF lookup) instead of being
fixed at the injection point — essential for a multi-tenant SaaS.

### 7. The selector fails fast at startup
If two adapters register for the same country (copy-paste bug), the selector
throws at construction time and Quarkus fails to start. Better than a runtime
`ConcurrentModificationException` in production.

### 8. In-memory fakes match each backend's behavior shape
`InMemoryFiskalyClient` returns ACCEPTED synchronously.
`InMemoryAgtClient` returns PENDENTE on first poll and PROCESSADO on second.
Tests of the adapters therefore exercise the real async lifecycle, not a
sanitized synchronous shortcut.

## How to extend

### Add a third country (e.g. Mozambique)

1. Add `MZ` to `CountryCode`.
2. Create `adapter/mozambique/MozambiqueComplianceAdapter` implementing
   `InvoiceComplianceAdapter`, annotated `@CountryQualifier(CountryCode.MZ)`.
3. Create `adapter/mozambique/client/MozambiqueClient` (the Adaptee) and an
   in-memory fake.
4. Done. The Core, the facade, the selector, and the other adapters don't change.

### Add cross-cutting concerns (retry, idempotency, metrics)

Don't bake them into the adapters. Wrap `ComplianceFacade` with a CDI
decorator:

```java
@Decorator
@Priority(Interceptor.Priority.APPLICATION)
public abstract class RetryingComplianceDecorator implements ComplianceFacade {
    @Delegate @Any abstract ComplianceFacade delegate();
    // override submit() to retry on transient errors with idempotency key
}
```

The adapters stay single-responsibility; the decorator handles concerns that
apply to all countries identically.

### Go from in-memory fakes to real HTTP clients

1. Annotate `FiskalyClient` and `AgtClient` with `@RegisterRestClient` and
   `@ApplicationScoped`.
2. Move `InMemoryFiskalyClient` and `InMemoryAgtClient` to
   `src/test/java` (or guard with `@IfBuildProfile("dev")`).
3. Configure `quarkus.rest-client."FiskalyClient".url=...` in
   `application.properties`.
4. Uncomment the `rest-client` dependency in `pom.xml`.

### Add per-tenant credentials

The skeleton uses flat config properties (`tms.compliance.pt.fiskaly.api-key`)
because it is single-tenant. For multi-tenant:

1. Replace `@ConfigProperty` injection in the adaptee clients with a
   `TenantCredentialsProvider` that looks up credentials by NIF.
2. The adapter passes the operator's NIF (from the request context) to the
   client on every call.
3. The client constructs the right Basic Auth header or Bearer token per call.

## Running the demo

```bash
cd /home/z/my-project/scripts/tms-compliance-adapter
mvn quarkus:dev
```

Then:

```bash
# Portugal — synchronous, returns ACCEPTED immediately with QR
curl 'http://localhost:8080/demo/issue?country=PT'

# Angola — asynchronous, returns PENDING; poll queryStatus to reach ACCEPTED
curl 'http://localhost:8080/demo/issue?country=AO'
```

## What is intentionally NOT here

- **No real HTTP clients.** `FiskalyClient` and `AgtClient` are skeletons so
  the pattern compiles and runs without external services. Real
  `@RegisterRestClient` interfaces are a drop-in replacement.
- **No JWS signing.** `AngolaComplianceAdapter.signAsJws` is a stub. In
  production you'd use `nimbus-jose-jwt` (already listed, commented out, in
  the pom).
- **No SAF-T exporters.** SAF-T (PT) and SAF-T (AO) are separate concerns
  (monthly file submission, not per-invoice). They belong in a sibling
  `SaftExporter` SPI with the same country-adapter shape — out of scope here.
- **No persistence.** The skeleton uses in-memory maps. Production adds a
  `SubmissionRecord` Panache entity keyed by `(tenantNif, invoiceId)` to track
  the `authorityReference` and last-known status across polls and webhooks.

## Files

All sources are under
`/home/z/my-project/scripts/tms-compliance-adapter/src/main/java/com/tms/compliance/`.
