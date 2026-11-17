-- Multi-party (group) voice and video calls.
--
-- A group call is an ad-hoc LiveKit room: the host invites people by id, everyone else joins or
-- declines. Postgres remains the source of truth for who is in the call and when it ended; media
-- is carried by LiveKit and the token is minted by the `livekit-token` Edge Function.

create table if not exists public.group_calls (
    id               uuid primary key default gen_random_uuid(),
    host_id          uuid not null references public.profiles(id) on delete cascade,
    title            text not null default 'Group call',
    call_type        text not null default 'video' check (call_type in ('voice', 'video')),
    status           text not null default 'active' check (status in (
                         'ringing', 'active', 'ended', 'cancelled', 'failed'
                     )),
    room_name        text not null unique,
    max_participants integer not null default 16,
    created_at       timestamptz not null default now(),
    started_at       timestamptz,
    ended_at         timestamptz,
    duration_seconds integer,
    ended_by         uuid references public.profiles(id) on delete set null
);

create table if not exists public.group_call_participants (
    call_id    uuid not null references public.group_calls(id) on delete cascade,
    user_id    uuid not null references public.profiles(id) on delete cascade,
    status     text not null default 'invited' check (status in (
                   'invited', 'joined', 'left', 'declined', 'missed'
               )),
    invited_at timestamptz not null default now(),
    joined_at  timestamptz,
    left_at    timestamptz,
    primary key (call_id, user_id)
);

create index if not exists group_calls_host_idx on public.group_calls (host_id, created_at desc);
create index if not exists group_calls_status_idx on public.group_calls (status, created_at desc);
create index if not exists group_call_participants_user_idx
    on public.group_call_participants (user_id, status);

alter table public.group_calls enable row level security;
alter table public.group_call_participants enable row level security;

drop policy if exists group_calls_select_members on public.group_calls;
create policy group_calls_select_members on public.group_calls
    for select using (
        host_id = auth.uid()
        or exists (
            select 1 from public.group_call_participants p
             where p.call_id = group_calls.id and p.user_id = auth.uid()
        )
    );

drop policy if exists group_calls_insert_host on public.group_calls;
create policy group_calls_insert_host on public.group_calls
    for insert with check (host_id = auth.uid());

drop policy if exists group_calls_update_host on public.group_calls;
create policy group_calls_update_host on public.group_calls
    for update using (host_id = auth.uid()) with check (host_id = auth.uid());

drop policy if exists group_participants_select on public.group_call_participants;
create policy group_participants_select on public.group_call_participants
    for select using (
        user_id = auth.uid()
        or exists (
            select 1 from public.group_calls c
             where c.id = group_call_participants.call_id and c.host_id = auth.uid()
        )
        or exists (
            select 1 from public.group_call_participants me
             where me.call_id = group_call_participants.call_id and me.user_id = auth.uid()
        )
    );

alter table public.group_calls replica identity full;
alter table public.group_call_participants replica identity full;

do $$
begin
    if not exists (
        select 1 from pg_publication_tables
         where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'group_calls'
    ) then
        alter publication supabase_realtime add table public.group_calls;
    end if;
    if not exists (
        select 1 from pg_publication_tables
         where pubname = 'supabase_realtime' and schemaname = 'public'
           and tablename = 'group_call_participants'
    ) then
        alter publication supabase_realtime add table public.group_call_participants;
    end if;
end $$;

-- ---------------------------------------------------------------------------
-- RPCs
-- ---------------------------------------------------------------------------

create or replace function public.start_group_call(
    p_title text default 'Group call',
    p_call_type text default 'video',
    p_invitee_ids uuid[] default '{}'
)
returns public.group_calls
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_me uuid := auth.uid();
  v_call public.group_calls;
  v_invitees uuid[];
  v_name text;
