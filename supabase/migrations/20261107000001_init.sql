-- =============================================================================
-- MINI CHAT — core schema
-- Fast. Simple. Connected.
-- =============================================================================
-- Tables:  profiles, conversations, conversation_members, messages,
--          message_reactions, user_blocks, user_reports, device_tokens
-- Objects: helper functions, triggers, indexes, row level security policies,
--          a security-invoker view for the home screen and storage policies.
-- =============================================================================

create extension if not exists pgcrypto;

-- -----------------------------------------------------------------------------
-- profiles
-- -----------------------------------------------------------------------------
create table if not exists public.profiles (
    id           uuid primary key references auth.users (id) on delete cascade,
    username     text not null unique,
    display_name text not null,
    avatar_url   text,
    bio          text,
    last_seen_at timestamptz not null default now(),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    constraint profiles_username_format check (username ~ '^[a-z0-9_]{3,24}$'),
    constraint profiles_display_name_length check (char_length(display_name) between 1 and 48),
    constraint profiles_bio_length check (bio is null or char_length(bio) <= 240),
    constraint profiles_avatar_url_length check (avatar_url is null or char_length(avatar_url) <= 512)
);

comment on table public.profiles is 'Public user profile, one row per auth user.';

-- -----------------------------------------------------------------------------
-- conversations
-- -----------------------------------------------------------------------------
create table if not exists public.conversations (
    id                uuid primary key default gen_random_uuid(),
    conversation_type text not null default 'direct',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint conversations_type_check check (conversation_type in ('direct', 'group'))
);

-- -----------------------------------------------------------------------------
-- conversation_members
-- -----------------------------------------------------------------------------
create table if not exists public.conversation_members (
    id             uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    user_id        uuid not null references public.profiles (id) on delete cascade,
    last_read_at   timestamptz not null default now(),
    is_muted       boolean not null default false,
    is_pinned      boolean not null default false,
    joined_at      timestamptz not null default now(),
    constraint conversation_members_unique unique (conversation_id, user_id)
);

-- -----------------------------------------------------------------------------
-- messages
-- -----------------------------------------------------------------------------
create table if not exists public.messages (
    id              uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    sender_id       uuid not null references public.profiles (id) on delete cascade,
    content         text not null,
    reply_to_id     uuid references public.messages (id) on delete set null,
    is_edited       boolean not null default false,
    is_deleted      boolean not null default false,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint messages_content_length check (char_length(content) between 1 and 4000)
);

-- -----------------------------------------------------------------------------
-- message_reactions
-- -----------------------------------------------------------------------------
create table if not exists public.message_reactions (
    id         uuid primary key default gen_random_uuid(),
    message_id uuid not null references public.messages (id) on delete cascade,
    user_id    uuid not null references public.profiles (id) on delete cascade,
    emoji      text not null,
    created_at timestamptz not null default now(),
    constraint message_reactions_unique unique (message_id, user_id, emoji),
    constraint message_reactions_emoji_length check (char_length(emoji) between 1 and 16)
);

-- -----------------------------------------------------------------------------
-- user_blocks / user_reports
-- -----------------------------------------------------------------------------
create table if not exists public.user_blocks (
    id         uuid primary key default gen_random_uuid(),
    blocker_id uuid not null references public.profiles (id) on delete cascade,
    blocked_id uuid not null references public.profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint user_blocks_unique unique (blocker_id, blocked_id),
    constraint user_blocks_no_self check (blocker_id <> blocked_id)
);

create table if not exists public.user_reports (
    id               uuid primary key default gen_random_uuid(),
    reporter_id      uuid not null references public.profiles (id) on delete cascade,
    reported_user_id uuid not null references public.profiles (id) on delete cascade,
    reason           text not null,
    created_at       timestamptz not null default now(),
    constraint user_reports_reason_length check (char_length(reason) between 3 and 500),
    constraint user_reports_no_self check (reporter_id <> reported_user_id)
);

