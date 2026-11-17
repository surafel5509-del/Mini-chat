-- Voice and video calling: call registry, RLS, Realtime and the signalling RPCs.
--
-- Design: Postgres is the *state machine* for a call (ringing → active → ended/declined/missed),
-- LiveKit carries the media. Every client transition goes through a SECURITY DEFINER function so
-- the column rules cannot be bypassed, and both phones learn about changes over Realtime.

create table if not exists public.calls (
    id               uuid primary key default gen_random_uuid(),
    conversation_id  uuid not null references public.conversations(id) on delete cascade,
    caller_id        uuid not null references public.profiles(id) on delete cascade,
    callee_id        uuid not null references public.profiles(id) on delete cascade,
    call_type        text not null default 'voice' check (call_type in ('voice', 'video')),
    status           text not null default 'ringing' check (status in (
                         'ringing', 'active', 'ended', 'declined', 'missed', 'cancelled', 'failed'
                     )),
    room_name        text not null,
    created_at       timestamptz not null default now(),
    answered_at      timestamptz,
    ended_at         timestamptz,
    duration_seconds integer,
    ended_by         uuid references public.profiles(id) on delete set null,
    failure_reason   text,
    constraint calls_distinct_parties check (caller_id <> callee_id)
);

create index if not exists calls_callee_status_idx on public.calls (callee_id, status, created_at desc);
create index if not exists calls_caller_status_idx on public.calls (caller_id, status, created_at desc);
create index if not exists calls_conversation_idx on public.calls (conversation_id, created_at desc);

alter table public.calls enable row level security;

drop policy if exists calls_select_participants on public.calls;
create policy calls_select_participants on public.calls
    for select using (auth.uid() = caller_id or auth.uid() = callee_id);

drop policy if exists calls_insert_caller on public.calls;
create policy calls_insert_caller on public.calls
    for insert with check (auth.uid() = caller_id);

drop policy if exists calls_update_participants on public.calls;
create policy calls_update_participants on public.calls
    for update using (auth.uid() = caller_id or auth.uid() = callee_id)
    with check (auth.uid() = caller_id or auth.uid() = callee_id);

-- Full row payloads in Realtime updates (the client needs status + timestamps every time).
alter table public.calls replica identity full;

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
         where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'calls'
    ) then
        alter publication supabase_realtime add table public.calls;
    end if;
end $$;

-- ---------------------------------------------------------------------------
-- RPCs
-- ---------------------------------------------------------------------------

create or replace function public.start_call(p_conversation_id uuid, p_call_type text default 'voice')
returns public.calls
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_me uuid := auth.uid();
  v_peer uuid;
  v_call public.calls;
begin
  if v_me is null then
    raise exception 'Not authenticated' using errcode = '28000';
  end if;
  if p_call_type not in ('voice','video') then
    raise exception 'Unsupported call type' using errcode = '22023';
  end if;
  if not exists (
    select 1 from public.conversation_members
     where conversation_id = p_conversation_id and user_id = v_me
  ) then
    raise exception 'You are not part of this conversation' using errcode = '42501';
  end if;

  select user_id into v_peer
    from public.conversation_members
   where conversation_id = p_conversation_id and user_id <> v_me
   limit 1;

  if v_peer is null then
    raise exception 'This conversation has no other member' using errcode = 'P0002';
  end if;
  if exists (select 1 from public.user_blocks where blocker_id = v_peer and blocked_id = v_me) then
    raise exception 'You cannot call this user' using errcode = '42501';
  end if;

  perform public.expire_stale_calls();

  -- A new call supersedes anything the caller was still ringing.
  update public.calls
     set status = 'cancelled', ended_at = now(), ended_by = v_me
   where caller_id = v_me and status = 'ringing';

  insert into public.calls (conversation_id, caller_id, callee_id, call_type, room_name, status)
  values (
    p_conversation_id,
    v_me,
    v_peer,
    p_call_type,
    'minichat-' || replace(gen_random_uuid()::text, '-', ''),
    'ringing'
  )
  returning * into v_call;

  return v_call;
end $$;

create or replace function public.answer_call(p_call_id uuid)
returns public.calls
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_me uuid := auth.uid();
  v_call public.calls;
begin
  if v_me is null then
    raise exception 'Not authenticated' using errcode = '28000';
  end if;

  update public.calls
     set status = 'active',
         answered_at = coalesce(answered_at, now())
   where id = p_call_id
     and callee_id = v_me
     and status = 'ringing'
  returning * into v_call;

  if v_call.id is null then
    raise exception 'This call is no longer ringing' using errcode = 'P0001';
  end if;

  -- A callee can only be in one call at a time.
  update public.calls
     set status = 'cancelled', ended_at = now(), ended_by = v_me
   where id <> v_call.id
     and status = 'ringing'
     and (callee_id = v_me or caller_id = v_me);

  return v_call;
