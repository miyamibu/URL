-- Admin operations workflow fields.
-- These additions are backward-compatible with existing support submissions.

alter table public.contact_support_requests
    add column if not exists support_status text not null default 'open'
        check (support_status in ('open', 'in_progress', 'resolved', 'closed'));

alter table public.contact_support_requests
    add column if not exists assigned_admin_id uuid references public.admin_users(id) on delete set null;

alter table public.contact_support_requests
    add column if not exists admin_note text;

create index if not exists idx_contact_support_requests_support_queue
    on public.contact_support_requests (support_status, created_at desc);

create index if not exists idx_contact_support_requests_assigned_admin
    on public.contact_support_requests (assigned_admin_id, support_status, created_at desc);

revoke all on table public.contact_support_requests from public, anon, authenticated;
grant select, insert, update on table public.contact_support_requests to service_role;
