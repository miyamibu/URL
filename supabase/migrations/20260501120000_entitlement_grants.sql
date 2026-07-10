create extension if not exists pgcrypto;

create schema if not exists private;

create or replace function private.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create table if not exists public.user_entitlement_grants (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    plan text not null check (plan in ('free', 'launch_standard', 'pro', 'promo_pro')),
    source text not null check (source in ('store_subscription', 'store_promo_code', 'admin_grant', 'referral_grant')),
    store_platform text null,
    store_transaction_id text null,
    starts_at timestamptz not null default now(),
    expires_at timestamptz null,
    status text not null check (status in ('active', 'revoked', 'pending')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (expires_at is null or expires_at > starts_at)
);

create index if not exists idx_user_entitlement_grants_user
    on public.user_entitlement_grants (user_id, status, starts_at desc);

create index if not exists idx_user_entitlement_grants_active
    on public.user_entitlement_grants (user_id, plan, starts_at desc)
    where status = 'active';

drop trigger if exists trg_user_entitlement_grants_updated_at on public.user_entitlement_grants;
create trigger trg_user_entitlement_grants_updated_at
before update on public.user_entitlement_grants
for each row
execute function private.set_updated_at();

alter table public.user_entitlement_grants enable row level security;

drop policy if exists "Users can read their own entitlement grants" on public.user_entitlement_grants;
create policy "Users can read their own entitlement grants"
on public.user_entitlement_grants
for select
to authenticated
using ((select auth.uid()) = user_id);

revoke all on table public.user_entitlement_grants from anon, authenticated;
grant select on table public.user_entitlement_grants to authenticated;

create or replace function public.get_my_entitlement_grants()
returns table (
    id uuid,
    plan text,
    source text,
    store_platform text,
    store_transaction_id text,
    starts_at timestamptz,
    expires_at timestamptz,
    status text
)
language sql
security invoker
stable
set search_path = public, pg_temp
as $$
    select
        grant_row.id,
        grant_row.plan,
        grant_row.source,
        grant_row.store_platform,
        grant_row.store_transaction_id,
        grant_row.starts_at,
        grant_row.expires_at,
        grant_row.status
    from public.user_entitlement_grants grant_row
    where grant_row.user_id = (select auth.uid())
      and grant_row.status = 'active'
      and grant_row.starts_at <= now()
      and (grant_row.expires_at is null or grant_row.expires_at > now())
    order by
        case grant_row.plan
            when 'promo_pro' then 0
            when 'pro' then 1
            when 'launch_standard' then 2
            when 'free' then 3
            else 4
        end,
        grant_row.starts_at desc;
$$;

revoke all on function public.get_my_entitlement_grants() from public;
grant execute on function public.get_my_entitlement_grants() to authenticated;
