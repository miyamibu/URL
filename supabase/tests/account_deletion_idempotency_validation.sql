\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

-- ---------------------------------------------------------------------------
-- Structural and privilege contract
-- ---------------------------------------------------------------------------
do $$
declare
    request_table regclass := to_regclass('public.account_deletion_requests');
    v_func record;
    v_privilege text;
    v_count integer;
begin
    if request_table is null then
        raise exception 'account_deletion_requests table is missing';
    end if;

    if (
        select count(*)
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'account_deletion_requests'
          and column_name in (
              'request_id', 'user_id', 'token_hash', 'status',
              'created_at', 'updated_at', 'completed_at'
          )
    ) <> 7 then
        raise exception 'account_deletion_requests columns are incomplete';
    end if;

    if exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'account_deletion_requests'
          and column_name in ('token', 'raw_token')
    ) then
        raise exception 'raw deletion token must never be stored';
    end if;

    if not exists (
        select 1 from pg_constraint
        where conrelid = request_table
          and contype = 'p'
          and pg_get_constraintdef(oid) = 'PRIMARY KEY (request_id)'
    ) then
        raise exception 'account_deletion_requests primary key is missing';
    end if;

    begin
        insert into public.account_deletion_requests (user_id, token_hash, status)
        values ('00000000-0000-0000-0000-00000000dead'::uuid, 'x', 'bogus');
        raise exception 'invalid deletion request status was accepted';
    exception
        when check_violation then null;
    end;

    if not exists (
        select 1 from pg_class where oid = request_table and relrowsecurity
    ) then
        raise exception 'row level security is not enabled on account_deletion_requests';
    end if;

    if exists (
        select 1 from pg_policies
        where schemaname = 'public'
          and tablename = 'account_deletion_requests'
    ) then
        raise exception 'unexpected RLS policy exposes account deletion requests';
    end if;

    foreach v_privilege in array array['SELECT', 'INSERT', 'UPDATE', 'DELETE'] loop
        if has_table_privilege('anon', request_table, v_privilege)
           or has_table_privilege('authenticated', request_table, v_privilege) then
            raise exception 'non-service roles hold direct privileges on account_deletion_requests (%)', v_privilege;
        end if;
    end loop;

    if has_function_privilege('anon', 'public.create_account_deletion_request()'::regprocedure, 'EXECUTE') then
        raise exception 'anon must not execute create_account_deletion_request';
    end if;
    if not has_function_privilege('authenticated', 'public.create_account_deletion_request()'::regprocedure, 'EXECUTE') then
        raise exception 'authenticated cannot execute create_account_deletion_request';
    end if;

    if not has_function_privilege('anon', 'public.get_account_deletion_status(uuid,text)'::regprocedure, 'EXECUTE') then
        raise exception 'anon cannot execute get_account_deletion_status';
    end if;

    if has_function_privilege('anon', 'public.delete_my_account(uuid)'::regprocedure, 'EXECUTE') then
        raise exception 'anon must not execute delete_my_account';
    end if;
    if not has_function_privilege('authenticated', 'public.delete_my_account(uuid)'::regprocedure, 'EXECUTE') then
        raise exception 'authenticated cannot execute delete_my_account';
    end if;
    if exists (
        select 1
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and p.proname = 'delete_my_account'
          and pg_get_function_identity_arguments(p.oid) <> 'p_request_id uuid'
    ) then
        raise exception 'ambiguous delete_my_account signatures must not coexist';
    end if;
    select count(*) into v_count
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname = 'delete_my_account';
    if v_count <> 1 then
        raise exception 'exactly one delete_my_account must exist';
    end if;

    for v_func in
        select p.proname, p.prosecdef,
               coalesce(array_to_string(p.proconfig, ' '), '') as config
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public'
          and (
              (p.proname = 'create_account_deletion_request')
              or (p.proname = 'get_account_deletion_status')
              or (p.proname = 'delete_my_account' and pg_get_function_identity_arguments(p.oid) = 'p_request_id uuid')
          )
    loop
        if not v_func.prosecdef then
            raise exception '% must be security definer', v_func.proname;
        end if;
        if v_func.config not like '%search_path%' then
            raise exception '% must pin search_path', v_func.proname;
        end if;
    end loop;
end;
$$;

-- ---------------------------------------------------------------------------
-- Functional protocol contract
-- ---------------------------------------------------------------------------
begin;

