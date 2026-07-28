-- Atomically finalize a provider-verified purchase.
-- Provider signature/account/product checks stay in the Edge Function. This
-- migration makes the durable verification, grant, binding, and audit event
-- one database transaction.

do $$
begin
    if exists (
        select 1
        from public.store_purchase_verifications
        group by store_platform, purchase_token_hash
        having count(*) > 1
    ) then
        raise exception
            'purchase_verification_atomicity_blocked: duplicate purchase token hashes exist';
    end if;
end;
$$;

create unique index if not exists idx_store_purchase_verifications_token_hash
    on public.store_purchase_verifications (store_platform, purchase_token_hash);

create table if not exists public.store_purchase_audit_events (
    id uuid primary key default gen_random_uuid(),
    event_type text not null check (event_type in ('verified')),
    provider text not null check (provider in ('google_play', 'app_store')),
    provider_event_id text not null,
    user_id uuid null references auth.users(id) on delete set null,
    verification_id uuid null references public.store_purchase_verifications(id) on delete set null,
    grant_id uuid null references public.user_entitlement_grants(id) on delete set null,
    store_product_id text not null,
    store_transaction_id text not null,
    store_subscription_key text not null,
    expires_at timestamptz not null,
    detail jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique (provider, provider_event_id, event_type)
);

create index if not exists idx_store_purchase_audit_events_user
    on public.store_purchase_audit_events (user_id, created_at desc);
create index if not exists idx_store_purchase_audit_events_verification
    on public.store_purchase_audit_events (verification_id, created_at desc);

alter table public.store_purchase_audit_events enable row level security;
revoke all on table public.store_purchase_audit_events from public, anon, authenticated;
grant select, insert on table public.store_purchase_audit_events to service_role;

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
    v_platform text := nullif(trim(coalesce(p_store_platform, '')), '');
    v_product_id text := nullif(trim(coalesce(p_store_product_id, '')), '');
    v_transaction_id text := nullif(trim(coalesce(p_store_transaction_id, '')), '');
    v_token_hash text := nullif(trim(coalesce(p_purchase_token_hash, '')), '');
    v_plan text := nullif(trim(coalesce(p_plan, '')), '');
    v_billing_period text := nullif(trim(coalesce(p_billing_period, '')), '');
    v_original_transaction_id text := nullif(trim(coalesce(p_original_transaction_id, '')), '');
    v_subscription_key text := nullif(trim(coalesce(p_subscription_key, '')), '');
    catalog_row record;
    verification_row public.store_purchase_verifications%rowtype;
    token_row public.store_purchase_verifications%rowtype;
    original_row public.store_purchase_verifications%rowtype;
    linked_grant_row public.user_entitlement_grants%rowtype;
    grant_row public.user_entitlement_grants%rowtype;
    conflicting_user uuid;
    audit_owner uuid;
    matching_grant_count integer;