-- -----------------------------------------------------------------------------
-- device_tokens (Android FCM push registration)
-- -----------------------------------------------------------------------------
create table if not exists public.device_tokens (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references public.profiles (id) on delete cascade,
    fcm_token  text not null unique,
    platform   text not null default 'android',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint device_tokens_platform_check check (platform in ('android', 'ios'))
);

-- -----------------------------------------------------------------------------
-- Indexes
-- -----------------------------------------------------------------------------
create index if not exists profiles_username_idx on public.profiles (lower(username));
create index if not exists profiles_display_name_idx on public.profiles (lower(display_name));

create index if not exists conversation_members_user_idx on public.conversation_members (user_id);
create index if not exists conversation_members_conversation_idx on public.conversation_members (conversation_id);

create index if not exists messages_conversation_created_idx
    on public.messages (conversation_id, created_at desc);
create index if not exists messages_sender_idx on public.messages (sender_id);
create index if not exists messages_reply_to_idx on public.messages (reply_to_id);

create index if not exists message_reactions_message_idx on public.message_reactions (message_id);

create index if not exists user_blocks_blocker_idx on public.user_blocks (blocker_id);
create index if not exists user_blocks_blocked_idx on public.user_blocks (blocked_id);
create index if not exists user_reports_reported_idx on public.user_reports (reported_user_id);
create index if not exists device_tokens_user_idx on public.device_tokens (user_id);

-- -----------------------------------------------------------------------------
-- Helper functions (all security definer to avoid recursive RLS evaluation)
-- -----------------------------------------------------------------------------
create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create or replace function public.is_conversation_member(p_conversation_id uuid, p_user_id uuid default auth.uid())
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1
        from public.conversation_members m
        where m.conversation_id = p_conversation_id
          and m.user_id = p_user_id
    );
$$;

create or replace function public.shares_conversation_with(p_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1
        from public.conversation_members mine
        join public.conversation_members theirs on theirs.conversation_id = mine.conversation_id
        where mine.user_id = auth.uid()
          and theirs.user_id = p_user_id
    );
$$;

