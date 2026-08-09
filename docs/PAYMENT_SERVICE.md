# Reusable payment service

For the day-to-day dashboard flow for creating plans, synchronizing variants, and
changing prices, see [Payment plans and pricing operator guide](PAYMENT_ADMIN_GUIDE.md).

Damkemon hosts a provider-neutral application boundary with Lemon Squeezy as
the current payment provider. Rewire, Damkemon's own website, and future
products use the same `/api/payments/v1` API; no merchant credential is shipped
to a browser or mobile binary.

The module is off unless `PAYMENTS_ENABLED=true`.

## Trust and authentication

| Caller | Authentication | Payment subject |
|---|---|---|
| Damkemon website/API user | Existing `Authorization: Bearer <JWT>` | Verified JWT subject set by `JwtAuthFilter`; a body user ID is never accepted |
| Another trusted backend | `X-Payment-Api-Key` plus `X-Payment-Subject` | HMAC-pseudonymized external subject |
| Native app or public browser flow | Random installation ID in the JSON body | HMAC-pseudonymized installation subject |
| Lemon Squeezy | `X-Signature` HMAC over the exact raw request body | Checkout ID placed server-side in Lemon custom data |

Application server keys are generated from 256 random bits. Mongo stores only
their SHA-256 digests and the plaintext is returned once on creation or
rotation. Never put an application server key in Flutter, JavaScript, or a
distributed desktop binary.

Damkemon JWTs are verified by the existing JWT filter. The payment code consumes
only the verified request attributes and never forwards the token to Lemon
Squeezy. Applications with another identity provider should call from their
backend with an application server key, or add an explicit issuer-verification
adapter instead of accepting arbitrary external JWTs.

## Public API

All responses use `Cache-Control: no-store`. The examples omit the public
installation value generation: use a locally persisted cryptographically random
20+ character value, not a hardware identifier, email, phone number, or package
name.

### Create checkout

```http
POST /api/payments/v1/apps/rewire/checkouts
Idempotency-Key: checkout_<random 256-bit value>
Content-Type: application/json

{
  "productCode": "lifetime",
  "installationId": "rw_<random 256-bit value>"
}
```

The response contains an HTTPS `checkoutUrl`, internal `checkoutId`, status,
expiry, and test-mode flag. Reusing the same idempotency key for the same
subject returns the original checkout instead of creating another one.

Lifetime and recurring products coexist as separate product codes mapped to
separate Lemon variants. For example, an application can retain `lifetime`
while also mapping tier-and-cadence codes such as `plus_weekly`, `pro_monthly`,
and `premium_yearly`. Each application defines its own tiers, prices, and
available cadences in Lemon. Checkout creation is the same for one-time and
recurring variants; Lemon starts a subscription for the latter.

### Activate license

```http
POST /api/payments/v1/apps/rewire/licenses/activate
Content-Type: application/json

{
  "productCode": "lifetime",
  "licenseKey": "customer-provided-license-key",
  "installationId": "rw_<same persisted value>",
  "instanceName": "Rewire Android"
}
```

Activation first validates the license with Lemon, verifies the configured
store/product/variant, then requires a synchronized paid order. Account- and
backend-authenticated products must match the same opaque subject. For an app
explicitly configured with `publicLicense=true`, the full customer license key
is a bearer proof and may activate another installation up to Lemon's provider
limit; this is necessary for anonymous multi-device products such as Rewire and
means users must protect the key. A `409 payment_sync_pending` response means
the signed order webhook has not arrived yet and the client should retry with
backoff.

### Validate or deactivate

```http
POST /api/payments/v1/apps/rewire/licenses/validate
POST /api/payments/v1/apps/rewire/licenses/deactivate
Content-Type: application/json

{
  "licenseKey": "customer-provided-license-key",
  "installationId": "rw_<same persisted value>",
  "instanceId": "provider-instance-id-from-activation"
}
```

The server verifies the installation, stored HMAC fingerprint, provider
product mapping, order state, and provider response. Provider outages return
`502`; clients may preserve a recently validated offline entitlement but must
not convert an explicit 4xx denial into access.

### Validate a direct subscription entitlement

Account- or installation-bound subscriptions that do not use Lemon license
keys are granted directly from signed subscription webhooks:

```http
POST /api/payments/v1/apps/damkemon/entitlements/validate
Content-Type: application/json

{
  "productCode": "pro_monthly",
  "installationId": "dk_<same persisted value for a public flow>"
}
```

The caller authentication rules are the same as checkout creation. The
response contains `valid`, entitlement status/code, renewal and expiry times,
validation time, and test-mode state. Validation fails closed after `endsAt`
even if a delayed webhook has not yet marked the entitlement revoked.

