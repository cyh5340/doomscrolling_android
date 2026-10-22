# TODO — needs you (accounts, domains, devices)

Everything else is built and runs against the local Supabase stack (`cd backend && npx supabase start`).
These steps need your accounts or real devices. All are free with no credit card.

## 1. Supabase project (backend)

```sh
cd backend
npx supabase login                                  # opens a browser
npx supabase projects create doomscroll --region us-west-1   # or create it on supabase.com
npx supabase link --project-ref <ref>
npx supabase config push                            # turns on anonymous sign-ins
npx supabase db push                                # tables, rules, triggers
npx supabase functions deploy send-alert --no-verify-jwt
npx supabase secrets set ALERT_SECRET=<random string>
```

Then point the database at the deployed function (SQL editor, or `psql` with the project's connection string):

```sql
insert into private.config (key, value) values
  ('alerts_url', 'https://<ref>.supabase.co/functions/v1/send-alert'),
  ('alerts_secret', '<same random string>');
```

Point the app at it in `~/.gradle/gradle.properties` (not committed):

```properties
supabaseUrl=https://<ref>.supabase.co
supabaseKey=<publishable key from: npx supabase projects api-keys --project-ref <ref>>
```

Note: free projects pause after a week without activity. Unpause from the dashboard.

## 2. Firebase project (push notifications only)

```sh
npx firebase-tools login
npx firebase-tools projects:create doomscroll-<suffix> --display-name "Doomscroll"
npx firebase-tools apps:create ANDROID "Doomscroll" --package-name com.doomscroll.app --project doomscroll-<suffix>
npx firebase-tools apps:sdkconfig ANDROID <appId> --project doomscroll-<suffix> -o apps/android/app/google-services.json
```

- If project creation fails, open console.firebase.google.com once and accept the terms.
- Don't link a billing account; that switches the project to the paid plan.
- Server key for sending: Firebase console → Project settings → Service accounts → Generate new private key. Then:
  `npx supabase secrets set FCM_SERVICE_ACCOUNT="$(cat <key>.json)"` and delete the downloaded file.
- `google-services.json` is git-ignored; the build picks it up automatically when present.

## 3. Invite-link domain

- Pick a domain (any free static host works, e.g. GitHub Pages or Cloudflare Pages).
- Set `inviteHost=<domain>` in `~/.gradle/gradle.properties`.
- Publish `backend/web/` there. Fill in the SHA-256 fingerprint in `backend/web/.well-known/assetlinks.json`
  (`keytool -list -v -keystore <release keystore>`; add the Play App Signing fingerprint too once on Play).
- Until then, invites work by pasting the link or code into "Create or join a group".

## 4. Google sign-in (later)

Accounts are anonymous for now, so uninstalling the app loses the account.
To add Google sign-in: create an OAuth client in Google Cloud → APIs & Services → Credentials (Android + Web
client), enable the Google provider in Supabase Auth, and link it to the anonymous user with Credential Manager
on Android (`linkIdentityWithIdToken`), so existing data is kept.

## 5. Weekly stake payments (needs a payment provider and legal advice)

The stake is built as a ledger: each Monday 12:00 UTC (`pg_cron`) the database records who owes, the fee and each
member's share in `settlements` / `settlement_shares`, and the group gets a push. No money moves yet; past weeks
show as "Due". Debts survive leaving the group, deleting the group, and (as an unnamed former member) deleting the
account; charge at settlement time so a deleted account can't skip paying.

- **Provider:** Stripe Connect fits this: charge the person who owes (saved card), pay the shares out to the
  others' connected accounts, and keep the fee as the platform fee that goes to you. Stripe needs business
  verification and a bank account for payouts (no card). The fee must cover Stripe's own per-charge and payout
  costs; check current pricing.
- **Wiring:** a `charge-settlement` edge function triggered after `settle_week`, and a Stripe webhook that sets
  `settlements.paid_at`. The app needs an "Add payment method" step before joining a staked group.
- **Fee:** 10% by default. Change it with
  `update private.config set value = '<basis points>' where key = 'stake_fee_bps';` (1000 = 10%).
- **Legal and policy, before launch:** paying out pooled money based on who scrolled most can count as a paid
  contest or money transmission depending on the country/state. Using Stripe Connect covers the money-handling
  licence side, but get legal advice, check Google Play's real-money contests policy, and add payments to the
  privacy policy and Data safety form.

## 6. Play Store

- Host the privacy policy (`docs/privacy-policy.md`) and put its URL in the Play listing.
- Data safety form answers and the usage-access declaration: `docs/play-store.md`.
- Create a release signing key; re-check current Play policy for usage-access apps before submitting.

## 7. Device testing

- Phase 1: compare the debug screen's "Today" with Digital Wellbeing over a normal day on a real phone.
- Phase 4–5: second device joins by link; device A passing the limit notifies device B within ~15 min, once.
- Phase 7: Samsung and Xiaomi phones over a few days; the battery help banner should appear only if uploads stall.
