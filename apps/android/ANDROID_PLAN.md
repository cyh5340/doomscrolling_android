# Android App — Implementation Plan

Screen-time social app: track time on "doomscroll" apps, alert a friend group when a limit is crossed, and rank friends on a leaderboard.

This file lives at `apps/android/ANDROID_PLAN.md` in the monorepo. Work through it phase by phase. Each phase ends with acceptance criteria. Don't start a phase until the previous one passes.

---

## Ground rules (read before every phase)

- **Kotlin only.** Jetpack Compose UI, no XML layouts.
- **Report in buckets, never exact minutes.** The iOS app can only report 15-minute threshold crossings, so Android must use the same model to keep the leaderboard fair. Exact usage stays on the device.
- **Track by label, not by app name.** The server sees `"social"`, never `"com.zhiliaoapp.musically"`. Package names never leave the device.
- **Keep clients dumb.** The device reports events. The server decides streaks, leaderboard order, and who gets notified.
- **Upload idempotently.** Every event has a client-generated UUID, so resends are harmless.
- **Ask for permissions only when needed.** No permission requests at app launch.

---

## Tech stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| minSdk / targetSdk | 29 / latest stable (minSdk 29 gives us `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` usage events) |
| DI | Hilt |
| Local storage | Room (sessions, event outbox), DataStore (prefs) |
| Background work | WorkManager (periodic, 15 min) |
| Backend | Supabase (free tier, no card): Auth, Postgres + row-level security, Realtime, Edge Functions. Code in `backend/supabase/` |
| Push | Firebase Cloud Messaging only (free Spark plan, no card), sent from the `send-alert` edge function |
| Deep links | Android App Links on our own domain (Firebase Dynamic Links is shut down — don't use it) |
| Testing | JUnit5, Turbine, Robolectric for the usage engine |

---

## Module layout

```
apps/android/
  app/                  Application, navigation, Hilt setup
  core/usage/           UsageStats reading, session reconstruction, bucket logic (pure Kotlin where possible)
  core/data/            Room DB, repositories, event outbox
  core/network/         Supabase client, groups, event upload (SyncWorker), FCM push
  feature/onboarding/   Sign-in, permission flow, app-category pick
  feature/groups/       Create/join group, invite links, members
  feature/leaderboard/  Leaderboard screen
  feature/home/         Today's usage, streaks, debug panel
```

---

## Shared event contract

Define this in `shared/contracts/events.schema.json` so the iOS app and backend use the same format.

```json
{
  "eventId": "uuid",
  "userId": "string",
  "platform": "android | ios",
  "label": "social",
  "type": "bucket_reached | session_threshold",
  "bucketMinutes": 45,
  "localDate": "2026-10-18",
  "timezone": "America/Los_Angeles",
  "occurredAt": "ISO-8601 UTC"
}
```

- `bucket_reached`: cumulative usage for `label` today crossed `bucketMinutes` (a multiple of 15). Sent once per bucket per day.
- `session_threshold`: one continuous session crossed the group's session limit. **Android-only for now** — iOS can't detect sessions. The backend treats it as optional.

---

## Phase 0 — Project setup

- Create the Gradle multi-module project with version catalogs (`libs.versions.toml`).
- Add Hilt, Compose, Room, WorkManager, and Firebase BoM.
- Set up CI: build, lint, unit tests.

**Done when:** the empty app builds and runs, and CI is green.

---

## Phase 1 — Usage engine (no network, no UI polish)

This is the core of the app. Build it first and test it thoroughly.

**1a. Tracked app list**
- Bundle a JSON list mapping package names to labels (e.g. TikTok, Instagram, YouTube, Reddit, X, Snapchat, Facebook → `social`). Later this list comes from Firestore remote config.
- Declare those packages in a manifest `<queries>` block so we can check whether they're installed.
- **Do not use `QUERY_ALL_PACKAGES`** — Play restricts it.
- Optionally also include installed packages whose `ApplicationInfo.category == CATEGORY_SOCIAL`, but only among visible packages.

**1b. Reading events**
- `UsageStatsManager.queryEvents(start, end)`.
- Use `ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED`, `SCREEN_NON_INTERACTIVE`, and `DEVICE_SHUTDOWN`.
- Android only keeps detailed events for a few days, so read incrementally and store a `lastProcessedTimestamp` cursor in DataStore.

**1c. Session reconstruction** (`SessionBuilder`, pure Kotlin, heavily unit-tested)
- A session opens on `ACTIVITY_RESUMED` for a tracked package.
- It closes on pause/stop, screen off, or shutdown.
- Merge gaps shorter than `mergeGapSeconds` (default 30) between sessions of the **same label**. Briefly switching apps or opening a notification shouldn't break a doomscroll session.
- Handle sessions spanning midnight: split them across `localDate`s for daily totals, but keep them whole for session-threshold detection.
- Handle a session still open at read time: count it up to "now" and mark it `isOngoing`.

**1d. Bucket and threshold logic** (`ThresholdEvaluator`)
- Compute today's cumulative minutes per label, then emit `bucket_reached` for each newly crossed 15-minute bucket. Store emitted buckets in Room so nothing is emitted twice.
- Emit `session_threshold` when an ongoing or finished session crosses the group's session limit, once per session.
- Write events to a Room **outbox** table with status `pending`.

**1e. Debug screen**
- Show raw sessions, today's totals, and outbox contents. Add a "Run now" button that triggers the worker immediately.

**Done when:** unit tests cover overlapping events, midnight crossover, screen-off, merge gaps, and duplicate-emission prevention, and the debug screen shows correct numbers compared with Android's Digital Wellbeing screen.

---

## Phase 2 — Onboarding and permission flow

1. Welcome screen, then sign-in (Google Sign-In via Firebase Auth; anonymous auth is fine for early testing).
2. **Prominent disclosure screen** (required by Google Play's user data policy). In plain language: what's tracked, what's shared with friends (time buckets per label only), and what never leaves the phone (app names, exact minutes). It needs an explicit "I agree" button.
3. Usage access: check `AppOpsManager.OPSTR_GET_USAGE_STATS`. If it isn't granted, deep-link to `Settings.ACTION_USAGE_ACCESS_SETTINGS`, re-check in `onResume`, and advance automatically when it's granted.
4. Notification permission (`POST_NOTIFICATIONS`, Android 13+), with a one-line explanation shown beforehand.
5. Done. Schedule the WorkManager job.

If the user arrived through an invite link, keep the group ID through the whole flow and join the group automatically at the end.

**Done when:** a fresh install reaches tracking in at most 4 taps plus one settings toggle, and revoking usage access later shows a clear banner to re-enable it.

---

## Phase 3 — Backend and event upload

**Postgres tables** (`backend/supabase/migrations/`)
```
profiles        id, display_name, photo_url, timezone        (created by trigger on sign-up)
push_tokens     user_id, token                               (owner-only; never visible to group-mates)
groups          name, owner_id, invite_code, label, daily_alert_minutes, session_alert_minutes, streak_days, ranking
group_members   group_id, user_id, joined_at, muted
events          (contract above, snake_case; event_id is the primary key)
daily_usage     user_id, label, local_date, minutes          (derived by trigger, read-only for clients)
alerts          group_id, user_id, rule, local_date          (server-only; unique key = rate limit)
```

**Client**
- `SyncWorker` (every 15 min) refreshes group rules, runs the usage engine, then inserts `pending` outbox rows into `events` with `on conflict do nothing` (the event ID is the primary key, so uploads are idempotent) and marks them `sent`. Exponential backoff on failure.
- Refresh the FCM token on change and save it to `push_tokens`.

**Row-level security**
- Users can write only their own events, profile and push token.
- Groups, members and group-mates' daily usage can be read only by members.
- Creating and joining groups go through `create_group` / `join_group` functions; daily totals and alerts can't be written by clients.

**Done when:** events show up in Supabase within one worker cycle, airplane-mode events upload later without duplicates, and the access rules have database tests (`npx supabase test db`).

---

## Phase 4 — Groups and invites

- Create a group: name plus rules, with presets such as "Social apps, alert at 1h daily, 45 min session."
- Invite link: `https://<our-domain>/join/{inviteCode}`, opened via App Links. Host `/.well-known/assetlinks.json` on our domain.
- Joining a group applies its rules automatically, so the user has nothing to configure.
- Show the member list. Allow leaving a group.
- Decide how to handle multiple groups with different rules: evaluate each group's rules separately, but upload one shared stream of bucket events.

**Done when:** a second device can join through a link and the group appears on both devices.

---

## Phase 5 — Friend alerts (database trigger + edge function + FCM)

`on_event` database trigger (writes an `alerts` row; a second trigger hands each new alert to the `send-alert` edge function, which sends FCM to the other, unmuted members):
- For `bucket_reached`: if `bucketMinutes` reaches the group's `dailyAlertMinutes` for the first time that day, notify the other members.
- For `session_threshold`: notify the other members.
- **Streaks:** consecutive days over the group's daily limit, computed from `daily_usage`; notify when `streakDays` is reached.
- Rate-limit to one alert per user per rule per day, to avoid spam.

Client:
- Handle FCM messages; tapping a notification opens the group screen.
- Add a per-group mute toggle.

**Done when:** device A crossing the limit sends a notification to device B within about 15 minutes, with no duplicate alerts.

---

## Phase 6 — Leaderboard

- The `leaderboard(group, from, to)` database function sums each member's `daily_usage` for the day or week on read, so there's nothing to keep in sync.
- Leaderboard screen: day/week tabs, avatars, minutes (always a multiple of 15), and streak flames.
- Decide on a ranking rule. Suggestion: "most doomscrolled" is the leaderboard and "least" is the hall of fame. Make it a group setting.

**Done when:** the leaderboard updates live while the app is open (Supabase Realtime on `daily_usage`) and matches the totals on each member's debug screen.

---

## Phase 7 — Polish and Play Store readiness

- Fill in the Data safety form: usage data is collected and shared with other users, encrypted in transit, and deletable on request.
- In-app account deletion that deletes all of the user's data on the server (Play requires this).
- Privacy policy URL.
- Reliability: test on Samsung and Xiaomi devices, which are aggressive about killing background work. Add a help screen about battery settings that's shown only if uploads stall.
- Check current Play Console requirements for usage-access apps before submitting; policies change.
- **Optional:** near-real-time session alerts using a foreground service that polls every 60 seconds. This needs a `specialUse` foreground service type and a Play justification. Only add it if 15-minute latency proves to be a real problem.

---

## Open decisions

- Default `mergeGapSeconds` value (30 s?) — tune with real usage.
- Should the leaderboard reward the most usage or the least?
- Are display names public within a group only, or app-wide? (Built: group only.)
- ~~Firebase versus Supabase~~ — decided: Supabase for the backend, FCM for push only. Both free with no card.

## Status (2026-10-20)

- Phases 0–7 are built except what needs your accounts, a domain or real devices: see `TODO.md` at the repo root.
- Leaderboard ranking: a group setting (`most` = leaderboard, `least` = hall of fame), chosen when creating the group.
- Sign-in is anonymous for now; Google sign-in is in `TODO.md`.
- Not built: the optional 60-second foreground-service session alerts (Phase 7).
- Weekly stake (added 2026-10-21): the week's top doomscroller owes the group's stake (default $20, chosen at creation,
  fixed after). After a processing fee (10%, `private.config.stake_fee_bps`) it's split evenly among the other
  members; leftover cents go to the fee. Ties and empty weeks pay nothing. Members accept the stake on the join
  screen (`join_group(code, accept_stake)`), so invite links open that screen instead of joining directly.
  A week counts whoever was in the group at its start (Monday 00:00 UTC): mid-week joiners start next Monday,
  leaving mid-week doesn't escape that week, and settlements survive the group being deleted. Settled by `pg_cron`
  (daily 12:00 UTC, catching up the last 4 weeks; each week is final once settled) into `settlements`.
  Ledger only: payments are in `TODO.md`.
