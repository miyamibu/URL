\set ON_ERROR_STOP on

select extensions.plan(15);

select extensions.ok(exists (
    select 1 from information_schema.columns
    where table_schema = 'public'
      and table_name = 'contact_support_requests'
      and column_name = 'idempotency_key_hash'
), 'contact support stores an idempotency hash');
select extensions.ok(to_regprocedure('public.reserve_contact_support_request(text,text,text,text,text,text,text,text,boolean)') is not null,
    'contact support has an atomic reservation function');
select extensions.ok(position('pg_advisory_xact_lock' in pg_get_functiondef(
    'public.reserve_contact_support_request(text,text,text,text,text,text,text,text,boolean)'::regprocedure
)) > 0, 'contact support reservation serializes rate-limit buckets');
select extensions.ok(not has_function_privilege(
    'authenticated', 'public.reserve_contact_support_request(text,text,text,text,text,text,text,text,boolean)', 'execute'
), 'authenticated cannot reserve support rows directly');
select extensions.ok(to_regprocedure('public.record_contact_support_delivery_event(text,text,text,text,timestamptz,text)') is not null,
    'contact support has a provider-event idempotency function');
select extensions.ok(position('on conflict (provider, provider_event_id)' in pg_get_functiondef(
    'public.record_contact_support_delivery_event(text,text,text,text,timestamptz,text)'::regprocedure
)) > 0, 'provider delivery events are idempotent');

select extensions.ok(to_regprocedure('public.reserve_store_purchase_attempt(uuid)') is not null,
    'purchase verification has a rate reservation function');
select extensions.ok(to_regprocedure('public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)') is not null,
    'purchase verification has an atomic completion function');
select extensions.ok(position('user_entitlement_grants' in pg_get_functiondef(
    'public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)'::regprocedure
)) > 0, 'purchase completion owns the grant write');
select extensions.ok(position('store_purchase_verifications' in pg_get_functiondef(
    'public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)'::regprocedure
)) > 0, 'purchase completion owns the verification write');
select extensions.ok(not has_function_privilege(
    'authenticated', 'public.complete_store_purchase_verification(uuid,text,text,text,text,text,text,text,text,timestamptz)', 'execute'
), 'authenticated cannot complete purchase grants directly');

select extensions.ok(to_regprocedure('public.bootstrap_first_admin(uuid,text)') is not null,
    'admin bootstrap uses a dedicated guarded function');
select extensions.ok(position('admin_users' in pg_get_functiondef(
    'public.bootstrap_first_admin(uuid,text)'::regprocedure
)) > 0, 'admin bootstrap is bound to the admin table');
select extensions.ok(position('pg_advisory_xact_lock' in pg_get_functiondef(
    'public.bootstrap_first_admin(uuid,text)'::regprocedure
)) > 0, 'admin bootstrap is race-safe');
select extensions.ok(not has_function_privilege(
    'authenticated', 'public.bootstrap_first_admin(uuid,text)', 'execute'
), 'authenticated cannot call admin bootstrap');