### Webhook

Configure Lemon to POST to:

```text
https://damkemon.com/api/payments/v1/webhooks/lemon-squeezy
```

Subscribe to `order_created`, `order_refunded`, `license_key_created`,
`license_key_updated`, `subscription_created`, and `subscription_updated`.
The two subscription events are Lemon's recommended minimum lifecycle signals;
`subscription_updated` covers cancellation, resume, pause, failed-payment state,
and expiry changes. The handler verifies the raw-body HMAC before parsing,
cross-checks `X-Event-Name` against signed JSON metadata, rejects provider ID or
test/live-mode mismatches, and is idempotent by payload SHA-256. It returns a
non-2xx response on processing failures so Lemon retries.

Subscription access remains valid during trial, active, paused, past-due,
unpaid, and cancelled grace-period states. It is revoked when Lemon reports
`expired`. A cancelled subscription keeps access through its provider `endsAt`
date and can be resumed before then.

## Separate Mongo collections

| Collection | Purpose | Important indexes |
|---|---|---|
| `payment_applications` | Caller policy and server-key digest | unique sparse API-key digest |
| `payment_products` | App product allowlist and provider mapping | unique app+code; unique provider+store+variant+mode |
| `payment_checkouts` | Opaque purchaser subject and checkout lifecycle | unique app+subject+idempotency key; unique provider checkout ID |
| `payment_orders` | Signed authoritative order/refund state, including partial-refund minor units | unique provider+order ID; subject timeline |
| `payment_subscriptions` | Signed recurring lifecycle state and renewal/end dates | unique provider+subscription ID; unique checkout ID |
| `payment_licenses` | Provider license state and HMAC key fingerprint | unique provider+license ID; app+fingerprint lookup |
| `payment_entitlements` | Installation/account capability state | unique app+subject+provider instance |
| `payment_webhook_events` | Idempotency and processing audit | payload digest primary key; received-time index |
| `payment_admin_actions` | Audited provider mutations without secrets or customer identity | app+mode+status timeline |

Money is stored as integer minor units plus ISO currency. Raw webhook bodies,
full license keys, card data, checkout payment details, merchant API keys, and
plaintext application server keys are not stored.

## Register another app or website

Admin endpoints are below `/api/admin/payments/**`, so the existing admin JWT or
constant-time `X-Admin-Key` gate protects them.

The Admin payment area exposes local overview/revenue totals plus paginated
applications, products, checkouts, orders, licenses, entitlements, webhook
events, and provider-action audit records. Test/live mode is explicit on every
relevant view. It also provides curated Lemon controls for connection status,
catalog and webhook inspection, idempotent webhook creation/update, managed
order inspection/refund, read-only synchronized subscription visibility, and
managed license inspection/update. Subscription plans, prices, and subscriber
management stay in Lemon Squeezy. It is not a
generic Lemon proxy: full license keys, provider secrets, card data, receipt
URLs, and customer name/email are never returned.

