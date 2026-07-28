-- Active Store reconciliation contract.
--
-- The Edge Function performs the provider lookup. This migration only accepts
-- the SHA-256 purchase-token binding and provider-normalized state, so a raw
-- Google purchase token can never be stored by these RPCs.

create or replace function public.prepare_store_subscription_reconciliation(
    p_event_row_id uuid,
    p_purchase_token_hash text default null,
    p_store_transaction_id text default null
)
returns table (
    event_id uuid,
    provider text,
    subscription_key text,
    purchase_token_hash text,
    store_product_id text,
    store_transaction_id text,
    original_transaction_id text,
    user_id uuid,
    binding_status text,
    failure_reason text
)
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    event_row public.store_subscription_notification_events%rowtype;
    verification_row public.store_purchase_verifications%rowtype;
    normalized_token_hash text := lower(nullif(trim(coalesce(p_purchase_token_hash, '')), ''));
    original_id text;
begin
    select notification_event.*
      into event_row
      from public.store_subscription_notification_events notification_event
     where notification_event.id = p_event_row_id
     for update;

    if event_row.id is null then
        raise exception 'store_notification_not_found';
    end if;

    if not event_row.signature_verified then
        raise exception 'provider_signature_unverified';
    end if;

    if event_row.provider = 'google_play' then
        if normalized_token_hash is null or normalized_token_hash !~ '^[0-9a-f]{64}$' then
            raise exception 'google_purchase_token_binding_required';
        end if;

        if event_row.purchase_token_hash is null
           or event_row.purchase_token_hash <> normalized_token_hash then
            raise exception 'google_purchase_token_binding_mismatch';
        end if;

        select verification.*
          into verification_row
          from public.store_purchase_verifications verification
         where verification.store_platform = 'google_play'
           and verification.purchase_token_hash = normalized_token_hash
           and verification.status = 'verified'
         order by verification.verified_at desc nulls last, verification.updated_at desc
         limit 1
         for update;

        if verification_row.id is null then
            raise exception 'google_purchase_binding_not_found';
        end if;

        if event_row.store_product_id is not null
           and event_row.store_product_id <> verification_row.store_product_id then
            raise exception 'product_binding_mismatch';
        end if;

        return query
        select event_row.id,
               event_row.provider,
               event_row.subscription_key,
               verification_row.purchase_token_hash,
               verification_row.store_product_id,
               verification_row.store_transaction_id,
               verification_row.original_transaction_id,
               verification_row.user_id,
               'verified'::text,
               null::text;
        return;
    end if;

    if event_row.provider <> 'app_store' then
        raise exception 'store_provider_invalid';
    end if;

    if nullif(trim(coalesce(p_store_transaction_id, '')), '') is not null
       and event_row.store_transaction_id is not null
       and event_row.store_transaction_id <> trim(p_store_transaction_id) then
        raise exception 'apple_transaction_binding_mismatch';
    end if;

    original_id := nullif(
        regexp_replace(event_row.subscription_key, '^app_store:', ''),
        event_row.subscription_key
    );

    select verification.*
      into verification_row
      from public.store_purchase_verifications verification
     where verification.store_platform = 'app_store'
       and verification.status = 'verified'
       and (
           verification.original_transaction_id = original_id
           or verification.store_transaction_id = event_row.store_transaction_id
       )
     order by verification.verified_at desc nulls last, verification.updated_at desc
     limit 1
     for update;

    if verification_row.id is null then
        return query
        select event_row.id,
               event_row.provider,
               event_row.subscription_key,
               null::text,
               event_row.store_product_id,
               event_row.store_transaction_id,
               original_id,
               event_row.user_id,
               'not_verified'::text,
               'apple_transaction_binding_not_found'::text;
        return;
    end if;

    if event_row.store_product_id is not null
       and event_row.store_product_id <> verification_row.store_product_id then
        raise exception 'product_binding_mismatch';
    end if;

    return query
    select event_row.id,
           event_row.provider,
           event_row.subscription_key,
           null::text,
           coalesce(event_row.store_product_id, verification_row.store_product_id),
           coalesce(event_row.store_transaction_id, verification_row.store_transaction_id),
           verification_row.original_transaction_id,
           verification_row.user_id,
           'verified'::text,
           'apple_current_store_lookup_not_implemented'::text;
end;
$$;

revoke all on function public.prepare_store_subscription_reconciliation(uuid, text, text)
    from public, anon, authenticated;
grant execute on function public.prepare_store_subscription_reconciliation(uuid, text, text)
    to service_role;