insert into auth.users (id) values
    ('11111111-1111-1111-1111-111111111111'),
    ('22222222-2222-2222-2222-222222222222'),
    ('33333333-3333-3333-3333-333333333333'),
    ('44444444-4444-4444-4444-444444444444'),
    ('55555555-5555-5555-5555-555555555555');

insert into public.shared_tag_groups (id, name, created_by) values
    ('aaaaaaaa-0000-0000-0000-000000000001', 'deletion-fixture-group', '44444444-4444-4444-4444-444444444444');

insert into public.shared_tag_group_members (group_id, user_id, role, status) values
    ('aaaaaaaa-0000-0000-0000-000000000001', '44444444-4444-4444-4444-444444444444', 'owner', 'active'),
    ('aaaaaaaa-0000-0000-0000-000000000001', '55555555-5555-5555-5555-555555555555', 'viewer', 'active');

set local role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);
create temp table req_a as select public.create_account_deletion_request() as g;
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', true);
create temp table req_b as select public.create_account_deletion_request() as g;
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '33333333-3333-3333-3333-333333333333', true);
create temp table req_c as select public.create_account_deletion_request() as g;
reset role;

set local role authenticated;
select set_config('request.jwt.claim.sub', '44444444-4444-4444-4444-444444444444', true);
create temp table req_d as select public.create_account_deletion_request() as g;
reset role;

-- Token is server-generated 256-bit hex; only its hash reaches storage.
do $$
declare
    v_tok text;
begin
    select g ->> 'token' into v_tok from req_a;
    if v_tok is null or v_tok !~ '^[0-9a-f]{64}$' then
        raise exception 'deletion token entropy/format contract violated';
    end if;
end;
$$;

-- Unauthenticated creation is refused.
select set_config('request.jwt.claim.sub', '', true);
do $$
begin
    perform public.create_account_deletion_request();
    raise exception 'anonymous deletion request creation unexpectedly succeeded';
exception
    when others then
        if sqlerrm <> 'auth_required' then
            raise;
        end if;
end;
$$;
select set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', true);

-- Status query converges for the original requester.
do $$
declare
    v_rid uuid;
    v_tok text;
    v_status jsonb;
begin
    select g ->> 'request_id', g ->> 'token' into v_rid, v_tok from req_a;
    v_status := public.get_account_deletion_status(v_rid, v_tok);
    if v_status ->> 'status' <> 'pending' then
        raise exception 'fresh deletion request must report pending';
    end if;
    if v_status ->> 'user_id' <> '11111111-1111-1111-1111-111111111111' then
        raise exception 'status query returned unexpected owner';
    end if;
end;
$$;

-- Wrong token, unknown request, and empty arguments all report not_found.
do $$
declare
    v_rid uuid;
    v_tok text;
