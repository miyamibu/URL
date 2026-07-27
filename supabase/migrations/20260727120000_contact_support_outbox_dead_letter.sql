begin;

-- The request endpoint no longer dispatches email inline. Remove the legacy
-- single-request claim/finalize API so every delivery attempt uses the
-- lease-token-protected independent worker path below.
drop function if exists public.claim_contact_support_outbox(text);
drop function if exists public.complete_contact_support_outbox(uuid, text, text);
drop function if exists public.fail_contact_support_outbox(uuid, text, text);

-- A permanently invalid destination or provider configuration must not be
-- retried forever. The scheduler can alert on dead_letter without deleting
-- the original support request or its audit trail.
alter table public.contact_support_outbox
  add column if not exists dead_letter_at timestamptz;

alter table public.contact_support_outbox
  drop constraint if exists contact_support_outbox_state_check;

alter table public.contact_support_outbox
  add constraint contact_support_outbox_state_check
  check (state in ('pending', 'processing', 'sent', 'failed', 'dead_letter'));

create index if not exists contact_support_outbox_dead_letter_idx
  on public.contact_support_outbox (dead_letter_at)
  where state = 'dead_letter';

create or replace function public.fail_contact_support_outbox(
  p_outbox_id uuid,
  p_request_id text,
  p_error_code text,
  p_lease_token uuid
)
returns void
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  max_attempts constant integer := 8;
  next_state text;
begin
  if p_lease_token is null then
    raise exception using message = 'contact_support_lease_token_required';
  end if;

  update public.contact_support_outbox o
  set state = case when o.attempts >= max_attempts then 'dead_letter' else 'failed' end,
      locked_at = null,
      lease_token = null,
      dead_letter_at = case when o.attempts >= max_attempts then now() else null end,
      available_at = case
        when o.attempts >= max_attempts then now()
        else now() + make_interval(secs => (
          case
            when o.attempts <= 1 then 30
            when o.attempts = 2 then 60
            when o.attempts = 3 then 120
            when o.attempts = 4 then 240
            when o.attempts = 5 then 480
            when o.attempts = 6 then 960
            when o.attempts = 7 then 1800
            else 3600
          end
        )::double precision)
      end,
      last_error = left(coalesce(p_error_code, 'outbox_failed'), 128)
  from public.contact_support_requests r
  where o.id = p_outbox_id
    and o.request_row_id = r.id
    and r.request_id = p_request_id
    and o.state = 'processing'
    and o.lease_token = p_lease_token;

  select o.state
    into next_state
    from public.contact_support_outbox o
    join public.contact_support_requests r on r.id = o.request_row_id
   where o.id = p_outbox_id
     and r.request_id = p_request_id;

  if next_state = 'dead_letter' then
    update public.contact_support_requests
       set delivery_status = 'failed',
           delivery_error = left(coalesce(p_error_code, 'outbox_dead_letter'), 512),
           delivery_event_type = 'outbox.dead_letter',
           delivery_event_at = now()
     where id = (select request_row_id from public.contact_support_outbox where id = p_outbox_id);
  end if;
end;
$function$;

create or replace function public.claim_contact_support_outbox_batch(
  p_limit integer,
  p_lease_seconds integer
)
returns table(
  outbox_id uuid,
  request_id text,
  payload jsonb,
  lease_token uuid,
  attempts integer
)
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  requested_limit integer := coalesce(p_limit, 10);
  requested_lease_seconds integer := coalesce(p_lease_seconds, 600);
begin
  if requested_limit < 1 or requested_limit > 50 then
    raise exception using message = 'contact_support_invalid_worker_limit';
  end if;
  if requested_lease_seconds < 30 or requested_lease_seconds > 3600 then
    raise exception using message = 'contact_support_invalid_worker_lease';
  end if;

  return query
  with candidates as (
    select o.id
    from public.contact_support_outbox o
    where (
      o.state in ('pending', 'failed')
      and o.attempts < 8
      and o.available_at <= now()
    )
    or (
      o.state = 'processing'
      and o.attempts < 8
      and o.locked_at is not null
      and o.locked_at <= now() - make_interval(secs => requested_lease_seconds::double precision)
    )
    order by o.available_at asc, o.created_at asc, o.id asc
    for update of o skip locked
    limit requested_limit
  ), claimed as (
    update public.contact_support_outbox o
    set state = 'processing',
        attempts = o.attempts + 1,
        locked_at = now(),
        lease_token = gen_random_uuid(),
        dead_letter_at = null,
        last_error = case when o.state = 'processing' then 'lease_expired' else null end
    from candidates c
    where o.id = c.id
    returning o.id, o.request_row_id, o.payload, o.lease_token, o.attempts, o.created_at
  )
  select c.id, r.request_id, c.payload, c.lease_token, c.attempts
  from claimed c
  join public.contact_support_requests r on r.id = c.request_row_id
  order by c.created_at asc, c.id asc;
end;
$function$;

create or replace function public.scrub_contact_support_outbox_payloads()
returns integer
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  scrubbed_count integer;
begin
  update public.contact_support_outbox
  set payload = '{}'::jsonb,
      payload_scrubbed_at = now()
  where state = 'dead_letter'
    and dead_letter_at is not null
    and dead_letter_at <= now() - interval '7 days'
    and payload_scrubbed_at is null;
  get diagnostics scrubbed_count = row_count;
  return scrubbed_count;
end;
$function$;

revoke all on function public.claim_contact_support_outbox_batch(integer, integer)
  from public, anon, authenticated;
revoke all on function public.fail_contact_support_outbox(uuid, text, text, uuid)
  from public, anon, authenticated;
revoke all on function public.scrub_contact_support_outbox_payloads()
  from public, anon, authenticated;
grant execute on function public.claim_contact_support_outbox_batch(integer, integer)
  to service_role;
grant execute on function public.fail_contact_support_outbox(uuid, text, text, uuid)
  to service_role;
grant execute on function public.scrub_contact_support_outbox_payloads()
  to service_role;

commit;
