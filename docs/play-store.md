# Play Console answers (draft)

Re-check Google Play's current policies before submitting; usage-access apps get extra review.

## Data safety form

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Yes |
| Is all data encrypted in transit? | Yes (HTTPS to Supabase and Firebase) |
| Can users request deletion? | Yes, in-app ("Delete account") and by email |

Data types:

| Data type | Collected | Shared | Purpose | Optional? |
|---|---|---|---|---|
| App activity → App interactions (usage time per category, in 15-minute steps) | Yes | Yes, with the user's group members | App functionality | Required for the core feature |
| Personal info → Name (display name) | Yes | Yes, with group members | App functionality | Required (can be any name) |
| Device or other IDs (FCM push token) | Yes | No | App functionality (notifications) | Optional (notification permission) |

"Shared" here means visible to other users the person chose to group with. Supabase and Firebase are service
providers processing on our behalf, which Play does not count as sharing.

Not collected: location, contacts, installed-app list, exact usage per app, advertising ID.

## Prominent disclosure

Shown on the first onboarding screen, before usage access is requested, with an explicit "I agree" button. It covers
what is tracked, what friends see (15-minute steps per category), and what never leaves the phone (app names, exact
minutes). See `apps/android/feature/onboarding/.../OnboardingScreen.kt`.

## Permissions to justify

- `PACKAGE_USAGE_STATS` (usage access): core feature, measuring time in social apps so friends can hold each other
  accountable. Granted by the user in system settings.
- `POST_NOTIFICATIONS`: friend alerts. Optional.
- No `QUERY_ALL_PACKAGES`; tracked apps are declared in a `<queries>` block.
- No foreground service (the optional 60-second session alerts in the plan were not built).

## Account deletion

Required by Play for apps with accounts. In-app: Home → Delete account. It calls `delete_account()`, which deletes
the user from `auth.users`; every table cascades from it. Also provide a web URL or email for deletion requests in
the Play Console.
