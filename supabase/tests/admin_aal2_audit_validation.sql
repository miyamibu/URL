\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;

begin;
select extensions.plan(14);

select extensions.ok(
    to_regprocedure('public.bootstrap_first_admin(uuid,text)') is not null,
    'service-side first-admin bootstrap RPC exists'
);

select extensions.ok(
    has_function_privilege('service_role', 'public.bootstrap_first_admin(uuid,text)', 'execute')
    and not has_function_privilege('authenticated', 'public.bootstrap_first_admin(uuid,text)', 'execute'),
    'first-admin bootstrap is service-role-only'
);

select extensions.ok(
    position('pg_advisory_xact_lock' in lower(
        pg_get_functiondef('public.bootstrap_first_admin(uuid,text)'::regprocedure)
    )) > 0,
    'first-admin bootstrap serializes the empty-table check'
);

select extensions.ok(
    position('admin_bootstrap_closed' in pg_get_functiondef(
        'public.bootstrap_first_admin(uuid,text)'::regprocedure
    )) > 0,
    'first-admin bootstrap fails closed after the first admin'
);

select extensions.ok(
    to_regprocedure('public.record_admin_audit(uuid,text,text,uuid,jsonb,jsonb,uuid,text,jsonb)') is not null,
    'audited admin RPC exists'
);

select extensions.ok(
    has_function_privilege(
        'service_role',
        'public.admin_update_support_request(uuid,uuid,text,text,text,uuid,jsonb)',
        'execute'
    ),
    'service_role can execute support audit RPC'
);

select extensions.ok(
    not has_function_privilege(
        'authenticated',
        'public.admin_update_support_request(uuid,uuid,text,text,text,uuid,jsonb)',
        'execute'
    ),
    'authenticated cannot execute support audit RPC'
);

select extensions.ok(
    has_table_privilege('service_role', 'public.admin_operation_idempotency', 'SELECT')
    and has_table_privilege('service_role', 'public.admin_operation_idempotency', 'INSERT')
    and has_table_privilege('service_role', 'public.admin_operation_idempotency', 'UPDATE'),
    'service_role has the required idempotency privileges'
);

select extensions.ok(
    not has_table_privilege('service_role', 'public.admin_operation_idempotency', 'DELETE')
    and not has_table_privilege('service_role', 'public.admin_operation_idempotency', 'TRUNCATE')
    and not has_table_privilege('service_role', 'public.admin_operation_idempotency', 'TRIGGER'),
    'service_role does not have destructive idempotency privileges'
);

select extensions.ok(
    exists (
        select 1
        from pg_indexes
        where schemaname = 'public'
          and tablename = 'admin_audit_logs'
          and indexname = 'idx_admin_audit_logs_operation_action_unique'
          and indexdef ilike 'create unique index%'
    ),
    'admin operation audit identity is unique'
);

select extensions.ok(
    position('role not in (''owner'', ''moderator'')' in lower(
        pg_get_functiondef('public.admin_update_support_request(uuid,uuid,text,text,text,uuid,jsonb)'::regprocedure)
    )) > 0,
    'support RPC enforces its role capability'
);

select extensions.ok(
    position('role not in (''owner'', ''billing'')' in lower(
        pg_get_functiondef('public.admin_revoke_promo_invite_code_audited(uuid,uuid,uuid,text,uuid,jsonb,timestamptz)'::regprocedure)
    )) > 0,
    'promo revoke RPC enforces its role capability'
);

do $$
declare
    owner_user_id uuid := gen_random_uuid();
    readonly_user_id uuid := gen_random_uuid();
    owner_admin_id uuid := gen_random_uuid();
    readonly_admin_id uuid := gen_random_uuid();
    support_id uuid := gen_random_uuid();
    operation_id uuid := gen_random_uuid();
begin
    insert into auth.users (id, email)
    values
        (owner_user_id, 'admin-owner-fixture@example.invalid'),
        (readonly_user_id, 'admin-readonly-fixture@example.invalid');

    insert into public.admin_users (id, user_id, email, role, status)
    values
        (owner_admin_id, owner_user_id, 'admin-owner-fixture@example.invalid', 'owner', 'active'),
        (readonly_admin_id, readonly_user_id, 'admin-readonly-fixture@example.invalid', 'readonly', 'active');

    perform public.bootstrap_first_admin(
        owner_user_id,
        'admin-owner-fixture@example.invalid'
    );

    insert into public.contact_support_requests (
        id, request_id, email_hash, ip_hash, platform, app_version, build_type, is_signed_in,
        source, idempotency_key
    ) values (
        support_id,
        'admin-fixture-' || replace(support_id::text, '-', ''),
        encode(digest(owner_user_id::text, 'sha256'), 'hex'),
        encode(digest(readonly_user_id::text, 'sha256'), 'hex'),
        'ios',
        'fixture',
        'debug',
        false,
        'admin-runtime-test',
        'admin-runtime-test-' || replace(support_id::text, '-', '')
    );

    perform public.admin_update_support_request(
        support_id,
        owner_admin_id,
        'in_progress',
        'fixture note',
        'runtime validation',
        operation_id,
        '{"aal":"aal2","methods":["mfa/totp"]}'::jsonb
    );

    if (select support_status from public.contact_support_requests where id = support_id) <> 'in_progress' then
        raise exception 'owner support operation did not apply';
    end if;

    begin
        perform public.admin_update_support_request(
            support_id,
            owner_admin_id,
            'resolved',
            'duplicate fixture note',
            'runtime validation',
            operation_id,
            '{"aal":"aal2","methods":["mfa/totp"]}'::jsonb
        );
        raise exception 'duplicate operation unexpectedly succeeded';
    exception
        when unique_violation then null;
    end;

    if (select support_status from public.contact_support_requests where id = support_id) <> 'in_progress' then
        raise exception 'duplicate operation was not rolled back atomically';
    end if;

    begin
        perform public.admin_update_support_request(
            support_id,
            readonly_admin_id,
            'resolved',
            'readonly fixture note',
            'runtime validation',
            gen_random_uuid(),
            '{"aal":"aal2","methods":["mfa/totp"]}'::jsonb
        );
        raise exception 'readonly operation unexpectedly succeeded';
    exception
        when others then
            if sqlerrm not like '%admin_capability_denied%' then
                raise;
            end if;
    end;
end
$$;

select extensions.pass('admin RPC applies once, rolls back duplicates, and rejects readonly writes');
select extensions.pass('runtime fixture completed without exposing authenticated execution');
select * from extensions.finish();
rollback;
