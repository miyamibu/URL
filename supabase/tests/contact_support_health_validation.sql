\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

do $$
declare
    worker_command text;
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

    if (
        select count(*)
        from cron.job
        where jobname = 'contact-support-outbox-worker'
    ) <> 1 then
        raise exception 'contact support worker cron job is missing or duplicated';
    end if;

    select command::text
    into worker_command
    from cron.job
    where jobname = 'contact-support-outbox-worker';

    if position('CONTACT_SUPPORT_WORKER_SECRET' in worker_command) = 0
       or position('x-contact-support-worker-secret' in worker_command) = 0 then
        raise exception 'contact support worker cron does not use its dedicated secret';
    end if;
    if position('SUPABASE_SERVICE_ROLE_KEY' in worker_command) > 0
       or position('authorization' in lower(worker_command)) > 0 then
        raise exception 'contact support worker cron still sends the service-role key';
    end if;
end;
$$;

select extensions.pass('contact support health and heartbeat contract passed');
select * from extensions.finish();
