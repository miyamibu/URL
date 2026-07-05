-- Add the paid-plan catalog shape without granting clients write access.
-- Store purchase verification remains backend-only; clients only read active grants.

alter table public.user_entitlement_grants
drop constraint if exists user_entitlement_grants_plan_check;
alter table public.user_entitlement_grants
add constraint user_entitlement_grants_plan_check
check (plan in ('free', 'launch_standard', 'standard', 'pro', 'promo_pro'));
alter table public.user_entitlement_grants
add column if not exists store_product_id text null,
add column if not exists billing_period text null;
alter table public.user_entitlement_grants
drop constraint if exists user_entitlement_grants_billing_period_check;
alter table public.user_entitlement_grants
add constraint user_entitlement_grants_billing_period_check
check (billing_period is null or billing_period in ('monthly', 'yearly'));
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
            when 'standard' then 2
            when 'launch_standard' then 3
            when 'free' then 4
            else 5
        end,
        grant_row.starts_at desc;
$$;
revoke all on function public.get_my_entitlement_grants() from public;
grant execute on function public.get_my_entitlement_grants() to authenticated;
create table if not exists public.subscription_products (
    id text primary key,
    plan text not null check (plan in ('standard', 'pro')),
    billing_period text not null check (billing_period in ('monthly', 'yearly')),
    store_platform text not null check (store_platform in ('google_play', 'app_store')),
    store_product_id text not null,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (store_platform, store_product_id),
    unique (plan, billing_period, store_platform)
);
drop trigger if exists trg_subscription_products_updated_at on public.subscription_products;
create trigger trg_subscription_products_updated_at
before update on public.subscription_products
for each row
execute function private.set_updated_at();
alter table public.subscription_products enable row level security;
drop policy if exists subscription_products_public_active_select on public.subscription_products;
create policy subscription_products_public_active_select
on public.subscription_products
for select
to anon, authenticated
using (is_active);
revoke all on table public.subscription_products from public, anon, authenticated;
grant select on table public.subscription_products to anon, authenticated;
create or replace function public.get_subscription_products()
returns table (
    id text,
    plan text,
    billing_period text,
    store_platform text,
    store_product_id text
)
language sql
security invoker
stable
set search_path = public, pg_temp
as $$
    select
        product.id,
        product.plan,
        product.billing_period,
        product.store_platform,
        product.store_product_id
    from public.subscription_products product
    where product.is_active
    order by
        case product.plan
            when 'standard' then 0
            when 'pro' then 1
            else 2
        end,
        case product.billing_period
            when 'monthly' then 0
            when 'yearly' then 1
            else 2
        end,
        product.store_platform;
$$;
revoke all on function public.get_subscription_products() from public;
grant execute on function public.get_subscription_products() to anon, authenticated;
