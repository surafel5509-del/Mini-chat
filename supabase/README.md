# MINI CHAT — Supabase backend

Everything the app needs from Postgres lives in [`migrations/`](migrations). The applied schema is:

| Object | Purpose |
| --- | --- |
| `profiles` | One row per `auth.users` row: `username` (unique, lowercase), `display_name`, `avatar_url`, `bio`, `last_seen_at`. Created automatically by the `handle_new_user` trigger. |
| `conversations` | Direct conversations only (a 2-person chat). `last_message_at` is denormalised for cheap ordering. |
| `conversation_members` | Membership, `last_read_at`, `is_pinned`, `is_muted`. Unique on `(conversation_id, user_id)`. |
| `messages` | `content`, `reply_to_id`, `edited_at`, `deleted_at`, `sender_id`. Never hard-deleted so the other side keeps the "message deleted" placeholder. |
| `message_reactions` | One emoji per (message, user). Primary key `(message_id, user_id)`. |
| `user_blocks` | `blocker_id` → `blocked_id`. Blocking hides the conversation in both directions and prevents new messages. |
| `user_reports` | Append-only moderation queue with a free-text reason. |
| `device_tokens` | FCM registration tokens per user and device, with `updated_at` for pruning. |
| `conversation_overview` (view) | Everything the home screen needs in one query: peer profile columns, `last_message_*`, `unread_count`, `is_pinned`, `is_muted`, `my_id`, `last_read_at`. |
| `get_or_create_direct_conversation(peer_id)` | Returns the existing 1:1 conversation or creates one plus both membership rows. Idempotent. |
| `mark_conversation_read(conversation_id)` | Sets `last_read_at = now()` for the caller. |
| `heartbeat()` | Updates `profiles.last_seen_at` — the source of the online dot. Called every 45 s while the app is in the foreground. |
| `register_device_token(token, platform)` | Upserts an FCM token for the caller and deletes it from any other user (device handover). |

## Row level security

RLS is enabled on every table. The short version:

* You can read and update **your own** profile; all profiles are readable by signed-in users so you
  can search for people to message.
* You can read a conversation only if `conversation_members` has a row for you.
* You can insert a message only into a conversation you belong to, and only as yourself.
* Reactions require message read access. Blocks are visible to both parties; reports are visible
  only to their author (and to service-role tooling).
* `device_tokens` rows are private to their owner.

## Applying the migrations

```bash
supabase link --project-ref <ref>
supabase db push          # applies supabase/migrations/*.sql in order
```

or paste the SQL into the Supabase SQL editor (the migrations are idempotent).

## Storage

Bucket `avatars` — public read, 5 MB limit, images only, writes restricted to a folder named after
the uploader's user id (`avatars/<user-id>/avatar-<timestamp>.jpg`). The app writes exactly that
path, so the storage policy is simply `(storage.foldername(name))[1] = auth.uid()::text`.

## Push notifications

`device_tokens` is written by the app (`PushTokenManager` → `register_device_token`). To deliver
messages while the app is in the background, deploy an Edge Function named `notify-message` and a
database webhook:

```sql
-- Database → Webhooks → "message-created"
-- table: messages, events: INSERT
-- type: Supabase Edge Function, function: notify-message
```

The function reads `conversation_members` for the conversation (excluding the sender), collects
`device_tokens` for those users, and sends an FCM v1 message per token with the data payload the
Android client expects:

```json
{
  "conversation_id": "<uuid>",
  "sender_name": "Ada",
  "preview": "See you at 7",
  "muted": "false"
}
```

`MiniChatMessagingService` maps that payload onto a grouped notification that opens the chat. When
`PUSH_NOTIFICATIONS_ENABLED=false` (the default in this repository) no token is ever registered and
nothing is sent — the rest of the app is unaffected.

## Test data

`migrations/` can be followed by the seed below in a scratch project to get two accounts that can
talk to each other immediately (`password123`):

```sql
-- Creates auth users ada@minichat.test, grace@minichat.test, mallory@minichat.test
-- and a direct conversation between ada and grace.
```
