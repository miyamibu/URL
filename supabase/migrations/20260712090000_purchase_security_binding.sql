-- Additive purchase-security contract for the public verification/grant path.
-- Existing historical migrations are intentionally not rewritten.

alter table public.store_purchase_verifications
    add column if not exists original_transaction_id text null;

alter table public.user_entitlement_grants
    add column if not exists store_subscription_key text null;

do $$
declare
    verification_duplicates integer;
    grant_duplicates integer;
begin
    select count(*) into verification_duplicates
    from (
        select store_platform, original_transaction_id
        from public.store_purchase_verifications
        where original_transaction_id is not null
          and status = 'verified'
        group by store_platform, original_transaction_id
        having count(*) > 1
    ) duplicates;

    if verification_duplicates > 0 then
        raise exception
            'purchase_security_migration_blocked: duplicate verified original_transaction_id rows exist';
    end if;

    select count(*) into grant_duplicates
    from (
        select store_platform, store_subscription_key
        from public.user_entitlement_grants
        where source = 'store_subscription'
          and store_subscription_key is not null
        group by store_platform, store_subscription_key
        having count(*) > 1
    ) duplicates;

    if grant_duplicates > 0 then
        raise exception
            'purchase_security_migration_blocked: duplicate store_subscription_key grants exist';
    end if;
end;
$$;

create unique index if not exists idx_store_purchase_verifications_original_transaction
    on public.store_purchase_verifications (store_platform, original_transaction_id)
    where original_transaction_id is not null and status = 'verified';

create unique index if not exists idx_user_entitlement_grants_store_subscription_key
    on public.user_entitlement_grants (store_platform, store_subscription_key)
    where source = 'store_subscription' and store_subscription_key is not null;
