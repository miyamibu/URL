begin;

alter table public.contact_support_outbox
  add column if not exists lease_token uuid;

-- The token is returned only to the worker that acquired the lease. A late
-- completion from an expired worker therefore cannot finalize a newer lease.
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
      and o.available_at <= now()
    )
    or (
      o.state = 'processing'
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
        last_error = case
          when o.state = 'processing' then 'lease_expired'
          else null
        end
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

-- Keep the legacy single-request caller compatible, but clear any token when
-- it finishes a row so a later worker never observes an old lease token.
create or replace function public.claim_contact_support_outbox(p_request_id text)
returns table(
  outbox_id uuid,
  request_id text,
  payload jsonb
)
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  candidate_id uuid;
  candidate_request_id text;
begin
  select o.id, r.request_id
  into candidate_id, candidate_request_id
  from public.contact_support_outbox o
  join public.contact_support_requests r on r.id = o.request_row_id
  where r.request_id = p_request_id
    and (
      o.state = 'pending'
      or (o.state = 'failed' and o.available_at <= now())
      or (o.state = 'processing' and o.locked_at < now() - interval '10 minutes')
    )
  order by o.available_at asc, o.created_at asc
  for update of o skip locked
  limit 1;

  if candidate_id is null then
    return;
  end if;

  update public.contact_support_outbox
  set state = 'processing',
      attempts = attempts + 1,
      locked_at = now(),
      lease_token = gen_random_uuid(),
      last_error = null
  where id = candidate_id;

  return query
  select candidate_id, candidate_request_id, o.payload
  from public.contact_support_outbox o
  where o.id = candidate_id;
end;
$function$;

create or replace function private.finalize_contact_support_outbox(
  p_request_row_id uuid,
  p_message_id text
)
returns void
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  pending_event record;
begin
  -- Keep request -> inbox ordering consistent with the webhook path.
  update public.contact_support_requests
  set delivery_provider = 'resend',
      delivery_message_id = p_message_id,
      delivery_status = case
        when private.contact_support_delivery_rank(delivery_status) <= private.contact_support_delivery_rank('sent') then 'sent'
        else delivery_status
      end,
      delivery_event_type = case
        when private.contact_support_delivery_rank(delivery_status) <= private.contact_support_delivery_rank('sent') then 'email.sent'
        else delivery_event_type
      end,
      delivery_event_at = case
        when private.contact_support_delivery_rank(delivery_status) <= private.contact_support_delivery_rank('sent') then now()
        else delivery_event_at
      end,
      delivery_error = case
        when private.contact_support_delivery_rank(delivery_status) <= private.contact_support_delivery_rank('sent') then null
        else delivery_error
      end
  where id = p_request_row_id;

  for pending_event in
    select id, email_id, event_type, delivery_status, event_at, delivery_error
    from public.contact_support_delivery_event_inbox
    where provider = 'resend'
      and email_id = p_message_id
      and processed_at is null
    order by event_at asc, id asc
    for update
  loop
    perform private.apply_contact_support_delivery_event(
      p_request_row_id,
      pending_event.email_id,
      pending_event.event_type,
      pending_event.delivery_status,
      pending_event.event_at,
      pending_event.delivery_error
    );
    update public.contact_support_delivery_event_inbox
    set contact_support_request_id = p_request_row_id,
        processed_at = now()
    where id = pending_event.id;
  end loop;
end;
$function$;

-- The existing signature remains available to contact-support/index.ts.
-- Missing Resend IDs are rejected before state can become sent.
create or replace function public.complete_contact_support_outbox(
  p_outbox_id uuid,
  p_request_id text,
  p_message_id text
)
returns void
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  request_row_id_value uuid;
  normalized_message_id text := nullif(btrim(coalesce(p_message_id, '')), '');
begin
  if normalized_message_id is null then
    raise exception using message = 'contact_support_message_id_required';
  end if;

  update public.contact_support_outbox o
  set state = 'sent',
      sent_at = coalesce(sent_at, now()),
      locked_at = null,
      lease_token = null,
      delivery_message_id = normalized_message_id,
      payload = '{}'::jsonb,
      payload_scrubbed_at = coalesce(payload_scrubbed_at, now()),
      last_error = null
  from public.contact_support_requests r
  where o.id = p_outbox_id
    and o.request_row_id = r.id
    and r.request_id = p_request_id
    and o.state = 'processing'
  returning o.request_row_id into request_row_id_value;

  if request_row_id_value is null then
    return;
  end if;

  perform private.finalize_contact_support_outbox(
    request_row_id_value,
    normalized_message_id
  );
end;
$function$;

create or replace function public.complete_contact_support_outbox(
  p_outbox_id uuid,
  p_request_id text,
  p_message_id text,
  p_lease_token uuid
)
returns void
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  request_row_id_value uuid;
  normalized_message_id text := nullif(btrim(coalesce(p_message_id, '')), '');
begin
  if normalized_message_id is null then
    raise exception using message = 'contact_support_message_id_required';
  end if;
  if p_lease_token is null then
    raise exception using message = 'contact_support_lease_token_required';
  end if;

  update public.contact_support_outbox o
  set state = 'sent',
      sent_at = coalesce(sent_at, now()),
      locked_at = null,
      lease_token = null,
      delivery_message_id = normalized_message_id,
      payload = '{}'::jsonb,
      payload_scrubbed_at = coalesce(payload_scrubbed_at, now()),
      last_error = null
  from public.contact_support_requests r
  where o.id = p_outbox_id
    and o.request_row_id = r.id
    and r.request_id = p_request_id
    and o.state = 'processing'
    and o.lease_token = p_lease_token
  returning o.request_row_id into request_row_id_value;

  -- A late worker or a retry after success is intentionally a no-op.
  if request_row_id_value is null then
    return;
  end if;

  perform private.finalize_contact_support_outbox(
    request_row_id_value,
    normalized_message_id
  );
end;
$function$;

-- Preserve the legacy retry interval for the old caller while clearing the
-- token. The worker-specific overload below uses exponential backoff.
create or replace function public.fail_contact_support_outbox(
  p_outbox_id uuid,
  p_request_id text,
  p_error_code text
)
returns void
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
begin
  update public.contact_support_outbox o
  set state = 'failed',
      locked_at = null,
      lease_token = null,
      available_at = now() + interval '5 minutes',
      last_error = left(coalesce(p_error_code, 'outbox_failed'), 128)
  from public.contact_support_requests r
  where o.id = p_outbox_id
    and o.request_row_id = r.id
    and r.request_id = p_request_id
    and o.state = 'processing';
end;
$function$;

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
begin
  if p_lease_token is null then
    raise exception using message = 'contact_support_lease_token_required';
  end if;

  update public.contact_support_outbox o
  set state = 'failed',
      locked_at = null,
      lease_token = null,
      available_at = now() + make_interval(secs => (
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
      )::double precision),
      last_error = left(coalesce(p_error_code, 'outbox_failed'), 128)
  from public.contact_support_requests r
  where o.id = p_outbox_id
    and o.request_row_id = r.id
    and r.request_id = p_request_id
    and o.state = 'processing'
    and o.lease_token = p_lease_token;
end;
$function$;

revoke all on function public.claim_contact_support_outbox_batch(integer, integer) from public, anon, authenticated;
revoke all on function public.complete_contact_support_outbox(uuid, text, text, uuid) from public, anon, authenticated;
revoke all on function public.fail_contact_support_outbox(uuid, text, text, uuid) from public, anon, authenticated;
grant execute on function public.claim_contact_support_outbox_batch(integer, integer) to service_role;
grant execute on function public.complete_contact_support_outbox(uuid, text, text, uuid) to service_role;
grant execute on function public.fail_contact_support_outbox(uuid, text, text, uuid) to service_role;

commit;
