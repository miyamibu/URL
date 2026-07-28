begin;

-- A service-role-only claim prevents one logical promo-code issue operation
-- from creating multiple codes or sending multiple emails.
create table if not exists public.admin_operation_idempotency (
    admin_user_id uuid not null references public.admin_users(id) on delete cascade,
    operation_id uuid not null,
    operation text not null,
    status text not null default 'started',
    code_id uuid null references public.promo_invite_codes(id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint admin_operation_idempotency_pkey
        primary key (admin_user_id, operation_id, operation),
    constraint admin_operation_idempotency_operation_check
        check (operation in ('promo_code_issue')),
    constraint admin_operation_idempotency_status_check
        check (status in ('started', 'completed', 'failed'))
);

create index if not exists idx_admin_operation_idempotency_status
    on public.admin_operation_idempotency (admin_user_id, status, updated_at desc);

drop trigger if exists trg_admin_operation_idempotency_updated_at
    on public.admin_operation_idempotency;
create trigger trg_admin_operation_idempotency_updated_at
before update on public.admin_operation_idempotency
for each row
execute function private.set_updated_at();

alter table public.admin_operation_idempotency enable row level security;
revoke all on table public.admin_operation_idempotency from public, anon, authenticated;
grant all on table public.admin_operation_idempotency to service_role;

commit;
