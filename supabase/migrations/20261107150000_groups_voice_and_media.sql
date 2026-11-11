-- =============================================================================
-- MINI CHAT — 005
--   * Group conversations: owner / admin / member roles, group metadata,
--     membership management, leave and delete.
--   * Voice messages: message types, duration, private chat-media bucket.
--   * System messages ("Ada added Sam") for a real group history.
--   * Group-aware conversation_overview so one query still powers the home list.
--
-- Security model
-- --------------
-- Group membership changes are NOT granted directly to clients (insert/delete on
-- conversation_members stays revoked). Every mutation goes through a function
-- that first proves the caller's role inside the conversation, then acts. Being
-- SECURITY DEFINER is what lets the write succeed at all — the authorization
-- happens *inside* the function and is re-checked by RLS on every read.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Schema
-- -----------------------------------------------------------------------------
alter table public.conversations
    add column if not exists title       text,
    add column if not exists avatar_url  text,
    add column if not exists description text,
    add column if not exists created_by  uuid references public.profiles (id) on delete set null;

alter table public.conversations drop constraint if exists conversations_title_length;
alter table public.conversations
    add constraint conversations_title_length
    check (title is null or char_length(btrim(title)) between 1 and 80);

alter table public.conversations drop constraint if exists conversations_description_length;
alter table public.conversations
    add constraint conversations_description_length
    check (description is null or char_length(description) <= 240);

alter table public.conversation_members
    add column if not exists role text not null default 'member';

alter table public.conversation_members drop constraint if exists conversation_members_role_check;
alter table public.conversation_members
    add constraint conversation_members_role_check
    check (role in ('owner', 'admin', 'member'));

-- Only one owner per conversation.
create unique index if not exists conversation_members_single_owner
    on public.conversation_members (conversation_id)
    where role = 'owner';

alter table public.messages
    add column if not exists message_type text not null default 'text',
    add column if not exists media_url    text,
    add column if not exists duration_ms  integer;

alter table public.messages drop constraint if exists messages_type_check;
alter table public.messages
    add constraint messages_type_check
    check (message_type in ('text', 'voice', 'image', 'system'));

alter table public.messages drop constraint if exists messages_duration_check;
alter table public.messages
    add constraint messages_duration_check
    check (duration_ms is null or duration_ms between 0 and 60000);

-- Voice / image messages carry no text body.
alter table public.messages drop constraint if exists messages_content_length;
alter table public.messages
    add constraint messages_content_length
    check (
        is_deleted
        or (message_type = 'text'   and char_length(content) between 1 and 4000)
        or (message_type = 'system' and char_length(content) between 1 and 400)
        or (message_type in ('voice', 'image') and char_length(content) <= 400)
    );

-- A voice message must reference an uploaded object and declare its length.
alter table public.messages drop constraint if exists messages_voice_shape;
alter table public.messages
    add constraint messages_voice_shape
    check (
        message_type <> 'voice'
        or (media_url is not null and char_length(media_url) between 1 and 512 and duration_ms is not null)
    );

create index if not exists messages_conversation_created_asc_idx
    on public.messages (conversation_id, created_at);

-- -----------------------------------------------------------------------------
-- 2. Role helpers
-- -----------------------------------------------------------------------------
create or replace function public.conversation_role(p_conversation_id uuid, p_user_id uuid default auth.uid())
returns text
language sql
stable
security definer
set search_path = public
as $$
    select m.role
    from public.conversation_members m
    where m.conversation_id = p_conversation_id
      and m.user_id = p_user_id;
$$;

create or replace function public.is_conversation_admin(p_conversation_id uuid, p_user_id uuid default auth.uid())
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select coalesce(
        (select m.role in ('owner', 'admin')
         from public.conversation_members m
         where m.conversation_id = p_conversation_id
           and m.user_id = p_user_id),
        false
    );
$$;

create or replace function public.is_conversation_owner(p_conversation_id uuid, p_user_id uuid default auth.uid())
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select coalesce(
        (select m.role = 'owner'
         from public.conversation_members m
         where m.conversation_id = p_conversation_id
           and m.user_id = p_user_id),
        false
    );
$$;

grant execute on function public.conversation_role(uuid, uuid) to authenticated;
grant execute on function public.is_conversation_admin(uuid, uuid) to authenticated;
grant execute on function public.is_conversation_owner(uuid, uuid) to authenticated;

