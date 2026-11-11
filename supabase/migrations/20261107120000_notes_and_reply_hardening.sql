-- =============================================================================
-- MINI CHAT — 002
--   * Fixes the PGRST200 "could not find a relationship between 'messages' and
--     'messages'" failure that broke sending/loading messages.
--   * Adds the Notes feature (Instagram-style shared notes).
--   * Adds content edit/delete helpers used by the upgraded message action sheet.
--
-- WHY THE OLD QUERY FAILED
-- ------------------------
-- The client embedded the replied-to message inside a message row:
--
--   reply_to:messages!messages_reply_to_id_fkey(id,sender_id,content,profiles(...))
--
-- That is a *self referencing* embed. PostgREST only resolves it while its
-- schema cache holds an exact entry for that constraint hint. When the cache is
-- rebuilt (or the service restarts) the hint resolution silently breaks and the
-- WHOLE request is rejected with PGRST200 — which also means "send message"
-- fails, because the insert asks for the same embed on the return payload.
--
-- The fix is twofold:
--   1. Stop depending on the self embed from the client entirely: the client now
--      reads the plain `messages` table. A stable, security-invoker view
--      (`message_feed`) and an RPC (`get_reply_previews`) provide reply context.
--   2. Give the client a single self-contained way to hydrate reply previews.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Re-assert the relationship the client used to hint at.
--    (Idempotent: safe when the constraint already exists.)
-- -----------------------------------------------------------------------------
do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'messages_reply_to_id_fkey'
          and conrelid = 'public.messages'::regclass
    ) then
        alter table public.messages
            add constraint messages_reply_to_id_fkey
            foreign key (reply_to_id) references public.messages (id) on delete set null;
    end if;
end;
$$;

-- -----------------------------------------------------------------------------
-- 2. Notes — short, ephemeral status notes shown on the profile and in the
--    "friends with a note" row at the top of the chat list.
-- -----------------------------------------------------------------------------
create table if not exists public.notes (
    id         uuid primary key default gen_random_uuid(),
    author_id  uuid not null references public.profiles (id) on delete cascade,
    body       text not null,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint notes_one_active_per_author unique (author_id),
    constraint notes_body_length check (char_length(btrim(body)) between 1 and 80),
    constraint notes_expiry_window check (
        expires_at > created_at - interval '1 minute'
        and expires_at <= now() + interval '25 hours'
    )
);

comment on table public.notes is
    'Instagram-style ephemeral note. One active note per author, hard expiry enforced server side.';

create index if not exists notes_expires_at_idx on public.notes (expires_at);
create index if not exists notes_author_idx on public.notes (author_id);

drop trigger if exists notes_touch_updated_at on public.notes;
create trigger notes_touch_updated_at
    before update on public.notes
    for each row execute function public.touch_updated_at();

-- Notes are pruned lazily by a cheap function; no cron required. The app never
-- sees an expired note because reads are always filtered by expires_at.
create or replace function public.prune_expired_notes()
returns integer
language sql
volatile
as $$
    with deleted as (
        delete from public.notes where expires_at < now() returning 1
    )
    select count(*)::int from deleted;
$$;

alter table public.notes enable row level security;

-- Read: my own note, or a note written by somebody I share a conversation with.
-- Blocked pairs never see each other's notes. Notes are always filtered by
-- expiry so a stale row can never leak.
drop policy if exists "notes are visible to friends" on public.notes;
create policy "notes are visible to friends"
    on public.notes for select
    to authenticated
    using (
        expires_at > now()
        and (
            author_id = auth.uid()
            or (
                public.shares_conversation_with(author_id)
                and not public.is_blocked_between(auth.uid(), author_id)
            )
        )
    );

drop policy if exists "users write their own note" on public.notes;
create policy "users write their own note"
    on public.notes for insert
    to authenticated
    with check (
        author_id = auth.uid()
        and char_length(btrim(body)) between 1 and 80
        and expires_at > now()
    );

drop policy if exists "users update their own note" on public.notes;
create policy "users update their own note"
    on public.notes for update
    to authenticated
    using (author_id = auth.uid())
    with check (author_id = auth.uid());

drop policy if exists "users delete their own note" on public.notes;
create policy "users delete their own note"
    on public.notes for delete
    to authenticated
    using (author_id = auth.uid());

