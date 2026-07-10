-- Grant idempotency: prevent duplicate active grants for the same store transaction.
-- mark_store_purchase_verified now upserts on (store_platform, store_transaction_id).

-- Pre-check: abort if existing data contains duplicates that would break the index.
do $$
declare
    dup_count integer;
begin
    select count(*) into dup_count
    from (
        select store_platform, store_transaction_id
        from public.user_entitlement_grants
        where store_transaction_id is not null
        group by store_platform, store_transaction_id
        having count(*) > 1
    ) dups;

    if dup_count > 0 then
        raise exception
            'grant_idempotency_migration_blocked: % duplicate (store_platform, store_transaction_id) pairs exist. '
            'Resolve manually before applying this migration. '
            'Query: SELECT store_platform, store_transaction_id, count(*) FROM public.user_entitlement_grants '
            'WHERE store_transaction_id IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1;',
            dup_count;
    end if;
end;
$$;
create unique index if not exists idx_user_entitlement_grants_store_txn_unique
    on public.user_entitlement_grants (store_platform, store_transaction_id)
    where store_transaction_id is not null;
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
    existing_grant_owner uuid;
    resolved_txn_id text;
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

    resolved_txn_id := coalesce(
        nullif(trim(coalesce(p_store_transaction_id, '')), ''),
        verification_row.id::text
    );

    -- Reject if a grant with the same store transaction already belongs to another user.
    select g.user_id
    into existing_grant_owner
    from public.user_entitlement_grants g
    where g.store_platform = verification_row.store_platform
      and g.store_transaction_id = resolved_txn_id;

    if existing_grant_owner is not null and existing_grant_owner <> verification_row.user_id then
        update private.store_purchase_verifications
        set
            status = 'rejected',
            rejection_reason = 'store_transaction_already_claimed',
            raw_verification = p_raw_verification
        where id = verification_row.id;

        raise exception 'store_transaction_already_claimed';
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
        resolved_txn_id,
        now(),
        p_expires_at,
        'active'
    )
    on conflict (store_platform, store_transaction_id) where store_transaction_id is not null
    do update set
        status = 'active',
        plan = excluded.plan,
        billing_period = excluded.billing_period,
        store_product_id = excluded.store_product_id,
        expires_at = coalesce(excluded.expires_at, public.user_entitlement_grants.expires_at)
    where public.user_entitlement_grants.user_id = excluded.user_id
    returning * into grant_row;

    -- ON CONFLICT matched but WHERE excluded the row → another user owns this transaction.
    if grant_row.id is null then
        update private.store_purchase_verifications
        set
            status = 'rejected',
            rejection_reason = 'store_transaction_already_claimed',
            raw_verification = p_raw_verification
        where id = verification_row.id;

        raise exception 'store_transaction_already_claimed';
    end if;

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
