# MINI CHAT

**Fast. Simple. Connected.**

A premium, real-time Android messaging app built with Jetpack Compose and Supabase:
real authentication, persistent PostgreSQL storage, live messaging over Realtime,
notes, reactions, receipts, presence, and a full settings surface — in light and dark mode.

<p align="center">
  <img src="docs/screenshot-chat.png" width="240" alt="Conversation">
</p>

---

## Download

Every successful build publishes a signed-with-debug-key APK as a GitHub Release:

**→ [Latest APK](https://github.com/surafel5509-del/Mini-chat/releases/latest)**

```bash
adb install -r app-debug.apk
```

`app-release.aab` is also produced for the Play Console. To cut a properly signed
release, provide `MINICHAT_KEYSTORE_PATH`, `MINICHAT_KEYSTORE_PASSWORD`,
`MINICHAT_KEY_ALIAS` and `MINICHAT_KEY_PASSWORD` (see *Configuration*).

---

## What's in the app

| Area | Details |
| --- | --- |
| **Auth** | Email + password sign up / sign in, email verification, password recovery deep link, encrypted session storage (Keystore), session restoration, protected navigation |
| **Messaging** | One-to-one real-time text, optimistic send with rollback, edit, soft delete, reply, reactions, copy, forward, read receipts, typing indicators, pagination |
| **Chat list** | Search, pin, mute, mark read, unread badges, online dots, typing preview, skeleton loading, empty states |
| **Notes** | Instagram-style ephemeral notes (≤80 chars, 15 min–24 h). Story row on Home, note card on profiles, note pill in chat headers, live updates |
| **Discovery** | Search by name or username, profile previews, start chat, block / unblock / report |
| **Profiles** | Avatar upload to Supabase Storage, display name, unique username, bio, live presence |
| **Settings** | Account, appearance (light / dark / system), privacy, notifications, blocked users, about / legal, sign out |
| **Platform** | Light + dark themes, mobile and tablet layouts, accessibility labels, reduced-motion aware animations |

---

## Architecture

```
app/src/main/java/com/minichat/app/
├── core/
│   ├── crypto/        Keystore-backed session storage
│   ├── datastore/     Theme + user settings (DataStore)
│   ├── di/            Hilt modules (Supabase, repositories, app scope)
│   ├── presence/      Foreground heartbeat + realtime keep-warm
│   └── util/          AppResult, time formatting, validators
├── data/
│   ├── dto/           kotlinx.serialization row types (embed-free by design)
│   ├── realtime/      Every subscription in one place (postgres_changes + broadcast)
│   └── repository/    Auth, chat, profile, notes implementations
├── domain/
│   ├── model/         Pure Kotlin models
│   └── repository/    Interfaces the UI talks to
├── design/
│   ├── components/    Buttons, fields, avatars, states, dialogs, notes
│   └── theme/         Colors, typography, shapes, extra color locals
├── feature/           welcome · auth · home · chat · contacts · profile · settings · notes
└── navigation/        Routes + NavHost with auth gate
```

**Layering rule:** ViewModels only ever see `domain` interfaces and `AppResult`.
No Supabase type and no raw exception reaches the UI.

### Why there is no PostgREST embed in the message path

The original client loaded messages with:

```text
messages?select=...,message_reactions(...),reply_to:messages!messages_reply_to_id_fkey(...)
```

That self-referencing embed depends on PostgREST's relationship cache. When the
hint stops resolving, **the entire request is rejected**:

```text
Could not find a relationship between 'messages' and 'messages' in the schema cache
Code: PGRST200
```

Because the insert asked for the same embed on its return payload, sending a
message failed too. The app now reads plain columns, then hydrates reply context
with one `get_reply_previews(uuid[])` RPC and reactions with one extra query.
Two simple queries cannot fail on a cache miss, so messaging cannot regress this way.

---

## Database

`supabase/migrations/` is the source of truth; it mirrors what is deployed.

| Migration | Purpose |
| --- | --- |
| `20261107000001_init.sql` | Tables, constraints, indexes, triggers, RPCs, RLS, storage bucket, realtime publication |
| `20261107120000_notes_and_reply_hardening.sql` | Notes table + policies, `set_my_note` / `delete_my_note`, `get_reply_previews`, `message_feed` |
| `20261107130000_username_leading_letter.sql` | Usernames must start with a letter; auth trigger normalises generated names |

**Tables:** `profiles`, `conversations`, `conversation_members`, `messages`,
`message_reactions`, `notes`, `user_blocks`, `user_reports`, `device_tokens`.

**Security posture**

- RLS is enabled on every exposed table; every policy is written against
  `auth.uid()`.
- Membership checks go through `is_conversation_member()` (SECURITY DEFINER,
  stable, no recursion) — never a client-supplied flag.
- `messages.sender_id` must equal the authenticated user on insert; a trigger
  re-checks membership and rejects blocked pairs. Content is trimmed and length
  checked (1–4000) by a constraint *and* the trigger.
- Notes are readable only by the author and people they share a conversation
  with, and only while unexpired.
- Avatars live in a public-read bucket where writes are restricted to
  `avatars/<auth.uid()>/…`, with MIME and size limits.
- Only the **publishable** key ships in the APK. Service role keys never leave the
  server.

Verified with a 15-case SQL suite executed as three distinct users (participant,
friend, stranger): participant access works, strangers read zero rows from every
table and from `get_reply_previews`, and forged `sender_id` / note authors are
rejected with `42501`.

---

## Configuration

The publishable key and project URL are baked into `BuildConfig` at build time and
can be overridden without touching source — put them in `local.properties`,
`gradle.properties`, or the environment:

```properties
SUPABASE_URL=https://<project>.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
PUSH_NOTIFICATIONS_ENABLED=false
```

Push notifications are optional: `google-services.json` is only applied when it
exists, so the project builds with or without Firebase.

### Deploying the backend to a fresh Supabase project

1. Create a project at [supabase.com](https://supabase.com).
2. Run the three files in `supabase/migrations/` **in order** in the SQL editor
   (or `supabase db push` with the CLI).
3. Copy the project URL and the publishable/anon key into the variables above.
4. Set `Site URL` and add `minichat://auth-callback` to the redirect allow list
   under *Authentication → URL configuration* (needed for password recovery).

---

## Building

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:assembleRelease      # minified release APK
```

Requires JDK 17+ and Android SDK 37. `minSdk` is 26.

CI (`.github/workflows/android.yml`) compiles, runs the unit tests, and publishes a
release with the APK + AAB. Compile and test failures fail the job — a green run
always means an installable APK exists.

---

## Design system

Warm yellow `#FFD43B` is the accent, never the canvas. Surfaces are near-white
`#F8F9FC` in light mode and `#101114` in dark mode, with amber `#F4B942` reserved
for secondary emphasis. Own messages are yellow with dark ink; incoming messages
use a muted surface. Every screen is built from the shared component set in
`design/components`, so light and dark mode stay consistent and theme switching
animates rather than flashing.

---

## Privacy

Messages are stored in your own Supabase project and are readable only by
conversation members. The app requests notification permission only when push
notifications are enabled and a user opts in. Blocking is enforced server-side,
so a blocked person cannot message you or see your notes even with a modified
client.