create or replace function public.is_blocked_between(p_a uuid, p_b uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select exists (
        select 1
        from public.user_blocks b
        where (b.blocker_id = p_a and b.blocked_id = p_b)
           or (b.blocker_id = p_b and b.blocked_id = p_a)
    );
$$;

create or replace function public.is_conversation_muted(p_conversation_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select coalesce((
        select m.is_muted
        from public.conversation_members m
        where m.conversation_id = p_conversation_id
          and m.user_id = auth.uid()
    ), false);
$$;

-- -----------------------------------------------------------------------------
-- Triggers
-- -----------------------------------------------------------------------------

-- Automatically create a profile for every new auth user.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    base_username text;
    candidate     text;
    suffix        int := 0;
    fallback_name text;
begin
    base_username := lower(regexp_replace(split_part(coalesce(new.email, 'user'), '@', 1), '[^a-z0-9_]', '', 'g'));
    if base_username is null or char_length(base_username) < 3 then
        base_username := 'user';
    end if;
    base_username := left(base_username, 18);
    candidate := base_username;

    while exists (select 1 from public.profiles p where p.username = candidate) loop
        suffix := suffix + 1;
        candidate := left(base_username, 14) || suffix::text;
    end loop;

    fallback_name := coalesce(
        nullif(btrim(new.raw_user_meta_data ->> 'display_name'), ''),
        initcap(replace(base_username, '_', ' '))
    );

    insert into public.profiles (id, username, display_name, avatar_url)
    values (
        new.id,
        candidate,
        left(fallback_name, 48),
        nullif(btrim(new.raw_user_meta_data ->> 'avatar_url'), '')
    )
    on conflict (id) do nothing;

    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

drop trigger if exists profiles_touch_updated_at on public.profiles;
create trigger profiles_touch_updated_at
    before update on public.profiles
    for each row execute function public.touch_updated_at();

drop trigger if exists conversations_touch_updated_at on public.conversations;
create trigger conversations_touch_updated_at
    before update on public.conversations
    for each row execute function public.touch_updated_at();

drop trigger if exists device_tokens_touch_updated_at on public.device_tokens;
create trigger device_tokens_touch_updated_at
    before update on public.device_tokens
    for each row execute function public.touch_updated_at();

-- Message integrity: sender cannot be spoofed, conversation cannot be moved,
-- edits are flagged, whitespace-only payloads are rejected, blocked pairs are
-- rejected. Client-side input validation is never trusted.
create or replace function public.messages_guard()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    peer_blocked boolean;
begin
    if tg_op = 'INSERT' then
        if not public.is_conversation_member(new.conversation_id, new.sender_id) then
            raise exception 'sender is not a member of this conversation' using errcode = '42501';
        end if;

        select exists (
            select 1
            from public.user_blocks b
            join public.conversation_members peer
              on peer.conversation_id = new.conversation_id
             and peer.user_id = b.blocker_id
            where b.blocked_id = new.sender_id
               or b.blocker_id = new.sender_id
        ) into peer_blocked;

        if peer_blocked then
            raise exception 'you cannot message this user' using errcode = '42501';
        end if;

        new.content := btrim(new.content);
        if char_length(new.content) = 0 then
            raise exception 'message content cannot be empty' using errcode = '22000';
        end if;

        new.is_edited := false;
        new.is_deleted := false;
        new.created_at := now();
        new.updated_at := now();

        -- The sender has implicitly read their own message.
        update public.conversation_members m
           set last_read_at = now()
         where m.conversation_id = new.conversation_id
           and m.user_id = new.sender_id;

        update public.conversations c
           set updated_at = now()
         where c.id = new.conversation_id;

        return new;
    end if;

    -- UPDATE
    if new.sender_id <> old.sender_id or new.conversation_id <> old.conversation_id then
        raise exception 'sender_id and conversation_id are immutable' using errcode = '42501';
    end if;

    if new.is_deleted and not old.is_deleted then
        -- Soft delete: content is wiped immediately.
        new.content := '';
        new.is_edited := old.is_edited;
        new.updated_at := now();
        return new;
    end if;

    if new.content is distinct from old.content then
        new.content := btrim(new.content);
        if char_length(new.content) = 0 then
            raise exception 'message content cannot be empty' using errcode = '22000';
        end if;
        new.is_edited := true;
    end if;

    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists messages_guard_trigger on public.messages;
create trigger messages_guard_trigger
    before insert or update on public.messages
    for each row execute function public.messages_guard();

-- Reactions are only allowed inside conversations the actor belongs to.
create or replace function public.message_reactions_guard()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    conversation uuid;
begin
    select m.conversation_id into conversation
    from public.messages m
    where m.id = new.message_id;

    if conversation is null then
        raise exception 'message not found' using errcode = '23503';
    end if;

    if not public.is_conversation_member(conversation, new.user_id) then
        raise exception 'not a conversation member' using errcode = '42501';
    end if;

    return new;
end;
$$;

drop trigger if exists message_reactions_guard_trigger on public.message_reactions;
create trigger message_reactions_guard_trigger
    before insert on public.message_reactions
    for each row execute function public.message_reactions_guard();

-- Memberships are immutable with respect to identity: a user can flip their own
-- pin / mute / read state, but can never move themselves into another
-- conversation or impersonate another member.
create or replace function public.conversation_members_guard()
returns trigger
language plpgsql
as $$
begin
    if new.conversation_id <> old.conversation_id or new.user_id <> old.user_id then
        raise exception 'conversation_id and user_id are immutable' using errcode = '42501';
    end if;
    return new;
end;
$$;

drop trigger if exists conversation_members_guard_trigger on public.conversation_members;
create trigger conversation_members_guard_trigger
    before update on public.conversation_members
    for each row execute function public.conversation_members_guard();

-- -----------------------------------------------------------------------------
-- RPC: get or create the direct conversation with another user
-- -----------------------------------------------------------------------------
create or replace function public.get_or_create_direct_conversation(p_other_user uuid)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    me       uuid := auth.uid();
    existing uuid;
begin
    if me is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;
    if p_other_user = me then
        raise exception 'you cannot start a conversation with yourself' using errcode = '22023';
    end if;
    if not exists (select 1 from public.profiles p where p.id = p_other_user) then
        raise exception 'user not found' using errcode = '22023';
    end if;
    if public.is_blocked_between(me, p_other_user) then
        raise exception 'this conversation is blocked' using errcode = '42501';
    end if;

    -- Serialize pair creation so two devices cannot create duplicates.
    perform pg_advisory_xact_lock(hashtext(least(me, p_other_user)::text || greatest(me, p_other_user)::text));

    select c.id into existing
    from public.conversations c
    join public.conversation_members m1 on m1.conversation_id = c.id and m1.user_id = me
    join public.conversation_members m2 on m2.conversation_id = c.id and m2.user_id = p_other_user
    where c.conversation_type = 'direct'
    limit 1;

    if existing is not null then
        return existing;
    end if;

    insert into public.conversations (conversation_type) values ('direct') returning id into existing;

    insert into public.conversation_members (conversation_id, user_id)
    values (existing, me), (existing, p_other_user);

    return existing;
end;
$$;

-- -----------------------------------------------------------------------------
-- RPC: mark a conversation as read
-- -----------------------------------------------------------------------------
create or replace function public.mark_conversation_read(p_conversation_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if not public.is_conversation_member(p_conversation_id) then
        raise exception 'not a conversation member' using errcode = '42501';
    end if;

    update public.conversation_members
       set last_read_at = now()
     where conversation_id = p_conversation_id
       and user_id = auth.uid();
end;
$$;

-- -----------------------------------------------------------------------------
-- RPC: presence heartbeat
-- -----------------------------------------------------------------------------
create or replace function public.heartbeat()
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.profiles set last_seen_at = now() where id = auth.uid();
end;
$$;

-- -----------------------------------------------------------------------------
-- RPC: register / refresh an FCM device token
-- -----------------------------------------------------------------------------
create or replace function public.register_device_token(p_token text, p_platform text default 'android')
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;
    if p_token is null or char_length(p_token) < 10 then
        raise exception 'invalid token' using errcode = '22023';
    end if;

    insert into public.device_tokens (user_id, fcm_token, platform)
    values (auth.uid(), p_token, coalesce(p_platform, 'android'))
    on conflict (fcm_token) do update
        set user_id = excluded.user_id,
            platform = excluded.platform,
            updated_at = now();
end;
$$;

-- -----------------------------------------------------------------------------
-- Home screen view (RLS is enforced through security_invoker)
-- -----------------------------------------------------------------------------
drop view if exists public.conversation_overview;

create view public.conversation_overview
with (security_invoker = true) as
select
    c.id                as conversation_id,
    c.conversation_type,
    c.created_at        as conversation_created_at,
    c.updated_at        as conversation_updated_at,
    me.user_id          as my_id,
    me.last_read_at,
    me.is_muted,
    me.is_pinned,
    peer.id             as peer_id,
    peer.display_name   as peer_display_name,
    peer.username       as peer_username,
    peer.avatar_url     as peer_avatar_url,
    peer.last_seen_at   as peer_last_seen_at,
    last_message.id        as last_message_id,
    last_message.content   as last_message_content,
    last_message.sender_id as last_message_sender_id,
    last_message.created_at as last_message_at,
    last_message.is_deleted as last_message_is_deleted,
    (
        select count(*)
        from public.messages m
        where m.conversation_id = c.id
          and m.sender_id <> me.user_id
          and m.created_at > coalesce(me.last_read_at, 'epoch'::timestamptz)
    ) as unread_count
from public.conversations c
join public.conversation_members me
  on me.conversation_id = c.id
 and me.user_id = auth.uid()
left join lateral (
    select m.user_id
    from public.conversation_members m
    where m.conversation_id = c.id
      and m.user_id <> me.user_id
    order by m.joined_at
    limit 1
) peer_member on true
left join public.profiles peer on peer.id = peer_member.user_id
left join lateral (
    select msg.id, msg.content, msg.sender_id, msg.created_at, msg.is_deleted
    from public.messages msg
    where msg.conversation_id = c.id
    order by msg.created_at desc
    limit 1
) last_message on true;

grant select on public.conversation_overview to authenticated;

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------
alter table public.profiles            enable row level security;
alter table public.conversations       enable row level security;
alter table public.conversation_members enable row level security;
alter table public.messages            enable row level security;
alter table public.message_reactions   enable row level security;
alter table public.user_blocks         enable row level security;
alter table public.user_reports        enable row level security;
alter table public.device_tokens       enable row level security;

-- profiles --------------------------------------------------------------------
drop policy if exists "profiles are readable by signed in users" on public.profiles;
create policy "profiles are readable by signed in users"
    on public.profiles for select
    to authenticated
    using (true);

drop policy if exists "users insert their own profile" on public.profiles;
create policy "users insert their own profile"
    on public.profiles for insert
    to authenticated
    with check (id = auth.uid());

drop policy if exists "users update their own profile" on public.profiles;
create policy "users update their own profile"
    on public.profiles for update
    to authenticated
    using (id = auth.uid())
    with check (id = auth.uid());

drop policy if exists "users delete their own profile" on public.profiles;
create policy "users delete their own profile"
    on public.profiles for delete
    to authenticated
    using (id = auth.uid());

-- conversations ---------------------------------------------------------------
drop policy if exists "members read their conversations" on public.conversations;
create policy "members read their conversations"
    on public.conversations for select
    to authenticated
    using (public.is_conversation_member(id));

-- Writes happen exclusively through get_or_create_direct_conversation().
revoke insert, update, delete on public.conversations from authenticated, anon;

-- conversation_members --------------------------------------------------------
drop policy if exists "members read conversation members" on public.conversation_members;
create policy "members read conversation members"
    on public.conversation_members for select
    to authenticated
    using (public.is_conversation_member(conversation_id));

drop policy if exists "members update their own membership" on public.conversation_members;
create policy "members update their own membership"
    on public.conversation_members for update
    to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

revoke insert, delete on public.conversation_members from authenticated, anon;

-- messages --------------------------------------------------------------------
drop policy if exists "members read messages" on public.messages;
create policy "members read messages"
    on public.messages for select
    to authenticated
    using (public.is_conversation_member(conversation_id));

drop policy if exists "members send their own messages" on public.messages;
create policy "members send their own messages"
    on public.messages for insert
    to authenticated
    with check (
        sender_id = auth.uid()
        and public.is_conversation_member(conversation_id)
    );

drop policy if exists "senders edit their own messages" on public.messages;
create policy "senders edit their own messages"
    on public.messages for update
    to authenticated
    using (sender_id = auth.uid())
    with check (
        sender_id = auth.uid()
        and public.is_conversation_member(conversation_id)
    );

drop policy if exists "senders delete their own messages" on public.messages;
create policy "senders delete their own messages"
    on public.messages for delete
    to authenticated
    using (sender_id = auth.uid());

-- message_reactions -----------------------------------------------------------
drop policy if exists "members read reactions" on public.message_reactions;
create policy "members read reactions"
    on public.message_reactions for select
    to authenticated
    using (
        exists (
            select 1 from public.messages m
            where m.id = message_reactions.message_id
              and public.is_conversation_member(m.conversation_id)
        )
    );

drop policy if exists "members add their own reactions" on public.message_reactions;
create policy "members add their own reactions"
    on public.message_reactions for insert
    to authenticated
    with check (user_id = auth.uid());

drop policy if exists "members remove their own reactions" on public.message_reactions;
create policy "members remove their own reactions"
    on public.message_reactions for delete
    to authenticated
    using (user_id = auth.uid());

-- user_blocks -----------------------------------------------------------------
drop policy if exists "users read their own blocks" on public.user_blocks;
create policy "users read their own blocks"
    on public.user_blocks for select
    to authenticated
    using (blocker_id = auth.uid());

drop policy if exists "users create their own blocks" on public.user_blocks;
create policy "users create their own blocks"
    on public.user_blocks for insert
    to authenticated
    with check (blocker_id = auth.uid() and blocked_id <> auth.uid());

drop policy if exists "users delete their own blocks" on public.user_blocks;
create policy "users delete their own blocks"
    on public.user_blocks for delete
    to authenticated
    using (blocker_id = auth.uid());

-- user_reports ----------------------------------------------------------------
drop policy if exists "users create their own reports" on public.user_reports;
create policy "users create their own reports"
    on public.user_reports for insert
    to authenticated
    with check (reporter_id = auth.uid() and reported_user_id <> auth.uid());

drop policy if exists "users read their own reports" on public.user_reports;
create policy "users read their own reports"
    on public.user_reports for select
    to authenticated
    using (reporter_id = auth.uid());

-- device_tokens ---------------------------------------------------------------
drop policy if exists "users manage their own device tokens" on public.device_tokens;
create policy "users manage their own device tokens"
    on public.device_tokens for all
    to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

-- -----------------------------------------------------------------------------
-- Storage: avatars bucket
-- -----------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('avatars', 'avatars', true, 5242880, array['image/png', 'image/jpeg', 'image/webp'])
on conflict (id) do update
    set public = true,
        file_size_limit = 5242880,
        allowed_mime_types = array['image/png', 'image/jpeg', 'image/webp'];

drop policy if exists "avatars are publicly readable" on storage.objects;
create policy "avatars are publicly readable"
    on storage.objects for select
    to public
    using (bucket_id = 'avatars');

drop policy if exists "users upload their own avatar" on storage.objects;
create policy "users upload their own avatar"
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "users update their own avatar" on storage.objects;
create policy "users update their own avatar"
    on storage.objects for update
    to authenticated
    using (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    )
    with check (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "users delete their own avatar" on storage.objects;
create policy "users delete their own avatar"
    on storage.objects for delete
    to authenticated
    using (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = auth.uid()::text
    );

-- -----------------------------------------------------------------------------
-- Realtime
-- -----------------------------------------------------------------------------
do $$
begin
    begin
        alter publication supabase_realtime add table public.messages;
    exception when duplicate_object then null;
    end;
    begin
        alter publication supabase_realtime add table public.message_reactions;
    exception when duplicate_object then null;
    end;
    begin
        alter publication supabase_realtime add table public.conversation_members;
    exception when duplicate_object then null;
    end;
    begin
        alter publication supabase_realtime add table public.conversations;
    exception when duplicate_object then null;
    end;
    begin
        alter publication supabase_realtime add table public.profiles;
    exception when duplicate_object then null;
    end;
end;
$$;

-- DELETE / UPDATE events must carry the full old row so clients can react.
alter table public.messages replica identity full;
alter table public.message_reactions replica identity full;
alter table public.conversation_members replica identity full;

-- -----------------------------------------------------------------------------
-- Grants
-- -----------------------------------------------------------------------------
grant usage on schema public to anon, authenticated;
grant select, insert, update, delete on public.profiles            to authenticated;
grant select, insert, update, delete on public.messages            to authenticated;
grant select, insert, update, delete on public.message_reactions   to authenticated;
grant select, insert, delete         on public.user_blocks         to authenticated;
grant select, insert                 on public.user_reports        to authenticated;
grant select, insert, update, delete on public.device_tokens       to authenticated;
grant select                         on public.conversations       to authenticated;
grant select, update                 on public.conversation_members to authenticated;

grant execute on function public.get_or_create_direct_conversation(uuid) to authenticated;
grant execute on function public.mark_conversation_read(uuid) to authenticated;
grant execute on function public.heartbeat() to authenticated;
grant execute on function public.register_device_token(text, text) to authenticated;