-- -----------------------------------------------------------------------------
-- 3. Message guard — now type aware
-- -----------------------------------------------------------------------------
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
        if new.is_deleted then
            raise exception 'a new message cannot start deleted' using errcode = '22000';
        end if;

        if not public.is_conversation_member(new.conversation_id, new.sender_id) then
            raise exception 'sender is not a member of this conversation' using errcode = '42501';
        end if;

        -- Only the server writes system messages.
        if new.message_type = 'system' then
            raise exception 'system messages are written by the server' using errcode = '42501';
        end if;

        -- A member who was blocked in this conversation cannot post.
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

        if new.message_type = 'text' then
            new.content := btrim(new.content);
            if char_length(new.content) = 0 then
                raise exception 'message content cannot be empty' using errcode = '22000';
            end if;
        end if;

        new.is_edited := false;
        new.created_at := now();
        new.updated_at := now();

        update public.conversation_members m
           set last_read_at = now()
         where m.conversation_id = new.conversation_id
           and m.user_id = new.sender_id;

        update public.conversations c
           set updated_at = now()
         where c.id = new.conversation_id;

        return new;
    end if;

    if new.sender_id <> old.sender_id or new.conversation_id <> old.conversation_id then
        raise exception 'sender_id and conversation_id are immutable' using errcode = '42501';
    end if;

    if new.message_type <> old.message_type then
        raise exception 'message_type is immutable' using errcode = '42501';
    end if;

    if new.is_deleted and not old.is_deleted then
        new.content := '';
        new.is_edited := old.is_edited;
        new.updated_at := now();
        return new;
    end if;

    if new.is_deleted then
        new.content := '';
        new.updated_at := now();
        return new;
    end if;

    if new.message_type = 'text' and new.content is distinct from old.content then
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

-- Direct insert of system messages stays impossible for clients.
revoke insert on public.messages from authenticated;

-- Column-level grant: everything except the columns the server owns.
grant insert (
    conversation_id, sender_id, content, reply_to_id,
    message_type, media_url, duration_ms
) on public.messages to authenticated;

grant update (content, is_deleted) on public.messages to authenticated;

-- -----------------------------------------------------------------------------
-- 4. System message helper (server side only)
-- -----------------------------------------------------------------------------
create or replace function public.post_system_message(p_conversation_id uuid, p_body text)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.messages (conversation_id, sender_id, content, message_type)
    select p_conversation_id, m.user_id, left(p_body, 400), 'system'
    from public.conversation_members m
    where m.conversation_id = p_conversation_id
      and m.role in ('owner', 'admin')
      and m.user_id = auth.uid()
    limit 1;

    if not found then
        insert into public.messages (conversation_id, sender_id, content, message_type)
        select p_conversation_id, p.creator, left(p_body, 400), 'system'
        from public.conversations c, lateral (select coalesce(c.created_by, auth.uid()) as creator) p
        where c.id = p_conversation_id;
    end if;
end;
$$;

revoke execute on function public.post_system_message(uuid, text) from authenticated;