begin
    if p_user_id is null
       or not exists (select 1 from auth.users where id = p_user_id)
       or v_platform not in ('google_play', 'app_store')
       or v_product_id is null
       or v_transaction_id is null
       or v_token_hash is null
       or v_plan not in ('standard', 'pro')
       or v_billing_period not in ('monthly', 'yearly')
       or v_subscription_key is null
       or p_expires_at is null
       or p_expires_at <= now()
       or (v_platform = 'app_store' and v_original_transaction_id is null)
       or (v_platform = 'google_play' and v_original_transaction_id is not null) then
        raise exception 'invalid_verified_purchase';
    end if;

    -- Keep the catalog invariant inside the same database boundary as the
    -- grant write. The provider and caller checks remain in the Edge Function.
    select product.plan, product.billing_period
      into catalog_row
      from public.subscription_products product
     where product.store_platform = v_platform
       and product.store_product_id = v_product_id
       and product.is_active
     limit 1;

    if catalog_row is null
       or catalog_row.plan <> v_plan
       or catalog_row.billing_period <> v_billing_period then
        raise exception 'unknown_product';
    end if;

    -- Serialize retries and cross-key races. All callers acquire locks in the
    -- same order, so a transaction-id collision cannot create a second grant.
    perform pg_advisory_xact_lock(hashtextextended(
        'store-purchase:subscription:' || v_platform || ':' || v_subscription_key,
        0
    ));
    perform pg_advisory_xact_lock(hashtextextended(
        'store-purchase:transaction:' || v_platform || ':' || v_transaction_id,
        0
    ));
    perform pg_advisory_xact_lock(hashtextextended(
        'store-purchase:token:' || v_platform || ':' || v_token_hash,
        0
    ));

    -- Failed rows remain replaceable, as in the existing failed-poisoning
    -- protection. Verified and pending rows claim the token for their user.
    select verification.*
      into token_row
      from public.store_purchase_verifications verification
     where verification.store_platform = v_platform
       and verification.purchase_token_hash = v_token_hash
     order by case verification.status
                  when 'verified' then 0
                  when 'pending' then 1
                  else 2
              end,
              verification.updated_at desc,
              verification.id
     limit 1
     for update;

    if token_row.id is not null
       and token_row.user_id <> p_user_id
       and token_row.status <> 'failed' then
        raise exception 'purchase_already_claimed';
    end if;

    if v_original_transaction_id is not null then
        select verification.*
          into original_row
          from public.store_purchase_verifications verification
         where verification.store_platform = v_platform
           and verification.original_transaction_id = v_original_transaction_id
           and verification.status = 'verified'
         limit 1
         for update;

        if original_row.id is not null
           and original_row.user_id <> p_user_id then
            raise exception 'purchase_already_claimed';
        end if;
    end if;

    if token_row.id is not null
       and original_row.id is not null
       and token_row.id <> original_row.id then
        raise exception 'purchase_state_conflict';
    end if;

    if token_row.id is not null then
        verification_row := token_row;
    elsif original_row.id is not null then
        verification_row := original_row;
    end if;

    -- The existing provider idempotency keys are subscription_key and
    -- transaction_id. Reject another user's ownership before touching either
    -- the verification or grant row.
    select grant_record.user_id
      into conflicting_user
      from public.user_entitlement_grants grant_record
     where grant_record.source = 'store_subscription'
       and grant_record.store_platform = v_platform
       and grant_record.user_id <> p_user_id
       and (
           grant_record.store_subscription_key = v_subscription_key
           or grant_record.store_transaction_id = v_transaction_id
       )
     limit 1
     for update;

    if conflicting_user is not null then
        raise exception 'purchase_already_claimed';
    end if;

    select count(distinct grant_record.id)
      into matching_grant_count
      from public.user_entitlement_grants grant_record
     where grant_record.source = 'store_subscription'
       and grant_record.user_id = p_user_id
       and grant_record.store_platform = v_platform
       and (
           grant_record.store_subscription_key = v_subscription_key
           or grant_record.store_transaction_id = v_transaction_id
       );

    if matching_grant_count > 1 then
        raise exception 'purchase_state_conflict';
    end if;

    select grant_record.*
      into grant_row
      from public.user_entitlement_grants grant_record
     where grant_record.source = 'store_subscription'
       and grant_record.user_id = p_user_id
       and grant_record.store_platform = v_platform
       and (
           grant_record.store_subscription_key = v_subscription_key
           or grant_record.store_transaction_id = v_transaction_id
       )
     limit 1
     for update;

    -- A legacy verification can already carry the subscription grant even if
    -- the legacy row did not yet have the new idempotency key populated.
    if grant_row.id is null and verification_row.grant_id is not null then
        select grant_record.*
          into linked_grant_row
          from public.user_entitlement_grants grant_record
         where grant_record.id = verification_row.grant_id
         for update;

        if linked_grant_row.id is not null then
            if linked_grant_row.user_id <> p_user_id then
                raise exception 'purchase_already_claimed';
            end if;
            if linked_grant_row.source <> 'store_subscription'
               or linked_grant_row.store_platform <> v_platform then
                raise exception 'purchase_state_conflict';
            end if;
            grant_row := linked_grant_row;
        end if;
    end if;

    if grant_row.id is null then
        begin
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
                v_plan,
                'store_subscription',
                v_platform,
                v_product_id,
                v_billing_period,
                v_transaction_id,
                v_subscription_key,
                now(),
                p_expires_at,
                'active'
            )
            returning * into grant_row;
        exception
            when unique_violation then
                raise exception 'purchase_already_claimed';
        end;
    else
        update public.user_entitlement_grants
           set plan = v_plan,
               store_product_id = v_product_id,
               billing_period = v_billing_period,
               store_transaction_id = v_transaction_id,
               store_subscription_key = v_subscription_key,
               expires_at = p_expires_at,
               status = 'active'
         where id = grant_row.id
         returning * into grant_row;
    end if;

    if verification_row.id is null then
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
            v_platform,
            v_product_id,
            v_transaction_id,
            v_token_hash,
            v_original_transaction_id,
            v_plan,
            v_billing_period,
            'verified',
            grant_row.id,
            p_expires_at,
            now()
        )
        returning * into verification_row;
    else
        update public.store_purchase_verifications
           set user_id = p_user_id,
               store_platform = v_platform,
               store_product_id = v_product_id,
               store_transaction_id = v_transaction_id,
               purchase_token_hash = v_token_hash,
               original_transaction_id = v_original_transaction_id,
               plan = v_plan,
               billing_period = v_billing_period,
               status = 'verified',
               failure_reason = null,
               grant_id = grant_row.id,
               expires_at = p_expires_at,
               verified_at = now()
         where id = verification_row.id
         returning * into verification_row;
    end if;

    -- The provider transaction is the idempotency key for this verification
    -- event. A retry may reuse the existing audit row but never creates a
    -- second event for the same provider event.
    select audit_event.user_id
      into audit_owner
      from public.store_purchase_audit_events audit_event
     where audit_event.provider = v_platform
       and audit_event.provider_event_id = v_transaction_id
       and audit_event.event_type = 'verified'
     for update;

    if audit_owner is not null and audit_owner <> p_user_id then
        raise exception 'purchase_already_claimed';
    end if;

    insert into public.store_purchase_audit_events (
        event_type,
        provider,
        provider_event_id,
        user_id,
        verification_id,
        grant_id,
        store_product_id,
        store_transaction_id,
        store_subscription_key,
        expires_at,
        detail
    ) values (
        'verified',
        v_platform,
        v_transaction_id,
        p_user_id,
        verification_row.id,
        grant_row.id,
        v_product_id,
        v_transaction_id,
        v_subscription_key,
        p_expires_at,
        jsonb_build_object(
            'plan', v_plan,
            'billing_period', v_billing_period,
            'original_transaction_id', v_original_transaction_id
        )
    )
    on conflict (provider, provider_event_id, event_type) do nothing;

    verification_id := verification_row.id;
    grant_id := grant_row.id;
    return next;
end;
$$;

revoke all on function public.complete_store_purchase_verification(
    uuid, text, text, text, text, text, text, text, text, timestamptz
) from public, anon, authenticated;
grant execute on function public.complete_store_purchase_verification(
    uuid, text, text, text, text, text, text, text, text, timestamptz
) to service_role;
