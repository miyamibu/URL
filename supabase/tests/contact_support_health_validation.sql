\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

do $$
begin
    if not has_function_privilege(
        'service_role',
        'public.contact_support_health()',
        'EXECUTE'
    ) then
        raise exception 'service_role cannot read contact support health';
    end if;
    if has_function_privilege(
        'anon',
        'public.contact_support_health()',
        'EXECUTE'
    ) then
        raise exception 'anon can read internal contact support health RPC';
    end if;
    if not exists (
        select 1
        from pg_proc
        where pronamespace = 'public'::regnamespace
          and proname = 'record_contact_support_worker_heartbeat'
    ) then
        raise exception 'worker heartbeat RPC is missing';
    end if;
    if not exists (
        select 1
        from pg_class
        where oid = 'public.contact_support_worker_heartbeats'::regclass
          and relrowsecurity
    ) then
        raise exception 'worker heartbeat table RLS is disabled';
    end if;
end;
$$;

select extensions.pass('contact support health and heartbeat contract passed');
select * from extensions.finish();
