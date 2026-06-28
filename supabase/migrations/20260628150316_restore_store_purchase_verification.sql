create extension if not exists pgcrypto;

alter table public.user_entitlement_grants
    drop constraint if exists user_entitlement_grants_plan_check;

alter table public.user_entitlement_grants
    add constraint user_entitlement_grants_plan_check
    check (plan in ('free', 'launch_standard', 'standard', 'pro', 'promo_pro'));

create unique index if not exists idx_user_entitlement_grants_unique_store_transaction
    on public.user_entitlement_grants (store_platform, store_transaction_id)
    where source = 'store_subscription'
      and store_platform is not null
      and store_transaction_id is not null;

create table if not exists public.store_purchase_verifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    store_platform text not null check (store_platform in ('google_play', 'app_store')),
    store_product_id text not null,
    store_transaction_id text null,
    purchase_token_hash text not null,
    plan text not null check (plan in ('standard', 'pro')),
    billing_period text not null check (billing_period in ('monthly', 'yearly')),
    status text not null check (status in ('verified', 'failed', 'pending')),
    failure_reason text null,
    grant_id uuid null references public.user_entitlement_grants(id) on delete set null,
    expires_at timestamptz null,
    verified_at timestamptz null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create unique index if not exists idx_store_purchase_verifications_unique_purchase
    on public.store_purchase_verifications (store_platform, store_transaction_id)
    where store_transaction_id is not null and status = 'verified';

create index if not exists idx_store_purchase_verifications_user
    on public.store_purchase_verifications (user_id, created_at desc);

drop trigger if exists trg_store_purchase_verifications_updated_at on public.store_purchase_verifications;
create trigger trg_store_purchase_verifications_updated_at
before update on public.store_purchase_verifications
for each row
execute function private.set_updated_at();

alter table public.store_purchase_verifications enable row level security;

drop policy if exists "Users can read their own store purchase verifications" on public.store_purchase_verifications;
create policy "Users can read their own store purchase verifications"
on public.store_purchase_verifications
for select
to authenticated
using ((select auth.uid()) = user_id);

revoke all on table public.store_purchase_verifications from anon, authenticated;
grant select on table public.store_purchase_verifications to authenticated;
