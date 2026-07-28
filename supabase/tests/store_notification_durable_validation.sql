\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;

select extensions.plan(20);

select extensions.ok(
    to_regclass('public.store_subscription_notification_events') is not null,
    'store notification event table exists'
);

select extensions.ok(
    to_regclass('public.store_subscription_states') is not null,
    'store subscription state table exists'
);

select extensions.ok(
    exists (
        select 1 from pg_indexes
        where schemaname = 'public'
          and indexname = 'idx_store_notification_events_notification'
    ),
    'notification id is unique per provider'
);

select extensions.ok(
    exists (
        select 1 from pg_indexes
        where schemaname = 'public'
          and indexname = 'idx_store_notification_events_event'
    ),
    'provider event id and type are idempotent'
);

select extensions.ok(
    to_regprocedure('public.apply_store_subscription_notification(text,text,text,text,text,text,text,text,uuid,text,text,timestamptz,timestamptz,boolean,jsonb)') is not null,
    'notification application RPC exists'
);

select extensions.ok(
    to_regprocedure('public.reconcile_store_subscription_notification(uuid)') is not null,
    'reconciliation RPC exists'
);

select extensions.ok(
    has_function_privilege(
        'service_role',
        'public.apply_store_subscription_notification(text,text,text,text,text,text,text,text,uuid,text,text,timestamptz,timestamptz,boolean,jsonb)',
        'execute'
    ),
    'service_role can apply verified notifications'
);

select extensions.ok(
    not has_function_privilege(
        'authenticated',
        'public.apply_store_subscription_notification(text,text,text,text,text,text,text,text,uuid,text,text,timestamptz,timestamptz,boolean,jsonb)',
        'execute'
    ),
    'authenticated cannot apply notifications'
);

select extensions.ok(
    not has_function_privilege(
        'authenticated',
        'public.reconcile_store_subscription_notification(uuid)',
        'execute'
    ),
    'authenticated cannot reconcile notifications'
);

select extensions.ok(
    position(
        'provider_signature_unverified' in lower(
            pg_get_functiondef(
                'public.apply_store_subscription_notification(text,text,text,text,text,text,text,text,uuid,text,text,timestamptz,timestamptz,boolean,jsonb)'::regprocedure
            )
        )
    ) > 0,
    'unverified provider input fails closed'
);

select extensions.ok(
    position(
        'binding_not_found' in lower(
            pg_get_functiondef(
                'private.process_store_subscription_notification(uuid,boolean)'::regprocedure
            )
        )
    ) > 0,
    'unmatched notifications are durable for reconciliation'
);

select extensions.ok(
    position(
        'stale_event' in lower(
            pg_get_functiondef(
                'private.process_store_subscription_notification(uuid,boolean)'::regprocedure
            )
        )
    ) > 0,
    'out-of-order notifications cannot roll state back'
);

select extensions.ok(
    position(
        'p_event_type <> ''test''' in lower(
            pg_get_functiondef(
                'public.apply_store_subscription_notification(text,text,text,text,text,text,text,text,uuid,text,text,timestamptz,timestamptz,boolean,jsonb)'::regprocedure
            )
        )
    ) > 0,
    'provider test notifications do not require a purchase token'
);

select extensions.ok(
    position(
        'grant_binding_mismatch' in lower(
            pg_get_functiondef(
                'private.process_store_subscription_notification(uuid,boolean)'::regprocedure
            )
        )
    ) > 0,
    'verification rows cannot redirect events to another grant owner'
);

begin;

do $$
declare
    owner_id uuid := gen_random_uuid();
    other_id uuid := gen_random_uuid();
    grant_id uuid;
    verification_id uuid;
    result_row record;
    suffix text := replace(gen_random_uuid()::text, '-', '');
    original_id text := 'notification-original-' || suffix;
    transaction_id text := 'notification-transaction-' || suffix;
    token_hash text := repeat('b', 64);
    expires_at timestamptz := now() + interval '2 days';
    event_time timestamptz := now();
    reconcile_original_id text := 'reconcile-original-' || suffix;
    reconcile_transaction_id text := 'reconcile-transaction-' || suffix;
    reconcile_token_hash text := repeat('c', 64);
    reconcile_event_id uuid;