The curated operations follow Lemon's official APIs for
[orders and refunds](https://docs.lemonsqueezy.com/api/orders/issue-refund),
[license updates](https://docs.lemonsqueezy.com/api/license-keys/update-license-key),
and [webhook management](https://docs.lemonsqueezy.com/api/webhooks/list-all-webhooks).

Provider mutations require an exact resource-ID or configured-URL confirmation
and create a `payment_admin_actions` record before calling Lemon. Refunds are
bounded by the locally synchronized order total. License updates can change the
activation limit, expiry, or disabled state; disabling a license revokes local
entitlements immediately while the signed webhook remains authoritative.
`GET /api/admin/payments/subscriptions` exposes synchronized lifecycle records
without duplicating Lemon's subscriber-management dashboard.

1. `POST /api/admin/payments/applications` with a stable lowercase `appId`,
   display name, and caller policy. Store the returned server key in that
   application's backend secret manager; it is not recoverable later.
2. `POST /api/admin/payments/applications/{appId}/products` with a stable public
   product code, entitlement code, and the allowlisted Lemon store/product/
   variant IDs. The backend reads the selected variant from Lemon and stores its
   one-time/subscription type, interval, interval count, and license requirement;
   the admin request cannot spoof those fields. Rewire does not need this manual
   step: its configured Lemon product is synchronized automatically.
3. For Damkemon account flows, enable `acceptDamkemonJwt`. For another backend,
   keep public access off and use its server key. Enable public checkout/license
   only for intentionally anonymous installation-bound products.
4. Test checkout, signed webhook, activation, validation, deactivation, refund,
   duplicate webhook, wrong product, wrong subject, and provider-outage paths
   before changing a product to live mode.
5. Rotate a backend key with
   `POST /api/admin/payments/applications/{appId}/rotate-key`; deploy the new key
   to its consumer immediately.
6. Disable or change an application's caller policy with
   `PATCH /api/admin/payments/applications/{appId}`. Disabling an application
   stops new payment calls without deleting audit data.

Lemon supports daily, weekly, monthly, and yearly subscription intervals, but
its public Products and Variants APIs currently expose only retrieve/list
operations (see the [API reference](https://docs.lemonsqueezy.com/api)). Create
and publish the product variants in the Lemon dashboard. Rewire refreshes that
product every 15 minutes and derives tier-and-cadence mappings from each
published variant's tier name and one-period cadence. For example,
`Plus Weekly` becomes `plus_weekly` and `Premium Yearly` becomes
`premium_yearly`. It creates missing mappings and refreshes
the same variant's metadata, but never silently repoints an established product
code to another variant. Damkemon creates hosted checkouts and
synchronizes subscription access through signed webhooks; it cannot create the
underlying Lemon product or variant through a supported API. The existing
lifetime mapping remains independent and active.

## Configuration and rollout

Required when payments are enabled:

```text
PAYMENTS_ENABLED=true
PAYMENTS_FINGERPRINT_SECRET=<32+ random characters>
LEMON_SQUEEZY_TEST_API_KEY=<test-mode API key, server only>
LEMON_SQUEEZY_LIVE_API_KEY=<live-mode API key, server only; optional until live cutover>
LEMON_SQUEEZY_WEBHOOK_SECRET=<32-40 random characters>
LEMON_SQUEEZY_WEBHOOK_URL=https://damkemon.com/api/payments/v1/webhooks/lemon-squeezy
```

Rewire live catalog configuration:

```text
PAYMENTS_REWIRE_ENABLED=true
PAYMENTS_REWIRE_STORE_ID=445309
PAYMENTS_REWIRE_PRODUCT_ID=1276394
PAYMENTS_REWIRE_VARIANT_ID=1995479
PAYMENTS_REWIRE_TEST_MODE=false
PAYMENTS_REWIRE_SYNC_MS=900000
```

The legacy `LEMON_SQUEEZY_API_KEY` is accepted only as a test-key fallback for
a safe migration; use the mode-specific names for new deployments. Production
startup fails closed if the module is enabled without its secrets, the API key
for Rewire's configured mode, an HTTPS webhook URL, or positive Rewire provider
IDs. Add secrets to the existing
`damkemon-prod-app.service` environment file before enabling the module; never
commit them. The current deployment remains the existing `deployment-prod`
GitHub Actions pipeline and `damkemon-prod-app.service` on the Damkemon server.
CI runs all tests and builds the boot JAR, serializes production deploys, then
requires both the systemd unit and the public actuator health check to recover.

The `deployment-prod` workflow writes the payment configuration to the service
environment without printing values. Configure these GitHub **secrets**:
`PAYMENTS_FINGERPRINT_SECRET`, `LEMON_SQUEEZY_TEST_API_KEY`,
`LEMON_SQUEEZY_LIVE_API_KEY` (required before live cutover), and
`LEMON_SQUEEZY_WEBHOOK_SECRET`. Configure these GitHub **variables**:
`PAYMENTS_ENABLED`, `LEMON_SQUEEZY_WEBHOOK_URL`,
`PAYMENTS_REWIRE_ENABLED`, `PAYMENTS_REWIRE_STORE_ID`,
`PAYMENTS_REWIRE_PRODUCT_ID`, `PAYMENTS_REWIRE_VARIANT_ID`,
`PAYMENTS_REWIRE_TEST_MODE`, and `PAYMENTS_REWIRE_REDIRECT_URL`. Set
`PAYMENTS_REWIRE_TEST_MODE=false` only after the live key and a live Lemon
catalog mapping are in place. An unset live-key secret leaves the existing
server setting untouched so sandbox deployments remain unchanged.

## Security operations

- Rate-limit public checkout/license calls independently from catalog traffic.
- Keep Lemon API and webhook secrets distinct and rotate them after suspected
  exposure.
- Preserve the fingerprint secret; rotating it invalidates stored external
  subjects and license fingerprints unless a migration is performed.
- Use different Lemon API keys and product/variant configuration for test and
  live modes. Checkout creation selects the key from the mapped product mode,
  and signed events must match that mode.
- Treat refunds and inactive license updates as immediate entitlement revocation.
- Do not grant access from checkout redirects, client success messages, email
  claims, or unsigned provider data.
- Review `payment_webhook_events`, `payment_admin_actions`, and unusual
  activation/rate-limit patterns. Never log request bodies or payment secrets.
