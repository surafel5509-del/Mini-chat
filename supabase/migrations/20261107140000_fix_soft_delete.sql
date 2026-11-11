-- =============================================================================
-- MINI CHAT — 004
-- Fix: deleting a message always failed.
--
-- `messages_guard()` implements soft delete by blanking `content` so a deleted
-- message's text is never retained. But `messages_content_length` required
-- char_length(content) between 1 and 4000, so every delete raised:
--
--   new row for relation "messages" violates check constraint
--   "messages_content_length"
--
-- The two rules contradicted each other. Deleted rows are the only ones allowed
-- to be empty; anything the user can read must still be 1–4000 characters, and
-- the guard already enforces that on insert and on edit.
-- =============================================================================

do $$
begin
    alter table public.messages drop constraint if exists messages_content_length;

    alter table public.messages
        add constraint messages_content_length
        check (is_deleted or char_length(content) between 1 and 4000);
end;
$$;

-- Make the intent explicit and keep the guard authoritative for live messages:
-- a non-deleted row can never be stored empty, and a deleted row is always empty.
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

    -- UPDATE
    if new.sender_id <> old.sender_id or new.conversation_id <> old.conversation_id then
        raise exception 'sender_id and conversation_id are immutable' using errcode = '42501';
    end if;

    if new.is_deleted and not old.is_deleted then
        -- Soft delete: the text is discarded immediately and never stored again.
        new.content := '';
        new.is_edited := old.is_edited;
        new.updated_at := now();
        return new;
    end if;

    if new.is_deleted then
        -- Already deleted: content stays empty regardless of what was sent.
        new.content := '';
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

do $$
begin
    perform pg_notify('pgrst', 'reload schema');
exception when others then null;
end;
$$;
