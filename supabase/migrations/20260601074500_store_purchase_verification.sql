-- Store purchase verification queue.
-- Raw purchase tokens stay server-side in private schema and never become client-readable grants.

create schema if not exists private;
create table if not exists private.store_purchase_verifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    store_platform text not null check (store_platform in ('google_play', 'app_store')),
    store_product_id text not null,
    plan text not null check (plan in ('standard', 'pro')),
    billing_period text not null check (billing_period in ('monthly', 'yearly')),
    store_transaction_id text null,
    purchase_token_hash text not null,
    status text not null default 'pending' check (status in ('pending', 'verified', 'rejected', 'revoked')),
    rejection_reason text null,
    verified_at timestamptz null,
    raw_verification jsonb null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (store_platform, purchase_token_hash),
    check (verified_at is null or status in ('verified', 'revoked'))
);
create index if not exists idx_store_purchase_verifications_user_status
    on private.store_purchase_verifications (user_id, status, created_at desc);
create index if not exists idx_store_purchase_verifications_product
    on private.store_purchase_verifications (store_platform, store_product_id, status);
drop trigger if exists trg_store_purchase_verifications_updated_at on private.store_purchase_verifications;
create trigger trg_store_purchase_verifications_updated_at
before update on private.store_purchase_verifications
for each row
execute function private.set_updated_at();
alter table private.store_purchase_verifications enable row level security;
revoke all on table private.store_purchase_verifications from public, anon, authenticated;
create or replace function private.hash_purchase_token(purchase_token text)
returns text
language sql
immutable
strict
set search_path = private, public, pg_temp
as $$
    select encode(extensions.digest(purchase_token, 'sha256'), 'hex');
$$;
revoke all on function private.hash_purchase_token(text) from public, anon, authenticated;
create or replace function private.queue_store_purchase_verification(
    p_user_id uuid,
    p_store_platform text,
    p_store_product_id text,
    p_purchase_token text,
    p_store_transaction_id text default null
)
returns table (
    verification_id uuid,
    status text,
    plan text,
    billing_period text
)
language plpgsql
security definer
set search_path = private, public, pg_temp
as $$
declare
    product_row record;
    token_hash text;
    verification_row private.store_purchase_verifications%rowtype;
begin
    if p_user_id is null then
        raise exception 'auth_required';
    end if;

    if length(trim(coalesce(p_purchase_token, ''))) < 16 then
        raise exception 'invalid_purchase_token';
    end if;

    select
        product.plan,
        product.billing_period
    into product_row
    from public.subscription_products product
    where product.store_platform = p_store_platform
      and product.store_product_id = p_store_product_id
      and product.is_active
    limit 1;

    if product_row is null then
        raise exception 'unknown_product';
    end if;

    token_hash := private.hash_purchase_token(p_purchase_token);

    insert into private.store_purchase_verifications (
        user_id,
        store_platform,
        store_product_id,
        plan,
        billing_period,
        store_transaction_id,
        purchase_token_hash,
        status
    )
    values (
        p_user_id,
        p_store_platform,
        p_store_product_id,
        product_row.plan,
        product_row.billing_period,
        nullif(trim(coalesce(p_store_transaction_id, '')), ''),
        token_hash,
        'pending'
    )
    on conflict (store_platform, purchase_token_hash)
    do update set
        user_id = excluded.user_id,
        store_product_id = excluded.store_product_id,
        plan = excluded.plan,
        billing_period = excluded.billing_period,
        store_transaction_id = coalesce(excluded.store_transaction_id, private.store_purchase_verifications.store_transaction_id),
        status = case
            when private.store_purchase_verifications.status = 'verified' then 'verified'
            else 'pending'
        end,
        rejection_reason = null
    returning * into verification_row;

    return query
    select
        verification_row.id,
        verification_row.status,
        verification_row.plan,
        verification_row.billing_period;
end;
$$;
revoke all on function private.queue_store_purchase_verification(uuid, text, text, text, text) from public, anon, authenticated;
create or replace function private.mark_store_purchase_verified(
    p_verification_id uuid,
    p_store_transaction_id text,
    p_expires_at timestamptz,
    p_raw_verification jsonb default '{}'::jsonb
)
returns table (
    grant_id uuid,
    verification_id uuid,
    status text,
    plan text,
    billing_period text
)
language plpgsql
security definer
set search_path = private, public, pg_temp
as $$
declare
    verification_row private.store_purchase_verifications%rowtype;
    grant_row public.user_entitlement_grants%rowtype;
begin
    select *
    into verification_row
    from private.store_purchase_verifications
    where id = p_verification_id
    for update;

    if verification_row.id is null then
        raise exception 'verification_not_found';
    end if;

    if p_expires_at is not null and p_expires_at <= now() then
        update private.store_purchase_verifications
        set
            status = 'rejected',
            rejection_reason = 'expired_purchase',
            raw_verification = p_raw_verification
        where id = verification_row.id;

        raise exception 'expired_purchase';
    end if;

    update private.store_purchase_verifications
    set
        status = 'verified',
        store_transaction_id = nullif(trim(coalesce(p_store_transaction_id, '')), ''),
        verified_at = now(),
        raw_verification = p_raw_verification,
        rejection_reason = null
    where id = verification_row.id
    returning * into verification_row;

    insert into public.user_entitlement_grants (
        user_id,
        plan,
        source,
        store_platform,
        store_product_id,
        billing_period,
        store_transaction_id,
        starts_at,
        expires_at,
        status
    )
    values (
        verification_row.user_id,
        verification_row.plan,
        'store_subscription',
        verification_row.store_platform,
        verification_row.store_product_id,
        verification_row.billing_period,
        coalesce(nullif(trim(coalesce(p_store_transaction_id, '')), ''), verification_row.id::text),
        now(),
        p_expires_at,
        'active'
    )
    returning * into grant_row;

    return query
    select
        grant_row.id,
        verification_row.id,
        verification_row.status,
        verification_row.plan,
        verification_row.billing_period;
end;
$$;
revoke all on function private.mark_store_purchase_verified(uuid, text, timestamptz, jsonb) from public, anon, authenticated;
