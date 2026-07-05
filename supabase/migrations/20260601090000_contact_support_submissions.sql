create schema if not exists private;
create extension if not exists pgcrypto;
create table if not exists private.contact_support_submissions (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  delivered_at timestamptz,
  user_email text not null,
  user_name text not null,
  message text not null,
  source text not null,
  ip_hash text not null,
  status text not null check (status in ('queued', 'sent', 'failed')),
  delivery_error text
);
create index if not exists contact_support_submissions_created_at_idx
  on private.contact_support_submissions (created_at desc);
create index if not exists contact_support_submissions_rate_limit_idx
  on private.contact_support_submissions (ip_hash, user_email, created_at desc);
revoke all on table private.contact_support_submissions from anon, authenticated;
