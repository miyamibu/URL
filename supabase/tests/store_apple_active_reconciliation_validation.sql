\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;

select extensions.plan(8);

select extensions.ok(
    to_regprocedure('public.apply_store_subscription_reconciliation(uuid,text,text,text,text,text,timestamptz,timestamptz,text)') is not null,
    'provider-aware active reconciliation RPC exists'
);

select extensions.ok(
    to_regprocedure('public.apply_store_subscription_reconciliation(uuid,text,text,text,text,text,timestamptz)') is not null,
    'legacy seven-argument reconciliation RPC remains available'
);

select extensions.ok(
    has_function_privilege(
        'service_role',
        'public.apply_store_subscription_reconciliation(uuid,text,text,text,text,text,timestamptz,timestamptz,text)',
        'execute'
    ),
    'service_role can apply provider-aware active reconciliation'
);

select extensions.ok(
    not has_function_privilege(
        'authenticated',
        'public.apply_store_subscription_reconciliation(uuid,text,text,text,text,text,timestamptz,timestamptz,text)',
        'execute'
    ),
    'authenticated cannot apply provider-aware active reconciliation'
);

select extensions.ok(
    position(
        'app_store_server_api' in lower(
            pg_get_functiondef(
                'public.apply_store_subscription_reconciliation(uuid,text,text,text,text,text,timestamptz,timestamptz,text)'::regprocedure
            )
        )
    ) > 0,
    'Apple reconciliation records the provider source'
);

select extensions.ok(
    position(
        'apple_signed_transaction_hash_required' in lower(
            pg_get_functiondef(
                'public.apply_store_subscription_reconciliation(uuid,text,text,text,text,text,timestamptz,timestamptz,text)'::regprocedure
            )
        )
    ) > 0,
    'Apple reconciliation requires a normalized signed transaction hash'
);

begin;

do $$
declare
    owner_id uuid := gen_random_uuid();
    suffix text := replace(gen_random_uuid()::text, '-', '');
    original_id text := 'apple-original-' || suffix;
    initial_transaction_id text := 'apple-transaction-initial-' || suffix;
    latest_transaction_id text := 'apple-transaction-latest-' || suffix;
    raw_initial_jws text := 'fixture-apple-initial-jws-' || suffix;
    latest_jws_hash text := encode(digest('fixture-apple-latest-jws-' || suffix, 'sha256'), 'hex');
    grant_id uuid;
    event_id uuid;
    result_row record;
    new_expiry timestamptz := now() + interval '3 days';
begin
    insert into auth.users (id)
    values (owner_id);

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
        owner_id,
        'standard',
        'store_subscription',
        'app_store',
        'urlsaver.standard.monthly',
        'monthly',
        initial_transaction_id,
        'app_store:' || original_id,
        now() - interval '1 hour',
        now() + interval '1 day',
        'active'
    ) returning id into grant_id;

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
        owner_id,
        'app_store',
        'urlsaver.standard.monthly',
        initial_transaction_id,
        encode(digest(raw_initial_jws, 'sha256'), 'hex'),
        original_id,
        'standard',
        'monthly',
        'verified',
        grant_id,
        now() + interval '1 day',
        now()
    );

    select applied.event_id into event_id
      from public.apply_store_subscription_notification(
          'app_store',
          'fixture-apple-notification-' || suffix,
          'fixture-apple-event-' || suffix,
          'SUBSCRIBED',
          'app_store:' || original_id,
          encode(digest(raw_initial_jws, 'sha256'), 'hex'),
          initial_transaction_id,
          'urlsaver.standard.monthly',
          owner_id,
          'activate',
          'active',
          now() + interval '1 day',
          now(),
          true,
          jsonb_build_object('fixture', true)
      ) applied;

    select * into result_row
      from public.apply_store_subscription_reconciliation(
          event_id,
          null,
          latest_transaction_id,
          'urlsaver.standard.monthly',
          'activate',
          'active',
          new_expiry,
          now(),
          latest_jws_hash
      );

    if result_row.result <> 'applied' then
        raise exception 'Apple active reconciliation did not apply: %', result_row.failure_reason;
    end if;
    if (select expires_at from public.user_entitlement_grants where id = grant_id) <> new_expiry then
        raise exception 'Apple active reconciliation did not update grant expiry';
    end if;
    if not exists (
        select 1
          from public.store_subscription_states
         where provider = 'app_store'
           and subscription_key = 'app_store:' || original_id
           and expires_at = new_expiry
    ) then
        raise exception 'Apple active reconciliation did not update durable state';
    end if;
    if not exists (
        select 1
          from public.store_subscription_notification_events
         where id = result_row.event_id
           and event_type = 'reconciliation'
           and detail->>'source' = 'app_store_server_api'
           and detail->>'signed_transaction_info_sha256' = latest_jws_hash
           and detail::text not like '%' || raw_initial_jws || '%'
    ) then
        raise exception 'Apple reconciliation event is not durable or contains raw JWS';
    end if;
end
$$;

select extensions.pass('Apple current state is reflected in the durable grant and state tables');
select extensions.pass('Apple reconciliation stores only the signed transaction hash, never raw JWS');

select * from extensions.finish();
rollback;
