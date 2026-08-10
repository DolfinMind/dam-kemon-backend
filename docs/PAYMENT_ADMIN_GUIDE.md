# Payment plans and pricing operator guide

This guide explains how each application can sell its own Plus, Pro, Premium,
or other tiers with weekly, monthly, yearly, and optional lifetime access.
Lemon Squeezy owns the commercial product and price. Damkemon owns which
application may sell each Lemon variant and which entitlement the customer
receives.

## Mental model

```text
Application (for example Rewire or Echo Memory)
  -> tier (for example Plus, Pro, or Premium)
    -> billing cadence (weekly, monthly, or yearly)
      -> Damkemon product code (for example pro_monthly)
        -> one Lemon Squeezy variant
          -> tier entitlement granted to the customer (for example rewire_pro)
```

Use one Lemon variant per sellable plan. Keep `lifetime` as a single-payment
variant; it remains available alongside any recurring variants.

| Example variant | Lemon price type | Repeat interval | Damkemon code |
|---|---|---|---|
| Plus Weekly | Subscription | Every 1 week | `plus_weekly` |
| Pro Monthly | Subscription | Every 1 month | `pro_monthly` |
| Premium Yearly | Subscription | Every 1 year | `premium_yearly` |
| Lifetime | Single payment | None | `lifetime` |

The product code identifies both what tier the customer bought and how it is
billed. The entitlement identifies the capability to unlock. Rewire examples
are `rewire_plus`, `rewire_pro`, and `rewire_premium`; another app uses its own
entitlement namespace and prices. Consumables such as Echo Memory diamonds use
one stable code and one exact-value entitlement per pack rather than a billing
cadence.

## Create a plan in Lemon Squeezy

Lemon's supported public API can list products and variants but cannot create
them. Create or edit the underlying plans in the Lemon dashboard:

1. Open **Store > Products** and select the application product.
2. Give the parent product an app-level name such as **Rewire Plans** if it
   contains several tiers and cadences.
3. Select **Add variant**.
4. Name each recurring variant **Tier Cadence**, for example **Plus Weekly**,
   **Pro Monthly**, or **Premium Yearly**. The tier name must remain stable.
5. Choose **Subscription**, set that app-specific price, and set
   **Repeat payments** to every 1 week, month, or year.
6. For Lifetime, choose **Single payment** and set its one-time BDT price.
7. Keep license generation and the activation limit aligned with the app's
   policy. Rewire and Echo Pro use generated license keys with a three-device
   activation limit; Echo Memory diamond packs do not generate licenses.
8. Save the variant and make sure the product is published.

Do this in Lemon's test mode first. Live and test catalogues are separate, and
the backend synchronizes the mode selected by the application's
`PAYMENTS_*_TEST_MODE` variable.

## How Damkemon connects to the plans

No plan or price needs to be recreated in Damkemon Admin. When payments and the
Rewire integration are enabled, the backend reads the configured Lemon product
at startup and every 15 minutes:

- the configured single-payment variant remains `lifetime`;
- the variant name supplies the tier and Lemon supplies the cadence;
- **Plus Weekly** becomes `plus_weekly` and grants `rewire_plus`;
- **Pro Monthly** becomes `pro_monthly` and grants `rewire_pro`;
- **Premium Yearly** becomes `premium_yearly` and grants `rewire_premium`;
- an app can publish any subset and set different prices for every variant;
- an existing product code is never silently repointed to a different variant,
  so current subscribers remain tied to the plan they purchased.

Keep only one published variant for each tier-and-cadence pair. If Lemon
contains two published **Pro Monthly** variants, Damkemon treats that code as
ambiguous and keeps the current mapping rather than choosing the wrong plan.

Echo Memory is intentionally explicit rather than name-derived. Configure the
monthly, lifetime, and three diamond variant IDs directly. Damkemon then checks
the provider's price type/cadence and maps the five stable codes without
guessing from names. Keep diamond values fixed at 40, 100, and 250 in both the
app and Supabase fulfillment RPC.

Open **Admin > Payments**, select Rewire and the configured mode, then verify
that **Product mappings** shows the expected cadence and variant ID. This area
is operational visibility; plan creation and pricing remain in Lemon.

The application creates a checkout by sending the synchronized product code:

```http
POST /api/payments/v1/apps/rewire/checkouts
Idempotency-Key: checkout_<random-256-bit-value>
Content-Type: application/json

{
  "productCode": "pro_monthly",
  "installationId": "rw_<persisted-random-installation-id>"
}
```

Change `pro_monthly` to another synchronized code such as `plus_weekly`,
`premium_yearly`, or `lifetime`. The returned `checkoutUrl` is the Lemon-hosted
checkout.