begin
    select g ->> 'request_id', g ->> 'token' into v_rid, v_tok from req_a;
    if (public.get_account_deletion_status(v_rid, repeat('a', 64))) ->> 'status' <> 'not_found' then
        raise exception 'wrong token must report not_found';
    end if;
    if (public.get_account_deletion_status('ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, v_tok)) ->> 'status' <> 'not_found' then
        raise exception 'unknown request must report not_found';
    end if;
    if (public.get_account_deletion_status(null, v_tok)) ->> 'status' <> 'not_found' then
        raise exception 'null request must report not_found';
    end if;
    if (public.get_account_deletion_status(v_rid, '')) ->> 'status' <> 'not_found' then
        raise exception 'empty token must report not_found';
    end if;
end;
$$;

-- A caller may not drive another user's deletion request.
do $$
declare
    v_rid_b uuid;
begin
    select g ->> 'request_id' into v_rid_b from req_b;
    perform public.delete_my_account(v_rid_b);
    raise exception 'cross-user deletion request was accepted';
exception
    when others then
        if sqlerrm <> 'invalid_deletion_request' then
            raise;
        end if;
end;
$$;

-- Full deletion completes the request and replays to the identical result.
do $$
declare
    v_rid uuid;
    v_first jsonb;
    v_replay jsonb;
begin
    select g ->> 'request_id' into v_rid from req_a;
    v_first := public.delete_my_account(v_rid);
    v_replay := public.delete_my_account(v_rid);
    if v_first ->> 'status' <> 'deleted'
       or v_first ->> 'user_id' <> '11111111-1111-1111-1111-111111111111' then
        raise exception 'deletion result contract violated';
    end if;
    if v_first is distinct from v_replay then
        raise exception 'replayed deletion must return the identical stable result';
    end if;
end;
$$;

-- The legacy zero-argument path keeps working and converges the caller's
-- own pending requests so no orphaned pending rows remain.
set local role authenticated;
select set_config('request.jwt.claim.sub', '33333333-3333-3333-3333-333333333333', true);
select public.delete_my_account();
reset role;

-- Owner-transfer requirement fails the request instead of deleting.
set local role authenticated;
select set_config('request.jwt.claim.sub', '44444444-4444-4444-4444-444444444444', true);
do $$
declare
    v_rid_d uuid;
begin
    select g ->> 'request_id' into v_rid_d from req_d;
    perform public.delete_my_account(v_rid_d);
    raise exception 'owner_transfer_required was not enforced';
exception
    when others then
        if sqlerrm <> 'owner_transfer_required' then
            raise;
        end if;
end;
$$;
reset role;

-- After transferring ownership, the legacy zero-argument path must delete
-- AND converge that user's still-pending request (no orphaned ledger row).
do $$
declare
    v_row public.account_deletion_requests%rowtype;
begin
    select * into v_row from public.account_deletion_requests
    where request_id = (select g ->> 'request_id' from req_d)::uuid;
    if v_row.status <> 'pending' then
        raise exception 'owner_transfer_required must keep the request retryable (pending)';
    end if;
    if not exists (select 1 from auth.users where id = '44444444-4444-4444-4444-444444444444') then
        raise exception 'blocked deletion removed the account';
    end if;
end;
$$;

update public.shared_tag_group_members
set role = 'owner', updated_at = now()
where group_id = 'aaaaaaaa-0000-0000-0000-000000000001'
  and user_id = '55555555-5555-5555-5555-555555555555';

update public.shared_tag_group_members
set role = 'viewer', updated_at = now()
where group_id = 'aaaaaaaa-0000-0000-0000-000000000001'
  and user_id = '44444444-4444-4444-4444-444444444444';

set local role authenticated;
select set_config('request.jwt.claim.sub', '44444444-4444-4444-4444-444444444444', true);
select public.delete_my_account();
reset role;

-- Final state assertions (elevated, transaction-local).
do $$
declare
    v_row public.account_deletion_requests%rowtype;
    v_rid uuid;
    v_tok text;
begin
    select g ->> 'request_id' into v_rid from req_b;
    if not exists (
        select 1 from public.account_deletion_requests
        where request_id = v_rid and status = 'pending'
    ) then
        raise exception 'cross-user attempt must leave target request untouched';
    end if;
    if not exists (select 1 from auth.users where id = '22222222-2222-2222-2222-222222222222') then
        raise exception 'cross-user attempt deleted the wrong account';
    end if;

    select g ->> 'request_id', g ->> 'token' into v_rid, v_tok from req_a;
    select * into v_row from public.account_deletion_requests where request_id = v_rid;
    if v_row.status <> 'completed' or v_row.completed_at is null then
        raise exception 'completed deletion request state contract violated';
    end if;
    if (select count(*) from public.account_deletion_requests where request_id = v_rid) <> 1 then
        raise exception 'replay duplicated deletion request rows';
    end if;
    if exists (select 1 from auth.users where id = '11111111-1111-1111-1111-111111111111') then
        raise exception 'auth user survived committed deletion';
    end if;
    if (public.get_account_deletion_status(v_rid, v_tok)) ->> 'status' <> 'completed' then
        raise exception 'status query must converge to completed after deletion';
    end if;

    if exists (select 1 from auth.users where id = '33333333-3333-3333-3333-333333333333') then
        raise exception 'legacy zero-arg deletion failed';
    end if;
    select g ->> 'request_id' into v_rid from req_c;
    select * into v_row from public.account_deletion_requests where request_id = v_rid;
    if v_row.status <> 'completed' or v_row.completed_at is null then
        raise exception 'legacy zero-arg deletion must converge the caller''s pending requests';
    end if;

    if exists (select 1 from auth.users where id = '44444444-4444-4444-4444-444444444444') then
        raise exception 'post-transfer deletion failed';
    end if;
    if exists (
        select 1 from public.shared_tag_group_members
        where group_id = 'aaaaaaaa-0000-0000-0000-000000000001'
          and user_id = '44444444-4444-4444-4444-444444444444'
    ) then
        raise exception 'post-transfer deletion must remove the caller membership';
    end if;
    select * into v_row from public.account_deletion_requests
    where request_id = (select g ->> 'request_id' from req_d)::uuid;
    if v_row.status <> 'completed' then
        raise exception 'zero-arg path after transfer must converge the pending request';
    end if;
end;
$$;

rollback;

select extensions.pass('account deletion idempotency contract validation passed');
select * from extensions.finish();
