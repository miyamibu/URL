\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

do $$
declare
    batch_definition text := lower(pg_get_functiondef(
        'public.claim_contact_support_outbox_batch(integer,integer)'::regprocedure
    ));
    complete_definition text := lower(pg_get_functiondef(
        'public.complete_contact_support_outbox(uuid,text,text,uuid)'::regprocedure
    ));
    fail_definition text := lower(pg_get_functiondef(
        'public.fail_contact_support_outbox(uuid,text,text,uuid)'::regprocedure
    ));
begin
    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'contact_support_outbox'
          and column_name = 'lease_token'
    ) then
        raise exception 'outbox lease_token column is missing';
    end if;

    if position('for update of o skip locked' in batch_definition) = 0
       or position('o.available_at <= now()' in batch_definition) = 0
       or position('o.locked_at <= now()' in batch_definition) = 0
       or position('gen_random_uuid()' in batch_definition) = 0 then
        raise exception 'batch claim is missing the due/lease/token safeguards';
    end if;

    if position('contact_support_message_id_required' in complete_definition) = 0
       or position('o.lease_token = p_lease_token' in complete_definition) = 0 then
        raise exception 'lease-aware complete does not fail closed';
    end if;

    if position('make_interval' in fail_definition) = 0
       or position('o.lease_token = p_lease_token' in fail_definition) = 0 then
        raise exception 'lease-aware fail does not apply backoff/token binding';
    end if;

    if position('payload = ''{}''::jsonb' in complete_definition) = 0 then
        raise exception 'successful outbox completion does not scrub encrypted payload';
    end if;

    if not has_function_privilege(
        'service_role',
        'public.claim_contact_support_outbox_batch(integer,integer)',
        'EXECUTE'
    ) then
        raise exception 'service_role cannot claim the outbox batch';
    end if;
    if has_function_privilege(
        'anon',
        'public.claim_contact_support_outbox_batch(integer,integer)',
        'EXECUTE'
    ) then
        raise exception 'anon can claim the outbox batch';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.complete_contact_support_outbox(uuid,text,text,uuid)',
        'EXECUTE'
    ) then
        raise exception 'authenticated can complete a worker lease';
    end if;
end;
$$;

select extensions.pass('contact support outbox worker contract passed');
select * from extensions.finish();
