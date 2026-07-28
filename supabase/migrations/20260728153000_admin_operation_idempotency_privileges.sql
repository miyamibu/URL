begin;

-- The fixed Admin Web uses only these table operations. Remove the broad
-- table grant before production exposure so service-role misuse cannot delete,
-- truncate, reference, or attach triggers to operation records.
revoke all privileges on table public.admin_operation_idempotency from service_role;
grant select, insert, update on table public.admin_operation_idempotency to service_role;

-- Client-generated operation IDs are reused after ambiguous network failures.
-- A repeated high-risk operation must roll back at the audit boundary instead
-- of applying its mutation twice.
do $$
begin
    if exists (
        select 1
        from public.admin_audit_logs
        where operation_id is not null
        group by admin_user_id, operation_id, action
        having count(*) > 1
    ) then
        raise exception 'duplicate_admin_operation_audit_rows';
    end if;
end
$$;

create unique index if not exists idx_admin_audit_logs_operation_action_unique
    on public.admin_audit_logs (admin_user_id, operation_id, action)
    where operation_id is not null;

commit;