create or replace function public.apply_store_subscription_reconciliation(
    p_event_row_id uuid,
    p_purchase_token_hash text,
    p_store_transaction_id text,
    p_store_product_id text,
    p_action text,
    p_subscription_state text,
    p_expires_at timestamptz
)
returns table (
    event_id uuid,
    result text,
    grant_id uuid,
    user_id uuid,
    failure_reason text,
    grant_changed boolean
)
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    event_row public.store_subscription_notification_events%rowtype;
    verification_row public.store_purchase_verifications%rowtype;
    applied_row record;
    normalized_token_hash text := lower(nullif(trim(coalesce(p_purchase_token_hash, '')), ''));
    normalized_transaction_id text := nullif(trim(coalesce(p_store_transaction_id, '')), '');
    normalized_product_id text := nullif(trim(coalesce(p_store_product_id, '')), '');
    reconciliation_id text;
begin
    select notification_event.*
      into event_row
      from public.store_subscription_notification_events notification_event
     where notification_event.id = p_event_row_id
     for update;

    if event_row.id is null then
        raise exception 'store_notification_not_found';
    end if;
    if event_row.provider <> 'google_play' then
        raise exception 'google_active_reconciliation_not_supported';
    end if;
    if not event_row.signature_verified then
        raise exception 'provider_signature_unverified';
    end if;
    if normalized_token_hash is null or normalized_token_hash !~ '^[0-9a-f]{64}$' then
        raise exception 'google_purchase_token_binding_required';
    end if;
    if event_row.purchase_token_hash is distinct from normalized_token_hash then
        raise exception 'google_purchase_token_binding_mismatch';
    end if;
    if normalized_product_id is null or length(normalized_product_id) > 256 then
        raise exception 'google_product_binding_required';
    end if;
    if normalized_transaction_id is not null and length(normalized_transaction_id) > 256 then
        raise exception 'google_transaction_binding_invalid';
    end if;
    if p_action not in ('activate', 'revoke', 'preserve', 'noop') then
        raise exception 'store_reconciliation_action_invalid';
    end if;
    if p_subscription_state not in (
        'active', 'billing_retry', 'grace_period', 'on_hold', 'paused',
        'canceled', 'revoked', 'refunded', 'expired', 'pending', 'unsupported'
    ) then
        raise exception 'store_reconciliation_state_invalid';
    end if;

    select verification.*
      into verification_row
      from public.store_purchase_verifications verification
     where verification.store_platform = 'google_play'
       and verification.purchase_token_hash = normalized_token_hash
       and verification.status = 'verified'
     order by verification.verified_at desc nulls last, verification.updated_at desc
     limit 1
     for update;

    if verification_row.id is null then
        raise exception 'google_purchase_binding_not_found';
    end if;
    if event_row.store_product_id is not null
       and event_row.store_product_id <> verification_row.store_product_id then
        raise exception 'product_binding_mismatch';
    end if;
    if normalized_product_id <> verification_row.store_product_id then
        raise exception 'product_binding_mismatch';
    end if;
    if p_action = 'activate' and (p_expires_at is null or p_expires_at <= now()) then
        raise exception 'authoritative_expiry_required';
    end if;

    reconciliation_id := md5(concat_ws(
        chr(31),
        event_row.id::text,
        normalized_token_hash,
        normalized_product_id,
        coalesce(normalized_transaction_id, ''),
        p_action,
        p_subscription_state,
        coalesce(p_expires_at::text, '')
    ));

    select *
      into applied_row
      from public.apply_store_subscription_notification(
          'google_play',
          'store-reconciliation:' || reconciliation_id,
          'store-reconciliation:' || reconciliation_id,
          'reconciliation',
          event_row.subscription_key,
          normalized_token_hash,
          normalized_transaction_id,
          normalized_product_id,
          null,
          p_action,
          p_subscription_state,
          p_expires_at,
          clock_timestamp(),
          true,
          jsonb_build_object(
              'reconciliation', true,
              'source', 'google_play_subscriptionsv2'
          )
      );

    -- The same authoritative snapshot is idempotent. If the first attempt
    -- lacked a grant, allow a later request to retry the durable event.
    if applied_row.result = 'ignored'
       and applied_row.failure_reason in ('binding_not_found', 'grant_not_found') then
        select *
          into applied_row
          from private.process_store_subscription_notification(applied_row.event_id, true);
    end if;

    return query
    select applied_row.event_id,
           applied_row.result,
           applied_row.grant_id,
           applied_row.user_id,
           applied_row.failure_reason,
           applied_row.grant_changed;
end;
$$;

revoke all on function public.apply_store_subscription_reconciliation(
    uuid, text, text, text, text, text, timestamptz
) from public, anon, authenticated;
grant execute on function public.apply_store_subscription_reconciliation(
    uuid, text, text, text, text, text, timestamptz
) to service_role;
