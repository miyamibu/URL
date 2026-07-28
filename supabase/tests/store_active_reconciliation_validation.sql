\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;

select extensions.plan(10);

select extensions.ok(
    to_regprocedure('public.prepare_store_subscription_reconciliation(uuid,text,text)') is not null,
    'active reconciliation target RPC exists'
);

select extensions.ok(
    to_regprocedure('public.apply_store_subscription_reconciliation(uuid,text,text,text,text,text,timestamptz)') is not null,
    'active reconciliation apply RPC exists'
);

select extensions.ok(
    has_function_privilege(
        'service_role',
        'public.prepare_store_subscription_reconciliation(uuid,text,text)',
        'execute'
    ),
    'service_role can prepare active reconciliation'
);

select extensions.ok(
    has_function_privilege(
        'service_role',
        'public.apply_store_subscription_reconciliation(uuid,text,text,text,text,text,timestamptz)',
        'execute'
    ),
    'service_role can apply active reconciliation'
);

select extensions.ok(
    not has_function_privilege(
        'authenticated',
        'public.prepare_store_subscription_reconciliation(uuid,text,text)',
        'execute'
    ),
    'authenticated cannot prepare active reconciliation'
);

select extensions.ok(
    not has_function_privilege(
        'authenticated',
        'public.apply_store_subscription_reconciliation(uuid,text,text,text,text,text,timestamptz)',
        'execute'
    ),
    'authenticated cannot apply active reconciliation'
);

select extensions.ok(
    position(
        'google_purchase_token_binding_mismatch' in lower(
            pg_get_functiondef(
                'public.prepare_store_subscription_reconciliation(uuid,text,text)'::regprocedure
            )
        )
    ) > 0,
    'target preparation fails closed on a token hash mismatch'
);

select extensions.ok(
    position(
        'google_play_subscriptionsv2' in lower(
            pg_get_functiondef(
                'public.apply_store_subscription_reconciliation(uuid,text,text,text,text,text,timestamptz,timestamptz,text)'::regprocedure
            )
        )
    ) > 0,
    'active apply records the authoritative provider source without raw payload'
);

begin;

do $$
declare
    owner_id uuid := gen_random_uuid();
    suffix text := replace(gen_random_uuid()::text, '-', '');
    raw_token text := 'fixture-raw-purchase-token-' || suffix;
    token_hash text := encode(digest(raw_token, 'sha256'), 'hex');
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
        starts_at,
        expires_at,
        status
    ) values (
        owner_id,
        'standard',
        'store_subscription',
        'google_play',
        'urlsaver.standard.monthly',
        'monthly',
        'GPA.initial-' || suffix,
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
        'google_play',
        'urlsaver.standard.monthly',
        'GPA.initial-' || suffix,
        token_hash,
        null,
        'standard',
        'monthly',
        'verified',
        grant_id,
        now() + interval '1 day',
        now()
    );

    select applied.event_id into event_id
      from public.apply_store_subscription_notification(
          'google_play',
          'fixture-notification-' || suffix,
          'fixture-event-' || suffix,
          'subscription:2',
          'google_play:' || token_hash,
          token_hash,
          'GPA.initial-' || suffix,
          'urlsaver.standard.monthly',
          null,
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
          token_hash,
          'GPA.renewed-' || suffix,
          'urlsaver.standard.monthly',
          'activate',
          'active',
          new_expiry
      );

    if result_row.result <> 'applied' then
        raise exception 'active reconciliation did not apply: %', result_row.failure_reason;
    end if;
    if (select expires_at from public.user_entitlement_grants where id = grant_id) <> new_expiry then
        raise exception 'active reconciliation did not update grant expiry';
    end if;
    if not exists (
        select 1
          from public.store_subscription_states
         where provider = 'google_play'
           and subscription_key = 'google_play:' || token_hash
           and expires_at = new_expiry
    ) then
        raise exception 'active reconciliation did not update durable state';
    end if;
    if not exists (
        select 1
          from public.store_subscription_notification_events
         where id = result_row.event_id
           and event_type = 'reconciliation'
           and detail->>'source' = 'google_play_subscriptionsv2'
           and detail::text not like '%' || raw_token || '%'
    ) then
        raise exception 'active reconciliation event is not durable or contains raw token';
    end if;
    if exists (
        select 1
          from information_schema.columns
         where table_schema = 'public'
           and table_name = 'store_subscription_notification_events'
           and column_name in ('purchase_token', 'raw_purchase_token', 'raw_token')
    ) then
        raise exception 'durable notification table has a raw token column';
    end if;
end
$$;

select extensions.pass('Google current state is reflected in the durable grant and state tables');
select extensions.pass('raw purchase token is absent from durable reconciliation output and schema');

select * from extensions.finish();
rollback;
