# MINI CHAT

A production-shaped, native Android messenger: Kotlin + Jetpack Compose (Material 3), MVVM with
Hilt, Supabase (Postgres + Auth + Realtime + Storage) as the backend, Coil for images and FCM for
push notifications.

Yellow `#FFD43B` on white in light mode, charcoal `#101114` on `#191B20` in dark mode — the brand
lives in `design/theme`.

---

## Feature tour

| Area | What you get |
| --- | --- |
| **Auth** | Welcome → sign up (display name, email, password), sign in, email verification notice, password reset by emailed link, in-app password update after the deep link returns. |
| **Home** | Conversation list ordered by last activity, unread badges, online dots, typing indicator, pin/mute, pull-to-refresh style shimmer, local search over names and message previews, long-press action sheet. |
| **Chat** | Realtime 1:1 messaging, optimistic send with `Sending → Sent → Read` ticks and tap-to-retry, reply previews, edit, delete-for-everyone, emoji reactions, day dividers, "is typing" bubble, forward to another chat, copy, message info, paging older history. |
| **Contacts** | Search people by display name or `@username`, recent peers, start a conversation in one tap, block and report from the same screen. |
| **Profile** | My profile, other people's profiles (last seen, bio, block/report/unblock), edit profile with avatar upload to the `avatars` storage bucket. |
| **Settings** | Theme (light / dark / follow system), notification master switch + sound + vibration + previews, read receipts, typing indicators, blocked users, about, sign out. |
| **Push** | `FirebaseMessagingService` renders grouped notifications that deep-link into the conversation; device tokens are registered per user in `device_tokens`. Disabled safely when no Firebase project is configured. |
| **Security** | Session tokens in `EncryptedSharedPreferences`, no secrets in the APK besides the Supabase *publishable* key, Postgres row level security on every table. |

Minimum SDK 26 (Android 8.0), target SDK 36, compile SDK 37.

## Download the built APK

Every push to `arena/8de85148-mini-chat` is built by GitHub Actions. When the build is green the
workflow publishes:

* a **GitHub Release** named `build-<short-sha>` with `app-debug.apk` and `app-release.aab`
  attached — open <https://github.com/surafel5509-del/Mini-chat/releases> and download
  `app-debug.apk`;
* the same files as the **`minichat-apk` workflow artifact** on the run page;
* the raw Gradle log as a comment on issue **#1** and in `ci-logs/`.

Install it on a device or emulator:

```bash
adb install -r app-debug.apk
```

The debug APK is signed with the standard Android debug key (`applicationId com.minichat.app.debug`),
so it installs without any extra setup. The release APK produced by CI is **unsigned** until you add a
keystore — see below.

---

## Project layout

```
app/src/main/java/com/minichat/app
├── MainActivity.kt              edge-to-edge host, splash gate, deep links (minichat://chat/<id>)
├── MiniChatApplication.kt       Hilt app, Coil loader, notification channels
├── RootViewModel.kt             session + theme + push token wiring
├── core/                        datastore, encrypted session, presence, utils, DI modules
├── data/                        DTOs, repositories, Supabase realtime service
├── domain/                      models + repository contracts (UI never sees Supabase types)
├── design/                      theme (colour, type, shape) and reusable components
├── feature/
│   ├── auth/       welcome, login, register, password recovery
│   ├── home/       conversation list
│   ├── chat/       chat screen, bubbles, composer, emoji picker
│   ├── contacts/   people discovery
│   ├── profile/    my profile, user profile, edit profile
│   └── settings/   settings hub and sub-screens
├── navigation/                  Routes + NavHost
└── notifications/               channels, helper, FCM service, token manager
```

Architecture in one sentence: Compose screens observe immutable `UiState` from Hilt view models,
view models talk to repository interfaces, and the only place that knows Supabase exists is
`data/`.

---

## Backend

The Supabase project is configured in `app/build.gradle.kts` and can be overridden without touching
source:

```properties
# local.properties or environment
SUPABASE_URL=https://<project>.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_xxx
PUSH_NOTIFICATIONS_ENABLED=true|false
```

Schema, RLS policies, views and RPCs live in [`supabase/migrations`](supabase/migrations) and
[`supabase/README.md`](supabase/README.md) explains how to apply them, how to seed test users and how
to deploy the push notification Edge Function.

### Tables

`profiles`, `conversations`, `conversation_members`, `messages`, `message_reactions`, `user_blocks`,
`user_reports`, `device_tokens` + the `conversation_overview` view and four RPCs
(`get_or_create_direct_conversation`, `mark_conversation_read`, `heartbeat`, `register_device_token`).

Storage bucket `avatars` (public read, 5 MB limit, owner-only writes).

---

## Build

```bash
# debug APK
./gradlew :app:assembleDebug
# release APK + App Bundle (requires a keystore, see below)
./gradlew :app:assembleRelease :app:bundleRelease
```

Outputs land in `app/build/outputs/apk/…` and `app/build/outputs/bundle/…`.

### Signing a release build

Create a keystore and put the four values in `local.properties` (never commit them):

```properties
MINICHAT_KEYSTORE_PATH=/absolute/path/minichat-release.jks
MINICHAT_KEYSTORE_PASSWORD=…
MINICHAT_KEY_ALIAS=minichat
MINICHAT_KEY_PASSWORD=…
```

Without those values the release variant is built **unsigned** — it compiles and can be inspected,
but Android will refuse to install it. Debug builds are signed with the standard debug key and
install normally.

### Continuous integration

`.github/workflows/android.yml` builds the debug APK, the release APK and the AAB on every push,
publishes a readable build log to issue **#1**, and commits build outputs to `ci-logs/`.

---

## Push notifications (optional)

1. Create a Firebase project, add an Android app with package `com.minichat.app`.
2. Drop `google-services.json` into `app/`. The Google Services plugin applies itself only when that
   file exists, so the project builds fine without it.
3. Set `PUSH_NOTIFICATIONS_ENABLED=true`.
4. Deploy the `notify-message` Edge Function described in `supabase/README.md` so inserts into
   `messages` fan out through FCM.

Without these steps the app is fully functional; only background push is missing.

---

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

Unit tests cover validators, time formatting and repository mapping helpers.
