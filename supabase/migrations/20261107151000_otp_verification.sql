-- =============================================================================
-- MINI CHAT — 006
--   * Email verification by 6-digit code (OTP) instead of a magic link.
--   * Server-side rate limiting for code requests and attempts.
--
-- HOW IT WORKS
-- ------------
-- Supabase's built-in `verifyOtp(type = 'email')` accepts the numeric
-- `{{ .Token }}` from the confirmation email, so the client never needs to trust
-- itself: the code is verified by Gotrue, not by this database.
--
-- What the database adds is abuse protection. A 6-digit code is guessable
-- (10^6 space) so attempts must be capped per email and per IP, and code
-- requests must be capped too — otherwise the endpoints become a spam relay.
--
-- These tables are only reachable through the `otp-guard` Edge Function, which
-- uses the service role. RLS is enabled with no policies, so no client can read
-- or write them even if it learns their names.
-- =============================================================================

create table if not exists public.otp_requests (
    id          uuid primary key default gen_random_uuid(),
    email       text not null,
    ip_hash     text,
    created_at  timestamptz not null default now()
);

create index if not exists otp_requests_email_idx on public.otp_requests (lower(email), created_at desc);
create index if not exists otp_requests_ip_idx    on public.otp_requests (ip_hash, created_at desc);

create table if not exists public.otp_attempts (
    id          uuid primary key default gen_random_uuid(),
    email       text not null,
    succeeded   boolean not null default false,
    created_at  timestamptz not null default now()
);

create index if not exists otp_attempts_email_idx on public.otp_attempts (lower(email), created_at desc);

create table if not exists public.otp_blocks (
    email      text primary key,
    blocked_until timestamptz not null,
    reason     text
);

alter table public.otp_requests enable row level security;
alter table public.otp_attempts enable row level security;
alter table public.otp_blocks   enable row level security;

-- No policies on purpose: service role only.

-- -----------------------------------------------------------------------------
-- Request gate: max 3 code requests per email per 15 minutes, 10 per IP.
-- Returns the number of seconds to wait, or 0 when the request may proceed.
-- -----------------------------------------------------------------------------
create or replace function public.otp_request_gate(p_email text, p_ip_hash text default null)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    recent_email int;
    recent_ip    int;
    oldest       timestamptz;
begin
    if exists (
        select 1 from public.otp_blocks b
        where lower(b.email) = lower(p_email) and b.blocked_until > now()
    ) then
        select extract(epoch from (blocked_until - now()))::int into recent_email
        from public.otp_blocks where lower(email) = lower(p_email);
        return greatest(recent_email, 60);
    end if;

    select count(*), min(created_at) into recent_email, oldest
    from public.otp_requests
    where lower(email) = lower(p_email) and created_at > now() - interval '15 minutes';

    if recent_email >= 3 then
        return greatest(60, extract(epoch from (oldest + interval '15 minutes' - now()))::int);
    end if;

    if p_ip_hash is not null then
        select count(*) into recent_ip
        from public.otp_requests
        where ip_hash = p_ip_hash and created_at > now() - interval '15 minutes';

        if recent_ip >= 10 then
            return 900;
        end if;
    end if;

    return 0;
end;
$$;

create or replace function public.otp_record_request(p_email text, p_ip_hash text default null)
returns void
language sql
security definer
set search_path = public
as $$
    insert into public.otp_requests (email, ip_hash) values (lower(p_email), p_ip_hash);

    -- Opportunistic cleanup keeps the table small without a cron job.
    delete from public.otp_requests where created_at < now() - interval '2 days';
$$;

-- -----------------------------------------------------------------------------
-- Verification gate: max 5 wrong attempts per email per 15 minutes. After that
-- the address is blocked for 30 minutes so the 10^6 space cannot be walked.
-- -----------------------------------------------------------------------------
create or replace function public.otp_verify_gate(p_email text)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
    failures int;
    oldest   timestamptz;
begin
    if exists (
        select 1 from public.otp_blocks b
        where lower(b.email) = lower(p_email) and b.blocked_until > now()
    ) then
        select greatest(60, extract(epoch from (blocked_until - now()))::int) into failures
        from public.otp_blocks where lower(email) = lower(p_email);
        return failures;
    end if;

    select count(*), min(created_at) into failures, oldest
    from public.otp_attempts
    where lower(email) = lower(p_email)
      and not succeeded
      and created_at > now() - interval '15 minutes';

    if failures >= 5 then
        return greatest(60, extract(epoch from (oldest + interval '15 minutes' - now()))::int);
    end if;

    return 0;
end;
$$;

create or replace function public.otp_record_attempt(p_email text, p_succeeded boolean)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    failures int;
begin
    insert into public.otp_attempts (email, succeeded) values (lower(p_email), p_succeeded);

    if p_succeeded then
        delete from public.otp_attempts where lower(email) = lower(p_email);
        delete from public.otp_blocks   where lower(email) = lower(p_email);
        return;
    end if;

    select count(*) into failures
    from public.otp_attempts
    where lower(email) = lower(p_email)
      and not succeeded
      and created_at > now() - interval '15 minutes';

    if failures >= 5 then
        insert into public.otp_blocks (email, blocked_until, reason)
        values (lower(p_email), now() + interval '30 minutes', 'too many incorrect codes')
        on conflict (email) do update
            set blocked_until = excluded.blocked_until,
                reason = excluded.reason;
    end if;

    delete from public.otp_attempts where created_at < now() - interval '2 days';
end;
$$;

revoke all on function public.otp_request_gate(text, text)      from public, anon, authenticated;
revoke all on function public.otp_record_request(text, text)    from public, anon, authenticated;
revoke all on function public.otp_verify_gate(text)             from public, anon, authenticated;
revoke all on function public.otp_record_attempt(text, boolean)  from public, anon, authenticated;

grant execute on function public.otp_request_gate(text, text)     to service_role;
grant execute on function public.otp_record_request(text, text)   to service_role;
grant execute on function public.otp_verify_gate(text)            to service_role;
grant execute on function public.otp_record_attempt(text, boolean) to service_role;

do $$
begin
    perform pg_notify('pgrst', 'reload schema');
exception when others then null;
end;
$$;
