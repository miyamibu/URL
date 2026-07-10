create extension if not exists pgcrypto;

create table if not exists public.contact_support_requests (
    id uuid primary key default gen_random_uuid(),
    request_id text not null unique,
    email_hash text not null,
    auth_user_id_hash text null,
    ip_hash text not null,
    platform text not null check (platform in ('android', 'ios')),
    app_version text not null,
    build_type text not null check (build_type in ('debug', 'release')),
    is_signed_in boolean not null default false,
    delivery_status text not null default 'pending' check (delivery_status in (
        'pending',
        'sent',
        'delivered',
        'delivery_delayed',
        'bounced',
        'complained',
        'failed',
        'suppressed'
    )),
    delivery_provider text null,
    delivery_message_id text null,
    delivery_event_type text null,
    delivery_event_at timestamptz null,
    delivery_error text null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

drop trigger if exists trg_contact_support_requests_updated_at on public.contact_support_requests;
create trigger trg_contact_support_requests_updated_at
before update on public.contact_support_requests
for each row
execute function private.set_updated_at();

create index if not exists idx_contact_support_requests_email_recent
    on public.contact_support_requests (email_hash, created_at desc);

create index if not exists idx_contact_support_requests_ip_recent
    on public.contact_support_requests (ip_hash, created_at desc);

create index if not exists idx_contact_support_requests_delivery_message
    on public.contact_support_requests (delivery_provider, delivery_message_id)
    where delivery_message_id is not null;

alter table public.contact_support_requests enable row level security;
revoke all on table public.contact_support_requests from anon, authenticated;
grant select, insert, update on table public.contact_support_requests to service_role;
