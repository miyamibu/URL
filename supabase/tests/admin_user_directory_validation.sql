\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;

begin;
select extensions.plan(14);

select extensions.ok(
    to_regprocedure('public.admin_list_users(text,text,integer,integer)') is not null,
    'admin user directory list RPC exists'
);

select extensions.ok(
    to_regprocedure('public.admin_get_user(uuid)') is not null,
    'admin user detail RPC exists'
);

select extensions.ok(
    has_function_privilege('service_role', 'public.admin_list_users(text,text,integer,integer)', 'execute')
    and not has_function_privilege('authenticated', 'public.admin_list_users(text,text,integer,integer)', 'execute')
    and not has_function_privilege('anon', 'public.admin_list_users(text,text,integer,integer)', 'execute'),
    'directory list is service-role-only'
);

select extensions.ok(
    has_function_privilege('service_role', 'public.admin_get_user(uuid)', 'execute')
    and not has_function_privilege('authenticated', 'public.admin_get_user(uuid)', 'execute')
    and not has_function_privilege('anon', 'public.admin_get_user(uuid)', 'execute'),
    'user detail is service-role-only'
);

select extensions.ok(
    position('security definer' in lower(pg_get_functiondef(
        'public.admin_list_users(text,text,integer,integer)'::regprocedure
    ))) > 0,
    'directory list is a controlled security-definer boundary'
);

do $$
declare
    fixture_user_id uuid := gen_random_uuid();
begin
    insert into auth.users (id, email, created_at, last_sign_in_at)
    values (fixture_user_id, 'directory-fixture@example.invalid', now() - interval '2 days', now() - interval '1 hour');

    insert into public.user_profiles (user_id, display_name, auth_provider, last_seen_at)
    values (fixture_user_id, 'Directory Fixture', 'email', now() - interval '30 minutes');

    insert into public.user_entitlement_grants (user_id, plan, source, starts_at, expires_at, status)
    values (fixture_user_id, 'pro', 'admin_grant', now() - interval '1 day', now() + interval '30 days', 'active');

    insert into auth.users (id, email, created_at)
    values (gen_random_uuid(), 'directory-default-pro@example.invalid', now());
end
$$;

select extensions.is(
    (select count(*)::integer from public.admin_list_users('directory-fixture@example.invalid', null, 50, 0)),
    1,
    'directory search returns the matching user'
);

select extensions.is(
    (select current_plan from public.admin_list_users('Directory Fixture', 'active', 50, 0) limit 1),
    'pro',
    'directory search joins profile and active plan'
);

select extensions.is(
    (select current_plan from public.admin_list_users('directory-default-pro@example.invalid', null, 50, 0) limit 1),
    'pro',
    'directory reports users without a grant as Pro'
);

select extensions.is(
    (select jsonb_array_length(entitlement_grants) from public.admin_get_user(
        (select id from auth.users where email = 'directory-fixture@example.invalid')
    )),
    1,
    'user detail includes entitlement history'
);

select extensions.ok(
    to_regprocedure('public.admin_manage_user_audited(uuid,uuid,uuid,text,text,uuid,jsonb,text,text,text,timestamptz,uuid)') is not null,
    'audited user management RPC exists'
);

select extensions.ok(
    has_function_privilege(
        'service_role',
        'public.admin_manage_user_audited(uuid,uuid,uuid,text,text,uuid,jsonb,text,text,text,timestamptz,uuid)',
        'execute'
    )
    and not has_function_privilege(
        'authenticated',
        'public.admin_manage_user_audited(uuid,uuid,uuid,text,text,uuid,jsonb,text,text,text,timestamptz,uuid)',
        'execute'
    ),
    'user management is service-role-only'
);

select extensions.ok(
    pg_get_constraintdef(
        (select oid from pg_constraint where conname = 'admin_operation_idempotency_operation_check')
    ) like '%user_manage%',
    'idempotency namespace includes user management'
);

do $$
declare
    owner_user_id uuid := gen_random_uuid();
    readonly_user_id uuid := gen_random_uuid();
    managed_user_id uuid := gen_random_uuid();
    owner_admin_id uuid := gen_random_uuid();
    readonly_admin_id uuid := gen_random_uuid();
    grant_id uuid;
    result jsonb;
begin
    insert into auth.users (id, email) values
        (owner_user_id, 'directory-owner@example.invalid'),
        (readonly_user_id, 'directory-readonly@example.invalid'),
        (managed_user_id, 'directory-target@example.invalid');
    insert into public.admin_users (id, user_id, email, role, status) values
        (owner_admin_id, owner_user_id, 'directory-owner@example.invalid', 'owner', 'active'),
        (readonly_admin_id, readonly_user_id, 'directory-readonly@example.invalid', 'readonly', 'active');

    perform public.admin_manage_user_audited(
        managed_user_id, owner_admin_id, owner_user_id, 'update_note', 'fixture note',
        gen_random_uuid(), '{"aal":"aal2"}'::jsonb, null, 'owner note', null, null, null
    );
    perform public.admin_manage_user_audited(
        managed_user_id, owner_admin_id, owner_user_id, 'set_status', 'fixture status',
        gen_random_uuid(), '{"aal":"aal2"}'::jsonb, 'suspended', null, null, null, null
    );
    result := public.admin_manage_user_audited(
        managed_user_id, owner_admin_id, owner_user_id, 'grant_entitlement', 'fixture grant',
        gen_random_uuid(), '{"aal":"aal2"}'::jsonb, null, null, 'standard', now() + interval '30 days', null
    );
    grant_id := (result ->> 'grantId')::uuid;
    perform public.admin_manage_user_audited(
        managed_user_id, owner_admin_id, owner_user_id, 'revoke_entitlement', 'fixture revoke',
        gen_random_uuid(), '{"aal":"aal2"}'::jsonb, null, null, null, null, grant_id
    );

    if (select account_status from public.user_profiles where user_id = managed_user_id) <> 'suspended'
       or (select admin_note from public.user_profiles where user_id = managed_user_id) <> 'owner note'
       or (select status from public.user_entitlement_grants where id = grant_id) <> 'revoked'
       or (select count(*) from public.admin_audit_logs audit where audit.target_user_id = managed_user_id) < 4 then
        raise exception 'owner user management operations did not persist and audit';
    end if;

    begin
        perform public.admin_manage_user_audited(
            managed_user_id, readonly_admin_id, readonly_user_id, 'set_status', 'readonly denied',
            gen_random_uuid(), '{"aal":"aal2"}'::jsonb, 'active', null, null, null, null
        );
        raise exception 'readonly user management unexpectedly succeeded';
    exception when others then
        if sqlerrm not like '%admin_capability_denied%' then raise; end if;
    end;

    begin
        perform public.admin_manage_user_audited(
            owner_user_id, owner_admin_id, owner_user_id, 'set_status', 'self lockout denied',
            gen_random_uuid(), '{"aal":"aal2"}'::jsonb, 'banned', null, null, null, null
        );
        raise exception 'owner self lockout unexpectedly succeeded';
    exception when others then
        if sqlerrm not like '%admin_self_lockout_denied%' then raise; end if;
    end;
end
$$;

select extensions.pass('owner can update note and status, grant, revoke, and audit user operations');
select extensions.pass('readonly management and owner self-lockout fail closed');

select * from extensions.finish();
rollback;