-- -----------------------------------------------------------------------------
-- 5. Group RPCs
-- -----------------------------------------------------------------------------
create or replace function public.create_group(
    p_title       text,
    p_member_ids  uuid[],
    p_description text default null
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    me        uuid := auth.uid();
    clean     text;
    conv      uuid;
    member    uuid;
    added     int := 0;
begin
    if me is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    clean := btrim(coalesce(p_title, ''));
    if char_length(clean) < 1 or char_length(clean) > 80 then
        raise exception 'group name must be 1-80 characters' using errcode = '22023';
    end if;

    -- Cap the initial roster; adding more is always possible later.
    if coalesce(array_length(p_member_ids, 1), 0) > 100 then
        raise exception 'a group can start with at most 100 members' using errcode = '22023';
    end if;

    insert into public.conversations (conversation_type, title, description, created_by)
    values ('group', clean, nullif(btrim(coalesce(p_description, '')), ''), me)
    returning id into conv;

    -- The creator is the owner and implicitly a member.
    insert into public.conversation_members (conversation_id, user_id, role)
    values (conv, me, 'owner');

    foreach member in array coalesce(p_member_ids, array[]::uuid[]) loop
        continue when member = me;
        continue when not exists (select 1 from public.profiles p where p.id = member);
        continue when public.is_blocked_between(me, member);

        insert into public.conversation_members (conversation_id, user_id, role)
        values (conv, member, 'member')
        on conflict (conversation_id, user_id) do nothing;
        added := added + 1;
    end loop;

    if added > 0 then
        insert into public.messages (conversation_id, sender_id, content, message_type)
        values (conv, me, 'created the group', 'system');
    end if;

    return conv;
end;
$$;

create or replace function public.add_group_members(p_conversation_id uuid, p_user_ids uuid[])
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    me      uuid := auth.uid();
    member  uuid;
    added   int := 0;
    is_group boolean;
begin
    select c.conversation_type = 'group' into is_group
    from public.conversations c where c.id = p_conversation_id;

    if not coalesce(is_group, false) then
        raise exception 'not a group conversation' using errcode = '22023';
    end if;

    if not public.is_conversation_member(p_conversation_id, me) then
        raise exception 'not a conversation member' using errcode = '42501';
    end if;

    foreach member in array coalesce(p_user_ids, array[]::uuid[]) loop
        continue when member = me;
        continue when not exists (select 1 from public.profiles p where p.id = member);
        continue when exists (
            select 1 from public.conversation_members m
            where m.conversation_id = p_conversation_id and m.user_id = member
        );

        insert into public.conversation_members (conversation_id, user_id, role)
        values (p_conversation_id, member, 'member');
        added := added + 1;
    end loop;

    if added > 0 then
        insert into public.messages (conversation_id, sender_id, content, message_type)
        values (p_conversation_id, me,
                format('added %s %s', added, case when added = 1 then 'member' else 'members' end),
                'system');
    end if;

    return added;
end;
$$;

create or replace function public.remove_group_member(p_conversation_id uuid, p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    me      uuid := auth.uid();
    my_role text;
    target  text;
begin
    if me = p_user_id then
        raise exception 'use leave_group to remove yourself' using errcode = '22023';
    end if;

    my_role := public.conversation_role(p_conversation_id, me);
    if my_role not in ('owner', 'admin') then
        raise exception 'only admins can remove members' using errcode = '42501';
    end if;

    target := public.conversation_role(p_conversation_id, p_user_id);
    if target is null then
        return;
    end if;

    -- Admins cannot remove the owner or each other; only the owner can.
    if target = 'owner' then
        raise exception 'the group owner cannot be removed' using errcode = '42501';
    end if;
    if target = 'admin' and my_role <> 'owner' then
        raise exception 'only the owner can remove an admin' using errcode = '42501';
    end if;

    delete from public.conversation_members
    where conversation_id = p_conversation_id and user_id = p_user_id;

    insert into public.messages (conversation_id, sender_id, content, message_type)
    values (p_conversation_id, me, 'removed a member', 'system');
end;
$$;

create or replace function public.set_group_member_role(p_conversation_id uuid, p_user_id uuid, p_role text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    me uuid := auth.uid();
begin
    if p_role not in ('admin', 'member') then
        raise exception 'role must be admin or member' using errcode = '22023';
    end if;

    if not public.is_conversation_owner(p_conversation_id, me) then
        raise exception 'only the owner can change roles' using errcode = '42501';
    end if;

    if p_user_id = me then
        raise exception 'the owner cannot change their own role' using errcode = '22023';
    end if;

    if public.conversation_role(p_conversation_id, p_user_id) is null then
        raise exception 'that person is not in this group' using errcode = '22023';
    end if;

    update public.conversation_members
       set role = p_role
     where conversation_id = p_conversation_id
       and user_id = p_user_id;

    insert into public.messages (conversation_id, sender_id, content, message_type)
    values (
        p_conversation_id,
        me,
        case when p_role = 'admin' then 'made someone an admin' else 'removed admin rights' end,
        'system'
    );
end;
$$;

create or replace function public.update_group(
    p_conversation_id uuid,
    p_title       text default null,
    p_description text default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    clean text;
begin
    if not public.is_conversation_admin(p_conversation_id, auth.uid()) then
        raise exception 'only admins can edit this group' using errcode = '42501';
    end if;

    clean := btrim(coalesce(p_title, ''));
    if p_title is not null and (char_length(clean) < 1 or char_length(clean) > 80) then
        raise exception 'group name must be 1-80 characters' using errcode = '22023';
    end if;

    update public.conversations
       set title       = coalesce(nullif(clean, ''), title),
           description = case when p_description is null then description
                              else nullif(btrim(p_description), '') end,
           updated_at  = now()
     where id = p_conversation_id;
end;
$$;

create or replace function public.leave_group(p_conversation_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    me        uuid := auth.uid();
    my_role   text;
    survivors int;
    heir      uuid;
begin
    my_role := public.conversation_role(p_conversation_id, me);
    if my_role is null then
        return;
    end if;

    delete from public.conversation_members
    where conversation_id = p_conversation_id and user_id = me;

    select count(*) into survivors
    from public.conversation_members m
    where m.conversation_id = p_conversation_id;

    -- An empty group is deleted so no orphan conversations linger.
    if survivors = 0 then
        delete from public.conversations where id = p_conversation_id;
        return;
    end if;

    -- The owner leaving hands ownership to the longest-standing admin or member.
    if my_role = 'owner' then
        select m.user_id into heir
        from public.conversation_members m
        where m.conversation_id = p_conversation_id
        order by (m.role = 'admin') desc, m.joined_at
        limit 1;

        if heir is not null then
            update public.conversation_members
               set role = 'owner'
             where conversation_id = p_conversation_id and user_id = heir;
        end if;
    end if;

    insert into public.messages (conversation_id, sender_id, content, message_type)
    select p_conversation_id, m.user_id, 'left the group', 'system'
    from public.conversation_members m
    where m.conversation_id = p_conversation_id
    order by m.joined_at
    limit 1;
end;
$$;

create or replace function public.delete_group(p_conversation_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if not public.is_conversation_owner(p_conversation_id, auth.uid()) then
        raise exception 'only the owner can delete this group' using errcode = '42501';
    end if;

    delete from public.conversations where id = p_conversation_id;
end;
$$;

grant execute on function public.create_group(text, uuid[], text)             to authenticated;
grant execute on function public.add_group_members(uuid, uuid[])               to authenticated;
grant execute on function public.remove_group_member(uuid, uuid)               to authenticated;
grant execute on function public.set_group_member_role(uuid, uuid, text)       to authenticated;
grant execute on function public.update_group(uuid, text, text)                to authenticated;
grant execute on function public.leave_group(uuid)                             to authenticated;
grant execute on function public.delete_group(uuid)                            to authenticated;

-- Writes to group metadata go through update_group only.
revoke update on public.conversations from authenticated;
grant select on public.conversations to authenticated;

-- -----------------------------------------------------------------------------
-- 6. conversation_overview — one query still powers the whole home list
-- -----------------------------------------------------------------------------
drop view if exists public.conversation_overview;

create view public.conversation_overview
with (security_invoker = true) as
select
    c.id                    as conversation_id,
    c.conversation_type,
    c.title                 as conversation_title,
    c.avatar_url            as conversation_avatar_url,
    c.description           as conversation_description,
    c.created_at            as conversation_created_at,
    c.updated_at            as conversation_updated_at,
    me.user_id              as my_id,
    me.role                 as my_role,
    me.last_read_at,
    me.is_muted,
    me.is_pinned,
    (select count(*) from public.conversation_members cm where cm.conversation_id = c.id) as member_count,
    peer.id                 as peer_id,
    peer.display_name       as peer_display_name,
    peer.username           as peer_username,
    peer.avatar_url         as peer_avatar_url,
    peer.last_seen_at       as peer_last_seen_at,
    last_message.id         as last_message_id,
    last_message.content    as last_message_content,
    last_message.sender_id  as last_message_sender_id,
    last_message.created_at as last_message_at,
    last_message.is_deleted as last_message_is_deleted,
    last_message.message_type as last_message_type,
    last_message.duration_ms  as last_message_duration_ms,
    last_message_sender.display_name as last_message_sender_name,
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
    select msg.id, msg.content, msg.sender_id, msg.created_at, msg.is_deleted,
           msg.message_type, msg.duration_ms
    from public.messages msg
    where msg.conversation_id = c.id and msg.message_type <> 'system'
    order by msg.created_at desc
    limit 1
) last_message on true
left join public.profiles last_message_sender on last_message_sender.id = last_message.sender_id;

grant select on public.conversation_overview to authenticated;

-- -----------------------------------------------------------------------------
-- 7. Storage: private chat-media bucket for voice notes (and future media)
-- -----------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'chat-media', 'chat-media', false, 10485760,
    array['audio/mp4', 'audio/aac', 'audio/mpeg', 'audio/3gpp', 'audio/ogg', 'image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update
    set public = false,
        file_size_limit = 10485760,
        allowed_mime_types = excluded.allowed_mime_types;

-- Object path convention: <conversation_id>/<file>. Membership is checked
-- against the first path segment, so a leaked path is useless to an outsider.
drop policy if exists "members read their chat media" on storage.objects;
create policy "members read their chat media"
    on storage.objects for select
    to authenticated
    using (
        bucket_id = 'chat-media'
        and public.is_conversation_member(((storage.foldername(name))[1])::uuid)
    );

drop policy if exists "members upload chat media" on storage.objects;
create policy "members upload chat media"
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'chat-media'
        and public.is_conversation_member(((storage.foldername(name))[1])::uuid)
    );

drop policy if exists "uploaders delete their chat media" on storage.objects;
create policy "uploaders delete their chat media"
    on storage.objects for delete
    to authenticated
    using (
        bucket_id = 'chat-media'
        and owner = auth.uid()
        and public.is_conversation_member(((storage.foldername(name))[1])::uuid)
    );

-- -----------------------------------------------------------------------------
-- 8. Realtime: group metadata changes must reach the client
-- -----------------------------------------------------------------------------
do $$
begin
    begin
        alter publication supabase_realtime add table public.conversations;
    exception when duplicate_object then null;
    end;
end;
$$;

alter table public.conversations replica identity full;

do $$
begin
    perform pg_notify('pgrst', 'reload schema');
exception when others then null;
end;
$$;