begin
  if v_me is null then
    raise exception 'Not authenticated' using errcode = '28000';
  end if;
  if p_call_type not in ('voice','video') then
    raise exception 'Unsupported call type' using errcode = '22023';
  end if;

  select array_agg(distinct candidate)
    into v_invitees
    from unnest(coalesce(p_invitee_ids, '{}')) as candidate
   where candidate <> v_me
     and exists (select 1 from public.profiles pr where pr.id = candidate)
     and not exists (
       select 1 from public.user_blocks b
        where (b.blocker_id = candidate and b.blocked_id = v_me)
     );

  if v_invitees is null or array_length(v_invitees, 1) is null then
    raise exception 'Invite at least one person' using errcode = '22023';
  end if;

  -- Whatever the host was still hosting is closed first: one live room per host.
  update public.group_calls
     set status = 'ended', ended_at = now(), ended_by = v_me
   where host_id = v_me and status in ('ringing','active');

  select coalesce(nullif(btrim(p_title), ''), 'Group call') into v_name;

  insert into public.group_calls (host_id, title, call_type, status, room_name, started_at)
  values (
    v_me,
    left(v_name, 80),
    p_call_type,
    'active',
    'minichat-g-' || replace(gen_random_uuid()::text, '-', ''),
    now()
  )
  returning * into v_call;

  insert into public.group_call_participants (call_id, user_id, status, joined_at)
  values (v_call.id, v_me, 'joined', now())
  on conflict (call_id, user_id) do update
    set status = 'joined', joined_at = coalesce(public.group_call_participants.joined_at, now());

  insert into public.group_call_participants (call_id, user_id, status)
  select v_call.id, invitee, 'invited' from unnest(v_invitees) as invitee
  on conflict (call_id, user_id) do nothing;

  return v_call;
end $$;

create or replace function public.join_group_call(p_call_id uuid)
returns public.group_calls
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_me uuid := auth.uid();
  v_call public.group_calls;
begin
  if v_me is null then
    raise exception 'Not authenticated' using errcode = '28000';
  end if;

  select * into v_call from public.group_calls where id = p_call_id for update;
  if v_call.id is null then
    raise exception 'Call not found' using errcode = 'P0002';
  end if;
  if v_call.status not in ('ringing','active') then
    raise exception 'This call has already ended' using errcode = 'P0001';
  end if;
  if not exists (
    select 1 from public.group_call_participants
     where call_id = p_call_id and user_id = v_me
  ) then
    raise exception 'You were not invited to this call' using errcode = '42501';
  end if;
  if (select count(*) from public.group_call_participants
       where call_id = p_call_id and status = 'joined') >= v_call.max_participants
     and not exists (
       select 1 from public.group_call_participants
        where call_id = p_call_id and user_id = v_me and status = 'joined'
     ) then
    raise exception 'This call is full' using errcode = 'P0001';
  end if;

  update public.group_call_participants
     set status = 'joined',
         joined_at = coalesce(joined_at, now()),
         left_at = null
   where call_id = p_call_id and user_id = v_me;

  if v_call.status = 'ringing' then
    update public.group_calls
       set status = 'active', started_at = coalesce(started_at, now())
     where id = p_call_id
    returning * into v_call;
  end if;

  return v_call;
end $$;

create or replace function public.decline_group_call(p_call_id uuid)
returns public.group_calls
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_me uuid := auth.uid();
  v_call public.group_calls;
begin
  if v_me is null then
    raise exception 'Not authenticated' using errcode = '28000';
  end if;

  update public.group_call_participants
     set status = 'declined', left_at = now()
   where call_id = p_call_id and user_id = v_me and status = 'invited';

  select * into v_call from public.group_calls where id = p_call_id;
  return v_call;
end $$;

create or replace function public.leave_group_call(p_call_id uuid)
returns public.group_calls
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_me uuid := auth.uid();
  v_call public.group_calls;
  v_remaining integer;
begin
  if v_me is null then
    raise exception 'Not authenticated' using errcode = '28000';
  end if;

  update public.group_call_participants
     set status = 'left', left_at = now()
   where call_id = p_call_id and user_id = v_me and status = 'joined';

  select count(*) into v_remaining
    from public.group_call_participants
   where call_id = p_call_id and status = 'joined';

  select * into v_call from public.group_calls where id = p_call_id;

  -- The room closes when the host leaves or when nobody is left inside.
  if v_call.id is not null
     and v_call.status in ('ringing','active')
     and (v_remaining = 0 or v_call.host_id = v_me) then
    update public.group_calls
       set status = 'ended',
           ended_at = now(),
           ended_by = v_me,
           duration_seconds = case
             when started_at is not null
               then greatest(0, extract(epoch from (now() - started_at))::integer)
             else null
           end
     where id = p_call_id
    returning * into v_call;
  end if;

  return v_call;
