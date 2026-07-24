-- Store purchase hardening for the public verification path.
-- Provider verification remains in the Edge Function; all durable grant
-- writes happen in one database transaction below.

do $$
begin
    if exists (
        select 1
        from public.store_purchase_verifications
        group by store_platform, purchase_token_hash
        having count(*) > 1
    ) then
        raise exception 'purchase_atomicity_migration_blocked: duplicate purchase token hashes exist';
    end if;
end;
$$;

create unique index if not exists idx_store_purchase_verifications_token_hash
    on public.store_purchase_verifications (store_platform, purchase_token_hash);

create table if not exists public.store_purchase_attempt_buckets (
    user_id uuid primary key references auth.users(id) on delete cascade,
    bucket_started_at timestamptz not null,
    attempt_count integer not null check (attempt_count >= 0),
    updated_at timestamptz not null default now()
);

alter table public.store_purchase_attempt_buckets enable row level security;
revoke all on table public.store_purchase_attempt_buckets from public, anon, authenticated;
grant select, insert, update on table public.store_purchase_attempt_buckets to service_role;

create or replace function public.reserve_store_purchase_attempt(p_user_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    current_bucket timestamptz := date_trunc('hour', now());
    next_count integer;
begin
    if p_user_id is null then
        raise exception 'auth_required';
    end if;

    insert into public.store_purchase_attempt_buckets (user_id, bucket_started_at, attempt_count)
    values (p_user_id, current_bucket, 1)
    on conflict (user_id) do update set
        bucket_started_at = case
            when store_purchase_attempt_buckets.bucket_started_at < current_bucket then current_bucket
            else store_purchase_attempt_buckets.bucket_started_at
        end,
        attempt_count = case
            when store_purchase_attempt_buckets.bucket_started_at < current_bucket then 1
            else store_purchase_attempt_buckets.attempt_count + 1
        end,
        updated_at = now()
    returning attempt_count into next_count;

    return next_count <= 20;
end;
$$;

revoke all on function public.reserve_store_purchase_attempt(uuid) from public, anon, authenticated;
grant execute on function public.reserve_store_purchase_attempt(uuid) to service_role;

create or replace function public.complete_store_purchase_verification(
    p_user_id uuid,
    p_store_platform text,
    p_store_product_id text,
    p_store_transaction_id text,
    p_purchase_token_hash text,
    p_plan text,
    p_billing_period text,
    p_original_transaction_id text,
    p_subscription_key text,
    p_expires_at timestamptz
)
returns table (
    verification_id uuid,
    grant_id uuid
)
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    verification_row public.store_purchase_verifications%rowtype;
    original_row public.store_purchase_verifications%rowtype;
    grant_row public.user_entitlement_grants%rowtype;
    conflicting_user uuid;
begin
    if p_user_id is null
       or p_store_platform not in ('google_play', 'app_store')
       or nullif(trim(coalesce(p_store_product_id, '')), '') is null
       or nullif(trim(coalesce(p_store_transaction_id, '')), '') is null
       or nullif(trim(coalesce(p_purchase_token_hash, '')), '') is null
       or p_plan not in ('standard', 'pro')
       or p_billing_period not in ('monthly', 'yearly')
       or nullif(trim(coalesce(p_subscription_key, '')), '') is null
       or p_expires_at is null
       or p_expires_at <= now() then
        raise exception 'invalid_verified_purchase';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(
        'purchase:' || p_store_platform || ':' || p_subscription_key,
        0
    ));

    -- A token or original transaction may never move to another account.
    select * into verification_row
      from public.store_purchase_verifications
     where store_platform = p_store_platform
       and purchase_token_hash = p_purchase_token_hash
     for update;
    if verification_row.id is not null and verification_row.user_id <> p_user_id then
        raise exception 'purchase_already_claimed';
    end if;

    if p_original_transaction_id is not null then
        select * into original_row
          from public.store_purchase_verifications
         where store_platform = p_store_platform
           and original_transaction_id = p_original_transaction_id
           and status = 'verified'
         for update;
        if original_row.id is not null and original_row.user_id <> p_user_id then
            raise exception 'purchase_already_claimed';
        end if;
    end if;

    if verification_row.id is not null
       and original_row.id is not null
       and verification_row.id <> original_row.id then
        -- A token and an original transaction resolving to different rows is
        -- an integrity conflict. Do not merge or overwrite either row.
        raise exception 'purchase_state_conflict';
    end if;

    if verification_row.id is null and original_row.id is not null then
        verification_row := original_row;
    end if;

    select g.user_id into conflicting_user
      from public.user_entitlement_grants g
     where g.source = 'store_subscription'
       and g.store_platform = p_store_platform
       and g.user_id <> p_user_id
       and (
           g.store_subscription_key = p_subscription_key
           or g.store_transaction_id = p_store_transaction_id
       )
     limit 1
     for update;
    if conflicting_user is not null and conflicting_user <> p_user_id then
        raise exception 'purchase_already_claimed';
    end if;

    select * into grant_row
      from public.user_entitlement_grants g
     where g.source = 'store_subscription'
       and g.user_id = p_user_id
       and g.store_platform = p_store_platform
       and (
           g.store_subscription_key = p_subscription_key
           or g.store_transaction_id = p_store_transaction_id
       )
     order by g.updated_at desc
     limit 1
     for update;

    if grant_row.id is null then
        insert into public.user_entitlement_grants (
            user_id,
            plan,
            source,
            store_platform,
            store_product_id,
            billing_period,
            store_transaction_id,
            store_subscription_key,
            starts_at,
            expires_at,
            status
        ) values (
            p_user_id,
            p_plan,
            'store_subscription',
            p_store_platform,
            p_store_product_id,
            p_billing_period,
            p_store_transaction_id,
            p_subscription_key,
            now(),
            p_expires_at,
            'active'
        ) returning * into grant_row;
    else
        update public.user_entitlement_grants
           set plan = p_plan,
               store_product_id = p_store_product_id,
               billing_period = p_billing_period,
               store_transaction_id = p_store_transaction_id,
               store_subscription_key = p_subscription_key,
               expires_at = p_expires_at,
               status = 'active'
         where id = grant_row.id
         returning * into grant_row;
    end if;

    if verification_row.id is null then
        verification_row := null;
        insert into public.store_purchase_verifications (
            user_id,
            store_platform,
            store_product_id,
            store_transaction_id,
            purchase_token_hash,
            original_transaction_id,
            plan,
            billing_period,
            status,
            grant_id,
            expires_at,
            verified_at
        ) values (
            p_user_id,
            p_store_platform,
            p_store_product_id,
            p_store_transaction_id,
            p_purchase_token_hash,
            p_original_transaction_id,
            p_plan,
            p_billing_period,
            'verified',
            grant_row.id,
            p_expires_at,
            now()
        ) returning * into verification_row;
    else
        update public.store_purchase_verifications
           set user_id = p_user_id,
               store_product_id = p_store_product_id,
               store_transaction_id = p_store_transaction_id,
               purchase_token_hash = p_purchase_token_hash,
               original_transaction_id = p_original_transaction_id,
               plan = p_plan,
               billing_period = p_billing_period,
               status = 'verified',
               failure_reason = null,
               grant_id = grant_row.id,
               expires_at = p_expires_at,
               verified_at = now()
         where id = verification_row.id
         returning * into verification_row;
    end if;

    verification_id := verification_row.id;
    grant_id := grant_row.id;
    return next;
end;
$$;

revoke all on function public.complete_store_purchase_verification(uuid, text, text, text, text, text, text, text, text, timestamptz)
    from public, anon, authenticated;
grant execute on function public.complete_store_purchase_verification(uuid, text, text, text, text, text, text, text, text, timestamptz)
    to service_role;
