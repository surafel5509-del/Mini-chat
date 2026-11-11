# MINI CHAT — install and test

## Download

**Installable APK (debug-signed, install this one):**

https://github.com/surafel5509-del/Mini-chat/releases/download/build-326fcc0/app-debug.apk

27 MB · `applicationId` `com.minichat.app.debug` · Android 8.0 (API 26) or newer

Other files in the same release:

| File | What it is |
| --- | --- |
| `app-debug.apk` | Installable, self-signed. Use this. |
| `app-release-unsigned.apk` | 4.4 MB, R8-minified, **unsigned** — cannot be installed until you sign it. |
| `app-release.aab` | Play Store bundle, minified and signed-ready. |

Because the APK is debug-signed you will need to allow install from your browser
or file manager the first time. To rebuild it from source:

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew bundleRelease          # app/build/outputs/bundle/release/
```

## What changed in this build

### The inbox error is fixed

The home screen used to show *"We couldn't read the server response."* and no
conversations. `conversation_overview` is a Postgres view built from LEFT JOINs,
so every one of its columns is nullable. The DTO declared six of them non-null,
and kotlinx.serialization throws on an explicit JSON `null` in a non-null field
**even when that field has a default** — so the single group in the account with
no messages yet failed the whole list. Every field is nullable now and normalised
in `toDomainOrNull`.

This was also what looked like a *Create Group crash*: the group was created
correctly, and then the inbox failed to render. Groups are still created and
listed, and the row for an empty group reads "No messages yet".

### Confirmation is an emailed link, not a code

There is no six-digit screen any more. Registering takes you to a "Check your
inbox" screen with a resend button on a 30-second cooldown. Three things had to
line up before the link could actually work:

- `signUp` now sends `redirectUrl = minichat://auth-callback`. Without it GoTrue
  redirected to the project's Site URL — a web page — and the app never resumed.
- The project's Site URL was still `http://localhost:3000` with an empty redirect
  allowlist, so `minichat://` was rejected outright. Both are set now.
- The deep link used to be treated as a password reset unconditionally, which
  dropped new users on a "choose a new password" screen. Only `type=recovery`
  does that now; `type=signup` simply confirms and signs you in.

**When testing:** the free Supabase plan sends a limited number of emails per
hour, so use one address and don't hammer resend.

### Pull to refresh

Pull down on the inbox to reload conversations and notes. Pull down inside a
conversation to re-read the thread, its newest page and the read state — this is
also the manual fix for a message that arrived while the socket was
reconnecting.

### Look and feel

Manrope is now bundled (one 164 KB variable font, every weight from Regular to
ExtraBold), so the type scale finally renders in the face it was designed around
instead of the system font.

### New: search inside a conversation

The magnifier in a chat's top bar swaps the thread for a search field over the
loaded history, with a live match count and each result labelled by sender and
time.

### Smaller fixes

- Group photos were uploaded to `avatars/<conversationId>/`, but the storage
  policy requires `avatars/<auth.uid>/` — every group photo failed with a 403.
- Double-tapping "Create group" could create two groups.
- Removed the last force-unwrap in a group screen.

## Testing checklist

1. Sign up with a fresh address → you land on "Check your inbox", not a code screen.
2. Open the email, tap **Confirm** → the app opens and you are signed in.
3. Home loads with your conversations; a group with no messages shows
   "No messages yet" rather than an error.
4. Create a group → no crash, the group appears in the list.
5. Open a conversation, send a message, send a voice note (stops at 7 s).
6. Reply, react, edit, delete. Long-press a conversation to pin or mute it.
7. Tap the magnifier in a chat and search for a word you sent.
8. Pull down on the inbox, then pull down inside a chat.
9. Upload a profile picture from the photo picker; upload a group photo.
10. Settings → Appearance → switch light/dark.
11. Sign out and back in.
12. "Forgot password" → the emailed link opens the reset screen.

## Backend

Supabase project `moiibzxhvioqqxoxfymn`. All tables are protected by row level
security; the APK ships only the publishable key.

Notifications come from Supabase Realtime over a foreground service — there is
no Firebase anywhere in this project.

The `otp-guard` Edge Function is still deployed but nothing calls it; the app no
longer uses six-digit codes. It can be deleted from the dashboard safely.
