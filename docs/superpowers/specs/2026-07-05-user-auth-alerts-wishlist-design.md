# User auth, price alerts, wishlist — design

Date: 2026-07-05 · Status: approved (owner delegated decisions: "take ownership")

## Problem

Wishlist + price alerts are fully built server-side (AccountController,
PriceAlertScheduler) and mostly built client-side (heart on ProductDetail,
Account tabs) — but dead in practice, because:

1. **No signup exists.** The only login is the fixed-credential owner account
   (OwnerBootstrap). Regular users can never reach /api/account/*.
2. **Alert emails go nowhere.** EmailNotifier writes to an
   `outbound_email_queue` collection nothing drains. ResendService (the real
   mailer, used by the newsletter) is never called for alerts.
3. **alertsEnabled defaults to null** on wishlist add — the scheduler skips
   `!TRUE.equals(alertsEnabled)`, so even a signed-in owner wishlisting a
   product gets no alert unless they PATCH the flag.

## Decisions

- **Auth method: email + password** on the existing JWT/BCrypt rails. No
  OAuth, no magic links (User javadoc updated). Owner username login unchanged.
- **Email verification via Resend** (token link, 48h). Unverified users can
  use everything EXCEPT email alerts (in-app notifications still work);
  legacy rows (owner, `emailVerified=null`) are treated as verified.
- **Password reset via Resend** (token link, 1h). Opaque responses — no
  account enumeration.
- **User data**: signup stays 3 fields (name, email, password) + optional
  phone + newsletter opt-in (default on). The rich data lives in an optional
  Account → Profile tab: phone, district (64-district list), gender, birth
  year, interests (catalog categories). Rationale: every required field costs
  signups; profile completion can be nudged later.
- **Alert emails: direct Resend send** from EmailNotifier, HTML template.
  ponytail: no queue/drain until alert volume outgrows Resend rate limits.
- **PriceAlertScheduler runs on the web role only** (was un-gated — both JVMs
  scanned hourly; the 24h debounce hid most dupes).

## Backend changes

- `User`: + emailVerified, verifyToken/expiry, resetToken/expiry, phone,
  district, gender, birthYear, interests[], newsletterOptIn, signupSource.
- `UserRepository`: + findAllByEmail (dup-tolerant, same reason as username),
  findByVerifyToken, findByResetToken.
- `AuthController`:
  - POST /api/auth/signup {name,email,password,phone?,newsletterOptIn?} →
    409 if email taken; creates role=user, unverified, sends verification
    mail async, auto-subscribes newsletter when opted; returns {token,user}.
  - POST /api/auth/verify {token} · POST /api/auth/resend-verification (authed)
  - POST /api/auth/forgot {email} (always ok) · POST /api/auth/reset {token,password}
  - PATCH /api/auth/profile (authed, partial) — also syncs NewsletterSubscriber.
  - login accepts `email` or `username`; publicProfile gains the new fields.
- `EmailNotifier` → ResendService (HTML price-drop mail, product link).
- `PriceAlertScheduler`: appRole.isWeb() gate; email only when
  `!FALSE.equals(user.emailVerified)`; else in-app.
- `AccountController.addToWishlist`: alertsEnabled=true, notifyChannel=email.
- `SecurityConfig`: auth rate-limit rule covers signup/verify/forgot/reset too.

## Frontend changes

- New pages: /sign-up, /verify, /forgot-password, /reset-password.
- SignIn: "Email or username", forgot-password + create-account links,
  de-operatorized copy.
- Account: + Profile tab (edit all profile fields), unverified-email banner
  with resend button.
- Navbar: "Sign up" CTA next to "Sign in" (desktop + mobile).
- auth.js: signup/verify/resend/forgot/reset/updateProfile calls.

## Testing

- Unit: signup validation + login-by-email (AuthController slice, mocked repo,
  same style as AuthControllerTest), token verify/reset happy + expiry paths.
- Existing suite must stay green. FE: lint + build.

## Out of scope

Google OAuth (phase 2), SMS/phone OTP, account deletion UI, profile-completion
nudges, draining/removing the legacy outbound_email_queue collection.
