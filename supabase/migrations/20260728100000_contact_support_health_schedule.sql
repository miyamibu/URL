begin;

create table if not exists public.contact_support_worker_heartbeats (
  worker_name text primary key check (worker_name = 'contact-support-outbox'),
  last_seen_at timestamptz not null,
  updated_at timestamptz not null default now()
);

alter table public.contact_support_worker_heartbeats enable row level security;
revoke all on table public.contact_support_worker_heartbeats from public, anon, authenticated;
grant select, insert, update on table public.contact_support_worker_heartbeats to service_role;

create or replace function public.record_contact_support_worker_heartbeat(
  p_worker_name text,
  p_run_at timestamptz default now()
)
returns void
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
begin
  if p_worker_name <> 'contact-support-outbox' then
    raise exception using message = 'contact_support_invalid_worker_name';
  end if;

  insert into public.contact_support_worker_heartbeats (worker_name, last_seen_at)
  values (
    p_worker_name,
    least(coalesce(p_run_at, now()), now() + interval '5 minutes')
  )
  on conflict (worker_name) do update
  set last_seen_at = excluded.last_seen_at,
      updated_at = now();
end;
$function$;

create or replace function public.contact_support_health()
returns table(healthy boolean)
language sql
security definer
set search_path = public, private, pg_temp
as $function$
  select (
    to_regclass('public.contact_support_outbox') is not null
    and exists (
      select 1
      from public.contact_support_worker_heartbeats
      where worker_name = 'contact-support-outbox'
        and last_seen_at >= now() - interval '5 minutes'
    )
  ) as healthy;
$function$;

revoke all on function public.record_contact_support_worker_heartbeat(text, timestamptz)
  from public, anon, authenticated;
revoke all on function public.contact_support_health()
  from public, anon, authenticated;
grant execute on function public.record_contact_support_worker_heartbeat(text, timestamptz)
  to service_role;
grant execute on function public.contact_support_health()
  to service_role;

-- Supabase Vault stores the service-role key and worker secret used by the
-- scheduled HTTP call. Values are intentionally not part of this migration.
create extension if not exists pg_cron;
create extension if not exists pg_net;

do $schedule$
declare
  existing_job_id bigint;
begin
  select jobid
    into existing_job_id
    from cron.job
   where jobname = 'contact-support-outbox-worker';
  if existing_job_id is not null then
    perform cron.unschedule(existing_job_id);
  end if;

  perform cron.schedule(
    'contact-support-outbox-worker',
    '* * * * *',
    $$
      select net.http_post(
        url := 'https://xocumgxbylmpoobfqows.supabase.co/functions/v1/contact-support-outbox',
        headers := jsonb_build_object(
          'Content-Type', 'application/json',
          'Authorization', 'Bearer ' || coalesce((select decrypted_secret from vault.decrypted_secrets where name = 'SUPABASE_SERVICE_ROLE_KEY' limit 1), ''),
          'x-contact-support-worker-secret', coalesce((select decrypted_secret from vault.decrypted_secrets where name = 'CONTACT_SUPPORT_WORKER_SECRET' limit 1), '')
        ),
        body := '{}'::jsonb
      );
    $$
  );

  select jobid
    into existing_job_id
    from cron.job
   where jobname = 'contact-support-outbox-scrub';
  if existing_job_id is not null then
    perform cron.unschedule(existing_job_id);
  end if;

  perform cron.schedule(
    'contact-support-outbox-scrub',
    '17 3 * * *',
    $$select public.scrub_contact_support_outbox_payloads();$$
  );
end;
$schedule$;

commit;