## Configure and verify webhooks

The production webhook URL is:

```text
https://damkemon.com/api/payments/v1/webhooks/lemon-squeezy
```

The webhook must subscribe to:

- `order_created`
- `order_refunded`
- `license_key_created`
- `license_key_updated`
- `subscription_created`
- `subscription_updated`

Use the Admin payment page's webhook control to inspect or ensure the webhook.
The backend verifies Lemon's signature before accepting any event. A successful
checkout alone is not an entitlement grant: the signed webhook is the
authoritative payment and subscription record.

## Change a price later

For a normal price change:

1. Open the existing variant in Lemon Squeezy.
2. Change its price and save it.
3. Keep the same variant ID. The Damkemon mapping continues to work and no
   backend deployment is needed.
4. Refresh **Admin > Payments > Provider catalogue** after the next sync and
   verify the variant is still published.
5. Run a test checkout before relying on the new live price.

Lemon applies the edited price to new subscriptions. Existing subscribers keep
their original price. To move an existing subscriber, open that subscription in
Lemon, select **Modify subscription**, choose the target variant, and review the
proration behavior before saving.

If the commercial offer changes materially, create a new variant instead of
repurposing an old one. Give that offer a new Damkemon product code through the
provider-verified backend registration endpoint, then update the app's plan
selector. Automatic sync deliberately refuses to replace an established code's
variant ID. Existing subscriptions can continue on the old disabled variant.

Prefer disabling an obsolete variant over deleting it. Disabled variants are
hidden from new checkouts while existing customers retain access. Deletion can
remove customer access to attached files.

## Customer subscription lifecycle

```text
Checkout created
  -> Lemon payment succeeds
  -> signed subscription webhook arrives
  -> Damkemon grants the entitlement
  -> renewal updates the subscription dates
  -> cancellation keeps access through endsAt
  -> resume before endsAt restores renewal
  -> expired revokes the entitlement
```

Admins can inspect synchronized subscriptions in the **Subscriptions** resource
in Admin Payments. Cancel, resume, change plan, and proration operations stay in
Lemon Squeezy's subscription dashboard or customer portal.

The client should validate access through Damkemon, not by trusting a checkout
redirect. For a plan without generated Lemon license keys, use the direct
entitlement endpoint:

```http
POST /api/payments/v1/apps/damkemon/entitlements/validate
Content-Type: application/json

{
  "productCode": "pro_monthly",
  "installationId": "dk_<same-persisted-installation-id>"
}
```

For a plan with generated license keys, including Rewire's current three-device
policy, use the license activation and validation endpoints documented in
[`PAYMENT_SERVICE.md`](PAYMENT_SERVICE.md). Subscription webhooks still bind
those license entitlements to the paid period and revoke them on expiry.

For a consumable, call the fulfillment endpoint only from a trusted backend,
verify the paid ownership-bound result, and apply it through an idempotent
credit ledger. Never credit from a checkout success redirect or a client-sent
product/amount pair.

A cancelled subscription remains valid until its paid `endsAt`. An expired
subscription is rejected even if a webhook is delayed.

## Safe rollout checklist

1. Create all plans in Lemon test mode.
2. Configure the backend for test mode, allow the catalogue sync to run, and
   verify the Product mappings in Damkemon Admin.
3. Ensure the test webhook contains all six required events.
4. Complete a checkout and confirm the subscription, order, and entitlement
   appear in Admin Payments.
5. Test entitlement validation, cancellation, grace-period access, resume, and
   expiry behavior.
6. Repeat the setup with live variants and configure the backend for live mode.
7. Verify one low-risk live checkout before exposing plans to all customers.
8. Keep the lifetime variant mapped and visible when recurring plans launch.

## Troubleshooting

| Symptom | Check |
|---|---|
| Variant is not in the Admin catalogue | Correct Sandbox/Live mode, API key, store, and published state |
| Checkout says product is unavailable | Application is enabled, the variant is published, the mode is correct, and the last catalogue sync succeeded |
| Payment succeeded but access is pending | Webhook URL, signature secret, subscribed events, and webhook event log |
| Subscription needs cancellation or a plan change | Manage it in Lemon Squeezy's Subscriptions page; Damkemon receives the signed update |
| Price changed for new customers but not old ones | Expected Lemon behavior; explicitly modify existing subscriptions if commercially intended |
| Lifetime purchase stopped appearing | Confirm the independent `lifetime` mapping still points to its single-payment variant |

Related implementation details and configuration variables are in
[`PAYMENT_SERVICE.md`](PAYMENT_SERVICE.md).