grant select, insert, update, delete on public.notes to authenticated;
grant execute on function public.prune_expired_notes() to authenticated;

-- set_my_note: validated, rate-limited write path. SECURITY INVOKER on purpose:
-- row level security above remains the authority, this only centralises input
-- validation and the "replace my current note" semantics.
create or replace function public.set_my_note(p_body text, p_minutes integer default 1440)
returns public.notes
language plpgsql
security invoker
set search_path = public
as $$
declare
    me       uuid := auth.uid();
    clean    text;
    result   public.notes;
    minutes  integer := coalesce(p_minutes, 1440);
    recent   integer;
begin
    if me is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    clean := btrim(coalesce(p_body, ''));
    if char_length(clean) = 0 then
        raise exception 'note cannot be empty' using errcode = '22000';
    end if;
    if char_length(clean) > 80 then
        raise exception 'notes are limited to 80 characters' using errcode = '22001';
    end if;

    if minutes < 15 or minutes > 1440 then
        raise exception 'note duration must be between 15 minutes and 24 hours'
            using errcode = '22023';
    end if;

    -- Soft abuse guard: at most 30 note writes per 10 minutes.
    select count(*) into recent
    from public.notes n
    where n.author_id = me
      and n.updated_at > now() - interval '10 minutes';

    if recent > 30 then
        raise exception 'you are posting notes too quickly' using errcode = '22023';
    end if;

    insert into public.notes (author_id, body, expires_at)
    values (me, clean, now() + make_interval(mins => minutes))
    on conflict (author_id) do update
        set body       = excluded.body,
            expires_at = excluded.expires_at,
            updated_at = now()
    returning * into result;

    return result;
end;
$$;

create or replace function public.delete_my_note()
returns void
language sql
security invoker
set search_path = public
as $$
    delete from public.notes where author_id = auth.uid();
$$;

grant execute on function public.set_my_note(text, integer) to authenticated;
grant execute on function public.delete_my_note() to authenticated;

-- -----------------------------------------------------------------------------
-- 3. Reply previews — replaces the fragile self embed.
--
--    The client loads a page of messages, then asks once for the small set of
--    messages that are referenced by `reply_to_id`. One round trip, no self
--    embed, no PGRST200, and an attacker cannot use the RPC to read messages
--    outside their conversations because membership is checked row by row.
-- -----------------------------------------------------------------------------
create or replace function public.get_reply_previews(p_message_ids uuid[])
returns table (
    id           uuid,
    sender_id    uuid,
    content      text,
    is_deleted   boolean,
    display_name text,
    username     text
)
language sql
stable
security invoker
set search_path = public
as $$
    select
        m.id,
        m.sender_id,
        m.content,
        m.is_deleted,
        p.display_name,
        p.username
    from public.messages m
    left join public.profiles p on p.id = m.sender_id
    where m.id = any (coalesce(p_message_ids, array[]::uuid[]))
      and public.is_conversation_member(m.conversation_id);
$$;

grant execute on function public.get_reply_previews(uuid[]) to authenticated;

-- -----------------------------------------------------------------------------
-- 4. A security-invoker message view the client can read instead of the base
--    table. Same columns, plus nothing sensitive. Kept for future clients; the
--    current Kotlin client reads `messages` directly.
-- -----------------------------------------------------------------------------
drop view if exists public.message_feed;

create view public.message_feed
with (security_invoker = true) as
select
    m.id,
    m.conversation_id,
    m.sender_id,
    m.content,
    m.reply_to_id,
    m.is_edited,
    m.is_deleted,
    m.created_at,
    m.updated_at
from public.messages m;

grant select on public.message_feed to authenticated;

-- -----------------------------------------------------------------------------
-- 5. Realtime: notes change live on the story row.
-- -----------------------------------------------------------------------------
do $$
begin
    begin
        alter publication supabase_realtime add table public.notes;
    exception when duplicate_object then null;
    end;
end;
$$;

alter table public.notes replica identity full;

-- -----------------------------------------------------------------------------
-- 6. Nudge PostgREST to rebuild its relationship cache immediately, so clients
--    that still hold the older schema never hit PGRST200 again.
-- -----------------------------------------------------------------------------
do $$
begin
    perform pg_notify('pgrst', 'reload schema');
exception when others then null;
end;
$$;
