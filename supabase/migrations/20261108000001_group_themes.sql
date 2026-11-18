-- Group chats: per-conversation look (wallpaper, bubble colour), and read receipts.
--
-- Everything here is additive: existing conversations simply keep NULLs and render with the
-- app's default theme.

alter table public.conversations add column if not exists wallpaper text;
alter table public.conversations add column if not exists bubble_color text;
alter table public.conversations add column if not exists accent_color text;

-- Theme + membership summary for one conversation.
create or replace function public.conversation_theme(p_conversation_id uuid)
returns table(
    title text,
    description text,
    conversation_type text,
    wallpaper text,
    bubble_color text,
    accent_color text,
    member_count integer,
    my_role text
)
language sql
stable
security definer
set search_path = public
as $$
    select c.title,
           c.description,
           c.conversation_type,
           c.wallpaper,
           c.bubble_color,
           c.accent_color,
           (select count(*)::int
              from public.conversation_members m
             where m.conversation_id = c.id),
           (select cm.role::text
              from public.conversation_members cm
             where cm.conversation_id = c.id and cm.user_id = auth.uid()
             limit 1)
      from public.conversations c
     where c.id = p_conversation_id
       and public.is_conversation_member(p_conversation_id);
$$;

-- Any member may restyle the chat they are in (NULL keeps the current value, '' clears it).
create or replace function public.update_conversation_theme(
    p_conversation_id uuid,
    p_wallpaper text default null,
    p_bubble_color text default null,
    p_accent_color text default null,
    p_title text default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if not public.is_conversation_member(p_conversation_id) then
        raise exception 'not_a_member' using errcode = '42501';
    end if;

    update public.conversations
       set wallpaper = coalesce(p_wallpaper, wallpaper),
           bubble_color = coalesce(p_bubble_color, bubble_color),
           accent_color = coalesce(p_accent_color, accent_color),
           title = coalesce(nullif(btrim(p_title), ''), title),
           updated_at = now()
     where id = p_conversation_id;
end;
$$;

-- Read cursors for every member — the "seen by" panel.
create or replace function public.conversation_receipts(p_conversation_id uuid)
returns table(
    user_id uuid,
    display_name text,
    username text,
    avatar_url text,
    last_read_at timestamptz,
    member_role text
)
language sql
stable
security definer
set search_path = public
as $$
    select cm.user_id,
           p.display_name,
           p.username,
           p.avatar_url,
           cm.last_read_at,
           cm.role::text
      from public.conversation_members cm
      left join public.profiles p on p.id = cm.user_id
     where cm.conversation_id = p_conversation_id
       and public.is_conversation_member(p_conversation_id);
$$;

grant execute on function public.conversation_theme(uuid) to authenticated;
grant execute on function public.update_conversation_theme(uuid, text, text, text, text) to authenticated;
grant execute on function public.conversation_receipts(uuid) to authenticated;
