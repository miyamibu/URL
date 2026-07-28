\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

do $$
declare
    operation_table regclass := to_regclass('public.admin_operation_idempotency');
begin
    if operation_table is null then
        raise exception 'admin operation idempotency table is missing';
    end if;

    if (
        select count(*)
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'admin_operation_idempotency'
          and column_name in (
              'admin_user_id', 'operation_id', 'operation', 'status',
              'code_id', 'created_at', 'updated_at'
          )
    ) <> 7 then
        raise exception 'admin operation idempotency columns are incomplete';
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conrelid = operation_table
          and conname = 'admin_operation_idempotency_pkey'
          and contype = 'p'
          and pg_get_constraintdef(oid) = 'PRIMARY KEY (admin_user_id, operation_id, operation)'
    ) then
        raise exception 'admin operation idempotency primary key is missing';
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conrelid = operation_table
          and conname = 'admin_operation_idempotency_operation_check'
          and pg_get_constraintdef(oid) like '%promo_code_issue%'
    ) then
        raise exception 'admin operation idempotency operation check is missing';
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conrelid = operation_table
          and conname = 'admin_operation_idempotency_status_check'
          and pg_get_constraintdef(oid) like '%started%'
          and pg_get_constraintdef(oid) like '%completed%'
          and pg_get_constraintdef(oid) like '%failed%'
    ) then
        raise exception 'admin operation idempotency status check is missing';
    end if;

    if not exists (
        select 1
        from pg_class
        where oid = operation_table
          and relrowsecurity
    ) then
        raise exception 'admin operation idempotency RLS is disabled';
    end if;

    if not exists (
        select 1
        from pg_trigger trigger_record
        join pg_class table_record on table_record.oid = trigger_record.tgrelid
        join pg_proc function_record on function_record.oid = trigger_record.tgfoid
        join pg_namespace schema_record on schema_record.oid = function_record.pronamespace
        where table_record.oid = operation_table
          and trigger_record.tgname = 'trg_admin_operation_idempotency_updated_at'
          and not trigger_record.tgisinternal
          and function_record.proname = 'set_updated_at'
          and schema_record.nspname = 'private'
    ) then
        raise exception 'admin operation idempotency updated_at trigger is missing';
    end if;

    if not has_table_privilege('service_role', operation_table, 'SELECT')
       or not has_table_privilege('service_role', operation_table, 'INSERT')
       or not has_table_privilege('service_role', operation_table, 'UPDATE') then
        raise exception 'service_role cannot operate on admin operation idempotency table';
    end if;

    if has_table_privilege('anon', operation_table, 'SELECT')
       or has_table_privilege('anon', operation_table, 'INSERT')
       or has_table_privilege('anon', operation_table, 'UPDATE')
       or has_table_privilege('authenticated', operation_table, 'SELECT')
       or has_table_privilege('authenticated', operation_table, 'INSERT')
       or has_table_privilege('authenticated', operation_table, 'UPDATE') then
        raise exception 'non-service roles can operate on admin operation idempotency table';
    end if;

    if exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'admin_operation_idempotency'
          and column_name in ('raw_code', 'promo_code', 'code')
    ) then
        raise exception 'raw promo code must not be stored in idempotency table';
    end if;
end;
$$;

select extensions.pass('admin operation idempotency schema and privilege validation passed');
select * from extensions.finish();
