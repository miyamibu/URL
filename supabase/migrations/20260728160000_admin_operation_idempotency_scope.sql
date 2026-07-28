begin;

-- The published idempotency table is retained. This forward migration extends
-- the operation namespace so support, moderation, and revoke routes can claim
-- a request before executing a side effect, without changing the frozen
-- 20260727150000 migration.
alter table public.admin_operation_idempotency
    drop constraint if exists admin_operation_idempotency_operation_check;

alter table public.admin_operation_idempotency
    add constraint admin_operation_idempotency_operation_check
    check (operation in (
        'promo_code_issue',
        'support_update',
        'moderation_action',
        'promo_code_revoke'
    ));

-- The original migration granted more than the application needs. Keep the
-- table service-only, but limit the service role to the operations used by the
-- server routes and reconciliation code.
revoke all on table public.admin_operation_idempotency from service_role;
grant select, insert, update on table public.admin_operation_idempotency to service_role;

commit;
