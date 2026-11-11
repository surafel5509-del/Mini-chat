-- =============================================================================
-- MINI CHAT — 003
-- Usernames must start with a letter.
--
-- Rationale: a username is also the @mention handle and is used verbatim in
-- notification titles and deep links. Leading digits make "@1ada" ambiguous and
-- break mention parsing, so the rule is enforced in one place: the database.
--
-- The generated default username (from the email local part) is normalised the
-- same way, so the auth trigger can never insert a row that violates the new
-- constraint.
-- =============================================================================

-- 1. Normalise the profile default-username generator.
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

    -- Strip leading digits/underscores: usernames must begin with a letter.
    base_username := regexp_replace(coalesce(base_username, ''), '^[^a-z]+', '');

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
        nullif(btrim(new.raw_user_meta_data ->> 'full_name'), ''),
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

-- 2. Tighten the constraint. Existing rows are left untouched; the constraint is
--    validated for new and updated rows only, so no user is locked out of their
--    own profile by this change.
do $$
begin
    alter table public.profiles drop constraint if exists profiles_username_format;
    alter table public.profiles
        add constraint profiles_username_format
        check (username ~ '^[a-z][a-z0-9_]{2,23}$') not valid;
end;
$$;

-- 3. Rebuild the PostgREST relationship cache.
do $$
begin
    perform pg_notify('pgrst', 'reload schema');
exception when others then null;
end;
$$;