end $$;

create or replace function public.end_group_call(p_call_id uuid)
returns public.group_calls
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  v_me uuid := auth.uid();
  v_call public.group_calls;
begin
  if v_me is null then
    raise exception 'Not authenticated' using errcode = '28000';
  end if;

  select * into v_call from public.group_calls where id = p_call_id for update;
  if v_call.id is null then
    raise exception 'Call not found' using errcode = 'P0002';
  end if;
  if v_call.host_id <> v_me then
    raise exception 'Only the host can end this call' using errcode = '42501';
  end if;
  if v_call.status not in ('ringing','active') then
    return v_call;
  end if;

  update public.group_calls
     set status = 'ended',
         ended_at = now(),
         ended_by = v_me,
         duration_seconds = case
           when started_at is not null
             then greatest(0, extract(epoch from (now() - started_at))::integer)
           else null
         end
   where id = p_call_id
  returning * into v_call;

  update public.group_call_participants
     set status = 'left', left_at = now()
   where call_id = p_call_id and status = 'joined';

  return v_call;
end $$;

create or replace function public.active_group_call()
returns public.group_calls
language sql
security definer
set search_path to 'public'
as $$
  select c.*
    from public.group_calls c
    join public.group_call_participants p on p.call_id = c.id
   where p.user_id = auth.uid()
     and c.status in ('ringing','active')
   order by c.created_at desc
   limit 1;
$$;

create or replace function public.group_call_participants(p_call_id uuid)
returns table (
    user_id uuid,
    display_name text,
    username text,
    avatar_url text,
    status text,
    joined_at timestamptz,
    is_host boolean
)
language sql
security definer
set search_path to 'public'
as $$
  select p.user_id,
         pr.display_name,
         pr.username,
         pr.avatar_url,
         p.status,
         p.joined_at,
         (c.host_id = p.user_id) as is_host
    from public.group_call_participants p
    join public.group_calls c on c.id = p.call_id
    join public.profiles pr on pr.id = p.user_id
   where p.call_id = p_call_id
     and (
       c.host_id = auth.uid()
       or exists (
         select 1 from public.group_call_participants me
          where me.call_id = p_call_id and me.user_id = auth.uid()
       )
     )
   order by (c.host_id = p.user_id) desc, p.status = 'joined' desc, pr.display_name;
$$;

create or replace function public.group_call_history(p_limit integer default 50, p_offset integer default 0)
returns table (
    id uuid,
    title text,
    call_type text,
    status text,
    created_at timestamptz,
    started_at timestamptz,
    ended_at timestamptz,
    duration_seconds integer,
    host_id uuid,
    host_name text,
    is_host boolean,
    participant_count integer
)
language sql
security definer
set search_path to 'public'
as $$
  select c.id,
         c.title,
         c.call_type,
         c.status,
         c.created_at,
         c.started_at,
         c.ended_at,
         c.duration_seconds,
         c.host_id,
         pr.display_name,
         (c.host_id = auth.uid()) as is_host,
         (select count(*)::integer from public.group_call_participants gp where gp.call_id = c.id)
    from public.group_calls c
    join public.profiles pr on pr.id = c.host_id
   where c.host_id = auth.uid()
      or exists (
        select 1 from public.group_call_participants gp
         where gp.call_id = c.id and gp.user_id = auth.uid()
      )
   order by c.created_at desc
   limit greatest(1, least(coalesce(p_limit, 50), 200))
   offset greatest(0, coalesce(p_offset, 0));
$$;

grant execute on function public.start_group_call(text, text, uuid[]) to authenticated;
grant execute on function public.join_group_call(uuid) to authenticated;
grant execute on function public.decline_group_call(uuid) to authenticated;
grant execute on function public.leave_group_call(uuid) to authenticated;
grant execute on function public.end_group_call(uuid) to authenticated;
grant execute on function public.active_group_call() to authenticated;
grant execute on function public.group_call_participants(uuid) to authenticated;
grant execute on function public.group_call_history(integer, integer) to authenticated;
