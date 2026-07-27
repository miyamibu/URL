\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
    select extensions.plan(1);

do $$
declare
    request_table regclass := 'public.contact_support_requests'::regclass;
    outbox_table regclass := 'public.contact_support_outbox'::regclass;
    inbox_table regclass := 'public.contact_support_delivery_event_inbox'::regclass;
    request_unique boolean;
begin
    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'contact_support_requests'
          and column_name in ('source', 'idempotency_key')
        group by table_schema, table_name
        having count(*) = 2
    ) then
        raise exception 'contact support source/idempotency columns are missing';
    end if;

    select exists (
        select 1
        from pg_constraint
        where conrelid = request_table
          and contype = 'u'
          and pg_get_constraintdef(oid) = 'UNIQUE (source, idempotency_key)'
    ) into request_unique;
    if not request_unique then
        raise exception 'contact support source/idempotency unique constraint is missing';
    end if;

    if not exists (select 1 from pg_class where oid = outbox_table and relrowsecurity) then
        raise exception 'contact support outbox RLS is disabled';
    end if;
    if not exists (select 1 from pg_class where oid = inbox_table and relrowsecurity) then
        raise exception 'contact support event inbox RLS is disabled';
    end if;
    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'contact_support_outbox'
          and column_name = 'payload_hash'
    ) then
        raise exception 'contact support encrypted payload hash is missing';
    end if;
    if not exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'contact_support_outbox'
          and column_name = 'payload_scrubbed_at'
    ) then
        raise exception 'contact support payload scrub timestamp is missing';
    end if;
    if not exists (
        select 1
        from pg_indexes
        where schemaname = 'public'
          and tablename = 'contact_support_delivery_event_inbox'
          and indexdef ilike '%UNIQUE%'
          and indexdef ilike '%(provider, provider_event_id)%'
    ) then
        raise exception 'contact support provider event unique index is missing';
    end if;

    if not has_function_privilege(
        'service_role',
        'public.enqueue_contact_support_request(text,text,text,text,text,text,text,text,text,jsonb,text)',
        'EXECUTE'
    ) then
        raise exception 'service_role cannot enqueue contact support requests';
    end if;
    if has_function_privilege(
        'anon',
        'public.enqueue_contact_support_request(text,text,text,text,text,text,text,text,text,jsonb,text)',
        'EXECUTE'
    ) then
        raise exception 'anon can execute contact support enqueue RPC';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.enqueue_contact_support_request(text,text,text,text,text,text,text,text,text,jsonb,text)',
        'EXECUTE'
    ) then
        raise exception 'authenticated can execute contact support enqueue RPC';
    end if;

    if private.contact_support_delivery_rank('delivered') <= private.contact_support_delivery_rank('sent') then
        raise exception 'delivery rank does not advance from sent to delivered';
    end if;
    if private.contact_support_delivery_rank('failed') <= private.contact_support_delivery_rank('delivered') then
        raise exception 'terminal delivery rank does not outrank delivered';
    end if;

    if not has_function_privilege(
        'service_role',
        'public.scrub_contact_support_outbox_payloads()',
        'EXECUTE'
    ) then
        raise exception 'service_role cannot scrub contact support payloads';
    end if;
end;
$$;

select extensions.pass('contact support schema and privilege validation passed');
select * from extensions.finish();