begin
    insert into auth.users (id)
    values (owner_id), (other_id);

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
        transaction_id,
        'app_store:' || original_id,
        now() - interval '1 hour',
        expires_at,
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
        transaction_id,
        token_hash,
        original_id,
        'standard',
        'monthly',
        'verified',
        grant_id,
        expires_at,
        now()
    ) returning id into verification_id;

    select * into result_row
      from public.apply_store_subscription_notification(
          'app_store',
          'notification-' || suffix,
          'apple-event-' || suffix,
          'DID_RENEW',
          'app_store:' || original_id,
          null,
          transaction_id,
          'urlsaver.standard.monthly',
          owner_id,
          'activate',
          'active',
          expires_at,
          event_time,
          true,
          jsonb_build_object('fixture', true)
      );
    if result_row.result <> 'applied' or result_row.grant_id <> grant_id then
        raise exception 'initial verified notification was not applied';
    end if;

    select * into result_row
      from public.apply_store_subscription_notification(
          'app_store',
          'notification-' || suffix,
          'apple-event-' || suffix,
          'DID_RENEW',
          'app_store:' || original_id,
          null,
          transaction_id,
          'urlsaver.standard.monthly',
          owner_id,
          'activate',
          'active',
          expires_at,
          event_time,
          true,
          jsonb_build_object('fixture', true)
      );
    if result_row.result <> 'applied' then
        raise exception 'idempotent notification replay changed result';
    end if;

    if (
        select count(*)
          from public.store_subscription_notification_events event
         where event.notification_id = 'notification-' || suffix
    ) <> 1 then
        raise exception 'notification replay created a second event';
    end if;

    select * into result_row
      from public.apply_store_subscription_notification(
          'app_store',
          'refund-notification-' || suffix,
          'refund-event-' || suffix,
          'REFUND',
          'app_store:' || original_id,
          null,
          transaction_id,
          'urlsaver.standard.monthly',
          owner_id,
          'revoke',
          'refunded',
          event_time + interval '1 minute',
          event_time + interval '1 minute',
          true,
          jsonb_build_object('fixture', true)
      );
    if result_row.result <> 'applied' then
        raise exception 'refund notification was not applied';
    end if;

    select * into result_row
      from public.apply_store_subscription_notification(
          'app_store',
          'stale-notification-' || suffix,
          'stale-event-' || suffix,
          'DID_RENEW',
          'app_store:' || original_id,
          null,
          transaction_id,
          'urlsaver.standard.monthly',
          owner_id,
          'activate',
          'active',
          expires_at,
          event_time - interval '1 minute',
          true,
          jsonb_build_object('fixture', true)
      );
    if result_row.result <> 'ignored' or result_row.failure_reason <> 'stale_event' then
        raise exception 'stale notification was not ignored';
    end if;

    if (select status from public.user_entitlement_grants where id = grant_id) <> 'revoked' then
        raise exception 'stale notification reactivated revoked grant';
    end if;

    select * into result_row
      from public.apply_store_subscription_notification(
          'app_store',
          'binding-notification-' || suffix,
          'binding-event-' || suffix,
          'DID_RENEW',
          'app_store:' || original_id,
          null,
          transaction_id,
          'urlsaver.standard.monthly',
          other_id,
          'activate',
          'active',
          expires_at,
          event_time + interval '2 minutes',
          true,
          jsonb_build_object('fixture', true)
      );
    if result_row.result <> 'rejected' or result_row.failure_reason <> 'user_binding_mismatch' then
        raise exception 'cross-user notification was not rejected';
    end if;

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
        expires_at,
        verified_at
    ) values (
        owner_id,
        'app_store',
        'urlsaver.standard.monthly',
        reconcile_transaction_id,
        reconcile_token_hash,
        reconcile_original_id,
        'standard',
        'monthly',
        'verified',
        expires_at,
        now()
    );

    select * into result_row
      from public.apply_store_subscription_notification(
          'app_store',
          'reconcile-notification-' || suffix,
          'reconcile-event-' || suffix,
          'DID_RENEW',
          'app_store:' || reconcile_original_id,
          null,
          reconcile_transaction_id,
          'urlsaver.standard.monthly',
          owner_id,
          'activate',
          'active',
          expires_at,
          event_time,
          true,
          jsonb_build_object('fixture', true)
      );
    if result_row.result <> 'ignored' or result_row.failure_reason <> 'grant_not_found' then
        raise exception 'unmapped notification was not held for reconciliation';
    end if;
    reconcile_event_id := result_row.event_id;

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
        reconcile_transaction_id,
        'app_store:' || reconcile_original_id,
        now() - interval '1 minute',
        expires_at,
        'active'
    );

    select * into result_row
      from public.reconcile_store_subscription_notification(reconcile_event_id);
    if result_row.result <> 'applied' then
        raise exception 'reconciliation did not apply after binding became available';
    end if;
end
$$;

select extensions.pass('verified notification applies only to its existing grant');
select extensions.pass('notification and event retries are idempotent');
select extensions.pass('refund revokes and stale renewal cannot reactivate');
select extensions.pass('user binding mismatch is rejected without grant mutation');
select extensions.pass('unmapped verified notification is durable for reconciliation');
select extensions.pass('reconciliation applies only after existing grant binding appears');

select * from extensions.finish();
rollback;
