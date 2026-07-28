\set ON_ERROR_STOP on

begin;

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(12);

select extensions.ok(
    to_regclass('public.store_purchase_audit_events') is not null,
    'purchase verification has an audit event table'
);

select extensions.ok(
    to_regprocedure('public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)') is not null,
    'purchase verification has an atomic completion RPC'
);

select extensions.ok(
    position('pg_advisory_xact_lock' in lower(pg_get_functiondef(
        'public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)'::regprocedure
    ))) > 0,
    'completion RPC serializes purchase races'
);

select extensions.ok(
    position('store_purchase_audit_events' in lower(pg_get_functiondef(
        'public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)'::regprocedure
    ))) > 0,
    'completion RPC owns the audit event write'
);

select extensions.ok(
    position('user_entitlement_grants' in lower(pg_get_functiondef(
        'public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)'::regprocedure
    ))) > 0,
    'completion RPC owns the grant write'
);

select extensions.ok(
    position('store_purchase_verifications' in lower(pg_get_functiondef(
        'public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)'::regprocedure
    ))) > 0,
    'completion RPC owns the verification write'
);

select extensions.ok(
    position('on conflict (provider, provider_event_id, event_type)' in lower(pg_get_functiondef(
        'public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)'::regprocedure
    ))) > 0,
    'verified audit events are provider-event idempotent'
);

select extensions.ok(
    not has_function_privilege(
        'authenticated',
        'public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)',
        'execute'
    ),
    'authenticated cannot complete purchase grants directly'
);

select extensions.ok(
    has_function_privilege(
        'service_role',
        'public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)',
        'execute'
    ),
    'service_role can invoke the completion RPC'
);

select extensions.ok(
    exists (
        select 1
        from pg_indexes
        where schemaname = 'public'
          and tablename = 'store_purchase_verifications'
          and indexname = 'idx_store_purchase_verifications_token_hash'
    ),
    'purchase token hash is unique per provider'
);

select extensions.ok(
    exists (
        select 1
        from pg_indexes
        where schemaname = 'public'
          and tablename = 'store_purchase_audit_events'
          and indexdef like '%UNIQUE INDEX%provider, provider_event_id, event_type%'
    ),
    'audit event provider key is unique'
);

do $$
declare
    owner_id uuid := '00000000-0000-0000-0000-000000007001';
    other_id uuid := '00000000-0000-0000-0000-000000007002';
    verified_verification_id uuid;
    repeated_verification_id uuid;
    entitlement_grant_id uuid;
    repeated_grant_id uuid;
    row_count integer;
    expires_at timestamptz := now() + interval '1 day';
begin
    insert into auth.users (id)
    values (owner_id), (other_id)
    on conflict (id) do nothing;

    select result.verification_id, result.grant_id
      into verified_verification_id, entitlement_grant_id
      from public.complete_store_purchase_verification(
          owner_id,
          'app_store',
          'urlsaver.standard.monthly',
          'atomicity-transaction-001',
          repeat('a', 64),
          'standard',
          'monthly',
          'atomicity-original-001',
          'app_store:atomicity-original-001',
          expires_at
      ) result;

    if verified_verification_id is null or entitlement_grant_id is null then
        raise exception 'atomic completion returned null ids';
    end if;

    if (
        select count(*)
        from public.store_purchase_verifications verification
        where verification.id = verified_verification_id
          and verification.user_id = owner_id
          and verification.status = 'verified'
          and verification.grant_id = entitlement_grant_id
    ) <> 1 then
        raise exception 'verification row was not atomically linked to the grant';
    end if;

    if (
        select count(*)
        from public.user_entitlement_grants grant_record
        where grant_record.id = entitlement_grant_id
          and grant_record.user_id = owner_id
          and grant_record.source = 'store_subscription'
          and grant_record.store_subscription_key = 'app_store:atomicity-original-001'
          and grant_record.store_transaction_id = 'atomicity-transaction-001'
          and grant_record.status = 'active'
    ) <> 1 then
        raise exception 'grant was not created by atomic completion';
    end if;

    if (
        select count(*)
        from public.store_purchase_audit_events event
        where event.provider = 'app_store'
          and event.provider_event_id = 'atomicity-transaction-001'
          and event.user_id = owner_id
          and event.verification_id = verified_verification_id
          and event.grant_id = entitlement_grant_id
    ) <> 1 then
        raise exception 'verified audit event was not created';
    end if;

    select result.verification_id, result.grant_id
      into repeated_verification_id, repeated_grant_id
      from public.complete_store_purchase_verification(
          owner_id,
          'app_store',
          'urlsaver.standard.monthly',
          'atomicity-transaction-001',
          repeat('a', 64),
          'standard',
          'monthly',
          'atomicity-original-001',
          'app_store:atomicity-original-001',
          expires_at
      ) result;

    if repeated_verification_id <> verified_verification_id or repeated_grant_id <> entitlement_grant_id then
        raise exception 'idempotent retry did not reuse verification and grant';
    end if;

    if (
        select count(*)
        from public.store_purchase_audit_events event
        where event.provider = 'app_store'
          and event.provider_event_id = 'atomicity-transaction-001'
    ) <> 1 then
        raise exception 'idempotent retry duplicated the audit event';
    end if;

    begin
        perform public.complete_store_purchase_verification(
            other_id,
            'app_store',
            'urlsaver.standard.monthly',
            'atomicity-transaction-001',
            repeat('a', 64),
            'standard',
            'monthly',
            'atomicity-original-001',
            'app_store:atomicity-original-001',
            expires_at
        );
        raise exception 'cross-user purchase reuse was accepted';
    exception
        when others then
            if position('purchase_already_claimed' in sqlerrm) = 0 then
                raise;
            end if;
    end;

    select count(*) into row_count
      from public.user_entitlement_grants grant_record
     where grant_record.store_transaction_id = 'atomicity-transaction-001';
    if row_count <> 1 then
        raise exception 'cross-user conflict changed grant state';
    end if;
end
$$;

select extensions.pass('purchase completion writes verification, grant, binding, and audit atomically');
select * from extensions.finish();

rollback;
