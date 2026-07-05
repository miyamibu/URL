-- Admin panel foundation: admin_users, user_profiles, admin_audit_logs.
-- All three tables are created in one migration to keep the admin schema atomic.

----------------------------------------------------------------------------
-- 1. admin_users — who is allowed to use the admin panel
----------------------------------------------------------------------------

create table if not exists public.admin_users (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null unique references auth.users(id) on delete cascade,
    email text not null,
    role text not null check (role in ('owner', 'moderator', 'billing', 'readonly')),
    status text not null default 'active' check (status in ('active', 'suspended')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create unique index if not exists idx_admin_users_email_lower
    on public.admin_users (lower(email));
drop trigger if exists trg_admin_users_updated_at on public.admin_users;
create trigger trg_admin_users_updated_at
before update on public.admin_users
for each row
execute function private.set_updated_at();
alter table public.admin_users enable row level security;
-- No RLS policies: no client-side access at all.
-- Admin verification happens exclusively inside Edge Functions via service role.

revoke all on table public.admin_users from public, anon, authenticated;
----------------------------------------------------------------------------
-- 2. user_profiles — lightweight profile for every app user
----------------------------------------------------------------------------

create table if not exists public.user_profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    display_name text,
    auth_provider text,
    created_at timestamptz not null default now(),
    last_seen_at timestamptz,
    account_status text not null default 'active'
        check (account_status in ('active', 'suspended', 'banned')),
    admin_note text,
    support_ticket_id text,
    updated_at timestamptz not null default now()
);
create index if not exists idx_user_profiles_account_status
    on public.user_profiles (account_status);
create index if not exists idx_user_profiles_last_seen
    on public.user_profiles (last_seen_at desc nulls last);
drop trigger if exists trg_user_profiles_updated_at on public.user_profiles;
create trigger trg_user_profiles_updated_at
before update on public.user_profiles
for each row
execute function private.set_updated_at();
alter table public.user_profiles enable row level security;
-- No SELECT/UPDATE policies at all.
-- All client reads go through get_my_public_profile() / get_my_account_status() RPCs.
-- All client writes go through upsert_my_profile() (security definer).
-- This guarantees admin_note, support_ticket_id, account_status are never
-- directly readable or writable by authenticated users.

revoke all on table public.user_profiles from public, anon, authenticated;
-- RPC for the mobile app to read own account status.
create or replace function public.get_my_account_status()
returns table (
    account_status text
)
language sql
security definer
stable
set search_path = public, pg_temp
as $$
    select p.account_status
    from public.user_profiles p
    where p.user_id = (select auth.uid())
    limit 1;
$$;
revoke all on function public.get_my_account_status() from public;
grant execute on function public.get_my_account_status() to authenticated;
-- RPC for the mobile app to read safe profile fields only.
create or replace function public.get_my_public_profile()
returns table (
    display_name text,
    auth_provider text,
    last_seen_at timestamptz
)
language sql
security definer
stable
set search_path = public, pg_temp
as $$
    select p.display_name, p.auth_provider, p.last_seen_at
    from public.user_profiles p
    where p.user_id = (select auth.uid())
    limit 1;
$$;
revoke all on function public.get_my_public_profile() from public;
grant execute on function public.get_my_public_profile() to authenticated;
-- RPC for the mobile app to upsert its own profile on sign-in.
create or replace function public.upsert_my_profile(
    p_display_name text default null,
    p_auth_provider text default null
)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    caller uuid := (select auth.uid());
    safe_name text;
    safe_provider text;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    safe_name := nullif(trim(left(coalesce(p_display_name, ''), 40)), '');

    safe_provider := case lower(trim(coalesce(p_auth_provider, '')))
        when 'email' then 'email'
        when 'google' then 'google'
        when 'apple' then 'apple'
        else null
    end;

    insert into public.user_profiles (user_id, display_name, auth_provider, last_seen_at)
    values (caller, safe_name, safe_provider, now())
    on conflict (user_id)
    do update set
        display_name = coalesce(excluded.display_name, user_profiles.display_name),
        auth_provider = coalesce(excluded.auth_provider, user_profiles.auth_provider),
        last_seen_at = now();
end;
$$;
revoke all on function public.upsert_my_profile(text, text) from public;
grant execute on function public.upsert_my_profile(text, text) to authenticated;
----------------------------------------------------------------------------
-- 3. admin_audit_logs — immutable record of every admin action
----------------------------------------------------------------------------

create table if not exists public.admin_audit_logs (
    id uuid primary key default gen_random_uuid(),
    admin_user_id uuid not null references public.admin_users(id) on delete restrict,
    target_user_id uuid references auth.users(id) on delete set null,
    action text not null,
    reason text,
    before_value jsonb,
    after_value jsonb,
    created_at timestamptz not null default now()
);
create index if not exists idx_admin_audit_logs_admin
    on public.admin_audit_logs (admin_user_id, created_at desc);
create index if not exists idx_admin_audit_logs_target
    on public.admin_audit_logs (target_user_id, created_at desc);
create index if not exists idx_admin_audit_logs_action
    on public.admin_audit_logs (action, created_at desc);
alter table public.admin_audit_logs enable row level security;
-- No RLS policies: no client-side access.
-- Audit logs are read/written exclusively by Edge Functions via service role.

revoke all on table public.admin_audit_logs from public, anon, authenticated;