end $$;

create or replace function public.decline_call(p_call_id uuid, p_reason text default 'declined')
returns public.calls
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_me uuid := auth.uid();
  v_call public.calls;
begin
  if v_me is null then
    raise exception 'Not authenticated' using errcode = '28000';
  end if;

  update public.calls
     set status = 'declined',
         ended_at = now(),
         ended_by = v_me,
         failure_reason = p_reason
   where id = p_call_id
     and callee_id = v_me
     and status = 'ringing'
  returning * into v_call;

  if v_call.id is null then
    select * into v_call from public.calls where id = p_call_id;
  end if;

  return v_call;
end $$;

create or replace function public.end_call(p_call_id uuid)
returns public.calls
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_me uuid := auth.uid();
  v_call public.calls;
  v_duration integer;
begin
  if v_me is null then
    raise exception 'Not authenticated' using errcode = '28000';
  end if;

  select * into v_call from public.calls where id = p_call_id for update;

  if v_call.id is null then
    raise exception 'Call not found' using errcode = 'P0002';
  end if;
  if v_me <> v_call.caller_id and v_me <> v_call.callee_id then
    raise exception 'You are not part of this call' using errcode = '42501';
  end if;
  if v_call.status in ('ended','declined','missed','cancelled','failed') then
    return v_call;
  end if;

  v_duration := case
    when v_call.answered_at is not null
      then greatest(0, extract(epoch from (now() - v_call.answered_at))::integer)
    else null
  end;

  update public.calls
     set status = case
           when v_call.status = 'ringing' and v_me = v_call.caller_id then 'cancelled'
           when v_call.status = 'ringing' and v_me = v_call.callee_id then 'declined'
           else 'ended'
         end,
         ended_at = now(),
         ended_by = v_me,
         duration_seconds = v_duration
   where id = p_call_id
  returning * into v_call;

  return v_call;
end $$;

create or replace function public.active_call()
returns public.calls
language sql
security definer
set search_path to 'public'
as $$
  select *
    from public.calls
   where (callee_id = auth.uid() or caller_id = auth.uid())
     and status in ('ringing','active')
   order by created_at desc
   limit 1;
$$;

create or replace function public.call_history(p_limit integer default 50, p_offset integer default 0)
returns table (
    id uuid,
    conversation_id uuid,
    caller_id uuid,
    callee_id uuid,
    call_type text,
    status text,
    created_at timestamptz,
    answered_at timestamptz,
    ended_at timestamptz,
    duration_seconds integer,
    is_outgoing boolean,
    peer_id uuid,
    peer_display_name text,
    peer_username text,
    peer_avatar_url text
)
language sql
security definer
set search_path to 'public'
as $$
  select c.id,
         c.conversation_id,
         c.caller_id,
         c.callee_id,
         c.call_type,
         c.status,
         c.created_at,
         c.answered_at,
         c.ended_at,
         c.duration_seconds,
         (c.caller_id = auth.uid()) as is_outgoing,
         p.id,
         p.display_name,
         p.username,
         p.avatar_url
    from public.calls c
    join public.profiles p
      on p.id = case when c.caller_id = auth.uid() then c.callee_id else c.caller_id end
   where c.caller_id = auth.uid() or c.callee_id = auth.uid()
   order by c.created_at desc
   limit greatest(1, least(coalesce(p_limit, 50), 200))
   offset greatest(0, coalesce(p_offset, 0));
$$;

create or replace function public.expire_stale_calls()
returns integer
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_count integer;
begin
  with stale as (
    update public.calls
       set status = 'missed',
           ended_at = now(),
           failure_reason = coalesce(failure_reason, 'no_answer')
     where status = 'ringing'
       and created_at < now() - interval '45 seconds'
    returning 1
  )
  select count(*) into v_count from stale;
  return coalesce(v_count, 0);
end $$;

grant execute on function public.start_call(uuid, text) to authenticated;
grant execute on function public.answer_call(uuid) to authenticated;
grant execute on function public.decline_call(uuid, text) to authenticated;
grant execute on function public.end_call(uuid) to authenticated;
grant execute on function public.active_call() to authenticated;
grant execute on function public.call_history(integer, integer) to authenticated;
grant execute on function public.expire_stale_calls() to authenticated;
