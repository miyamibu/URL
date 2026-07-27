begin;

alter table public.contact_support_requests
  add column if not exists source text,
  add column if not exists idempotency_key text;

update public.contact_support_requests
set source = coalesce(nullif(btrim(source), ''), 'legacy:' || platform),
    idempotency_key = coalesce(nullif(btrim(idempotency_key), ''), 'legacy:' || id::text)
where source is null
   or btrim(source) = ''
   or idempotency_key is null
   or btrim(idempotency_key) = '';

alter table public.contact_support_requests
  alter column source set not null,
  alter column idempotency_key set not null;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'public.contact_support_requests'::regclass
      and conname = 'contact_support_requests_source_idempotency_key_unique'
  ) then
    alter table public.contact_support_requests
      add constraint contact_support_requests_source_idempotency_key_unique
      unique (source, idempotency_key);
  end if;
end;
$$;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'public.contact_support_requests'::regclass
      and conname = 'contact_support_requests_source_size_check'
  ) then
    alter table public.contact_support_requests
      add constraint contact_support_requests_source_size_check
      check (octet_length(source) between 1 and 128);
  end if;
  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'public.contact_support_requests'::regclass
      and conname = 'contact_support_requests_idempotency_key_size_check'
  ) then
    alter table public.contact_support_requests
      add constraint contact_support_requests_idempotency_key_size_check
      check (octet_length(idempotency_key) between 16 and 128);
  end if;
end;
$$;

create table if not exists public.contact_support_outbox (
  id uuid primary key default gen_random_uuid(),
  request_row_id uuid not null unique references public.contact_support_requests(id) on delete cascade,
  source text not null,
  idempotency_key text not null,
  payload jsonb not null check (jsonb_typeof(payload) = 'object'),
  payload_hash text not null check (payload_hash ~ '^[0-9a-f]{64}$'),
  payload_scrubbed_at timestamptz,
  state text not null default 'pending' check (state in ('pending', 'processing', 'sent', 'failed')),
  attempts integer not null default 0 check (attempts >= 0),
  available_at timestamptz not null default now(),
  locked_at timestamptz,
  sent_at timestamptz,
  delivery_message_id text,
  last_error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.contact_support_outbox
  add column if not exists payload_hash text,
  add column if not exists payload_scrubbed_at timestamptz;

update public.contact_support_outbox
set payload_hash = encode(digest(payload::text, 'sha256'), 'hex')
where payload_hash is null;

alter table public.contact_support_outbox
  alter column payload_hash set not null;

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'public.contact_support_outbox'::regclass
      and conname = 'contact_support_outbox_payload_hash_check'
  ) then
    alter table public.contact_support_outbox
      add constraint contact_support_outbox_payload_hash_check
      check (payload_hash ~ '^[0-9a-f]{64}$');
  end if;
end;
$$;

create index if not exists contact_support_outbox_ready_idx
  on public.contact_support_outbox (state, available_at, created_at);

create unique index if not exists contact_support_outbox_source_idempotency_key_idx
  on public.contact_support_outbox (source, idempotency_key);

do $$
begin
  if not exists (
    select 1
    from pg_trigger
    where tgrelid = 'public.contact_support_outbox'::regclass
      and tgname = 'set_contact_support_outbox_updated_at'
  ) then
    create trigger set_contact_support_outbox_updated_at
      before update on public.contact_support_outbox
      for each row execute function private.set_updated_at();
  end if;
end;
$$;

create table if not exists public.contact_support_delivery_event_inbox (
  id uuid primary key default gen_random_uuid(),
  provider text not null check (provider = 'resend'),
  provider_event_id text not null check (octet_length(provider_event_id) between 1 and 256),
  email_id text not null,
  event_type text not null,
  delivery_status text not null check (delivery_status in ('sent', 'delivered', 'delivery_delayed', 'bounced', 'complained', 'failed', 'suppressed')),
  event_at timestamptz not null,
  delivery_error text,
  contact_support_request_id uuid references public.contact_support_requests(id) on delete set null,
  received_at timestamptz not null default now(),
  processed_at timestamptz
);

create unique index if not exists contact_support_delivery_event_inbox_provider_event_idx
  on public.contact_support_delivery_event_inbox (provider, provider_event_id);

create index if not exists contact_support_delivery_event_inbox_email_idx
  on public.contact_support_delivery_event_inbox (provider, email_id, processed_at);

create schema if not exists private;

create or replace function private.contact_support_delivery_rank(status text)
returns integer
language sql
immutable
as $$
  select case status
    when 'sent' then 100
    when 'delivery_delayed' then 200
    when 'delivered' then 300
    when 'failed' then 900
    when 'bounced' then 900
    when 'complained' then 900
    when 'suppressed' then 900
    else 0
  end;
$$;

create or replace function private.lock_contact_support_bucket(bucket_key text)
returns void
language sql
volatile
as $$
  select pg_advisory_xact_lock(hashtextextended(bucket_key, 0));
$$;

create or replace function public.enqueue_contact_support_request(
  p_request_id text,
  p_source text,
  p_idempotency_key text,
  p_email_hash text,
  p_auth_user_id_hash text,
  p_ip_hash text,
  p_platform text,
  p_app_version text,
  p_build_type text,
  p_payload jsonb,
  p_payload_hash text
)
returns table(
  request_row_id uuid,
  request_id text,
  delivery_status text,
  already_accepted boolean,
  outbox_id uuid
)
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  source_value text := btrim(coalesce(p_source, ''));
  key_value text := btrim(coalesce(p_idempotency_key, ''));
  auth_hash_value text := nullif(btrim(coalesce(p_auth_user_id_hash, '')), '');
  existing_request public.contact_support_requests%rowtype;
  existing_outbox_id uuid;
  email_hourly_count integer;
  ip_hourly_count integer;
  ip_daily_count integer;
  inserted_request public.contact_support_requests%rowtype;
  inserted_outbox_id uuid;
begin
  if p_request_id is null or p_request_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' then
    raise exception using message = 'contact_support_invalid_request_id';
  end if;
  if source_value !~ '^[A-Za-z0-9._:-]{1,128}$' then
    raise exception using message = 'contact_support_invalid_source';
  end if;
  if key_value !~ '^[A-Za-z0-9._~-]{16,128}$' then
    raise exception using message = 'contact_support_invalid_idempotency_key';
  end if;
  if p_payload is null or jsonb_typeof(p_payload) <> 'object' then
    raise exception using message = 'contact_support_invalid_payload';
  end if;
  if p_payload_hash is null or p_payload_hash !~ '^[0-9a-f]{64}$' then
    raise exception using message = 'contact_support_invalid_payload_hash';
  end if;

  perform private.lock_contact_support_bucket('contact-support:key:' || source_value || ':' || key_value);

  select *
  into existing_request
  from public.contact_support_requests
  where source = source_value
    and idempotency_key = key_value
  for update;

  if found then
    select id
    into existing_outbox_id
    from public.contact_support_outbox
    where public.contact_support_outbox.request_row_id = existing_request.id;

    if existing_outbox_id is null then
      raise exception using message = 'contact_support_outbox_missing';
    end if;
    if exists (
      select 1
      from public.contact_support_outbox
      where id = existing_outbox_id
        and payload_hash <> p_payload_hash
    ) then
      raise exception using message = 'contact_support_idempotency_key_reused';
    end if;

    return query
    select existing_request.id, existing_request.request_id, existing_request.delivery_status, true, existing_outbox_id;
    return;
  end if;

  perform private.lock_contact_support_bucket('contact-support:email:' || p_email_hash);
  perform private.lock_contact_support_bucket('contact-support:ip:' || p_ip_hash);

  select
    count(*) filter (where created_at > now() - interval '1 hour' and email_hash = p_email_hash)::int,
    count(*) filter (where created_at > now() - interval '1 hour' and ip_hash = p_ip_hash)::int,
    count(*) filter (where created_at > now() - interval '24 hours' and ip_hash = p_ip_hash)::int
  into email_hourly_count, ip_hourly_count, ip_daily_count
  from public.contact_support_requests;

  if email_hourly_count >= 10 then
    raise exception using message = 'contact_support_rate_limited_email';
  end if;
  if ip_hourly_count >= 50 or ip_daily_count >= 200 then
    raise exception using message = 'contact_support_rate_limited';
  end if;

  insert into public.contact_support_requests (
    request_id,
    source,
    idempotency_key,
    email_hash,
    auth_user_id_hash,
    ip_hash,
    platform,
    app_version,
    build_type,
    is_signed_in,
    delivery_status
  )
  values (
    p_request_id,
    source_value,
    key_value,
    p_email_hash,
    auth_hash_value,
    p_ip_hash,
    p_platform,
    p_app_version,
    p_build_type,
    auth_hash_value is not null,
    'pending'
  )
  returning * into inserted_request;

  insert into public.contact_support_outbox (
    request_row_id,
    source,
    idempotency_key,
    payload,
    payload_hash
  )
  values (
    inserted_request.id,
    source_value,
    key_value,
    p_payload,
    p_payload_hash
  )
  returning id into inserted_outbox_id;

  return query
  select inserted_request.id, inserted_request.request_id, inserted_request.delivery_status, false, inserted_outbox_id;
end;
$function$;

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
      last_error = null
  where id = candidate_id;

  return query
  select candidate_id, candidate_request_id, o.payload
  from public.contact_support_outbox o
  where o.id = candidate_id;
end;
$function$;

create or replace function private.apply_contact_support_delivery_event(
  p_request_row_id uuid,
  p_email_id text,
  p_event_type text,
  p_delivery_status text,
  p_event_at timestamptz,
  p_delivery_error text
)
returns boolean
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  current_status text;
  current_event_at timestamptz;
  incoming_rank integer;
  current_rank integer;
begin
  incoming_rank := private.contact_support_delivery_rank(p_delivery_status);
  if incoming_rank = 0 then
    return false;
  end if;

  select delivery_status, delivery_event_at
  into current_status, current_event_at
  from public.contact_support_requests
  where id = p_request_row_id
  for update;

  if not found then
    return false;
  end if;

  current_rank := private.contact_support_delivery_rank(current_status);
  if incoming_rank > current_rank
     or (incoming_rank = current_rank and (current_event_at is null or p_event_at >= current_event_at)) then
    update public.contact_support_requests
    set delivery_provider = 'resend',
        delivery_message_id = coalesce(delivery_message_id, p_email_id),
        delivery_status = p_delivery_status,
        delivery_event_type = p_event_type,
        delivery_event_at = p_event_at,
        delivery_error = left(p_delivery_error, 512)
    where id = p_request_row_id;
    return true;
  end if;

  return false;
end;
$function$;

create or replace function public.record_contact_support_delivery_event(
  p_provider text,
  p_provider_event_id text,
  p_email_id text,
  p_event_type text,
  p_delivery_status text,
  p_event_at timestamptz,
  p_delivery_error text
)
returns table(
  duplicate boolean,
  matched boolean,
  updated boolean
)
language plpgsql
security definer
set search_path = public, private, pg_temp
as $function$
declare
  inserted_event_id uuid;
  request_row_id_value uuid;
  did_update boolean := false;
  existing_request_id uuid;
begin
  if p_provider <> 'resend'
     or p_provider_event_id is null
     or btrim(p_provider_event_id) = ''
     or octet_length(btrim(p_provider_event_id)) > 256
     or p_email_id is null
     or btrim(p_email_id) = ''
     or p_event_type is null
     or btrim(p_event_type) = ''
     or private.contact_support_delivery_rank(p_delivery_status) = 0 then
    raise exception using message = 'contact_support_invalid_delivery_event';
  end if;

  -- Keep the lock order request -> inbox consistent with
  -- complete_contact_support_outbox. This prevents a webhook racing with
  -- the sender from acquiring the two row locks in the opposite order.
  select id
  into request_row_id_value
  from public.contact_support_requests
  where delivery_provider = p_provider
    and delivery_message_id = p_email_id
  for update;

  insert into public.contact_support_delivery_event_inbox (
    provider,
    provider_event_id,
    email_id,
    event_type,
    delivery_status,
    event_at,
    delivery_error
  )
  values (
    p_provider,
    btrim(p_provider_event_id),
    p_email_id,
    p_event_type,
    p_delivery_status,
    p_event_at,
    left(p_delivery_error, 512)
  )
  on conflict (provider, provider_event_id) do nothing
  returning id into inserted_event_id;

  if inserted_event_id is null then
    select contact_support_request_id
    into existing_request_id
    from public.contact_support_delivery_event_inbox
    where provider = p_provider
      and provider_event_id = btrim(p_provider_event_id);
    return query select true, existing_request_id is not null, false;
    return;
  end if;

  if request_row_id_value is null then
    return query select false, false, false;
    return;
  end if;

  did_update := private.apply_contact_support_delivery_event(
    request_row_id_value,
    p_email_id,
    p_event_type,
    p_delivery_status,
    p_event_at,
    p_delivery_error
  );
  update public.contact_support_delivery_event_inbox
  set contact_support_request_id = request_row_id_value,
      processed_at = now()
  where id = inserted_event_id;

  return query select false, true, did_update;
end;
$function$;

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
  pending_event record;
begin
  update public.contact_support_outbox o
  set state = 'sent',
      sent_at = now(),
      locked_at = null,
      delivery_message_id = p_message_id,
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
  where id = request_row_id_value;

  if p_message_id is null then
    return;
  end if;

  for pending_event in
    select id, provider, email_id, event_type, delivery_status, event_at, delivery_error
    from public.contact_support_delivery_event_inbox
    where provider = 'resend'
      and email_id = p_message_id
      and processed_at is null
    order by event_at asc, id asc
    for update
  loop
    perform private.apply_contact_support_delivery_event(
      request_row_id_value,
      pending_event.email_id,
      pending_event.event_type,
      pending_event.delivery_status,
      pending_event.event_at,
      pending_event.delivery_error
    );
    update public.contact_support_delivery_event_inbox
    set contact_support_request_id = request_row_id_value,
        processed_at = now()
    where id = pending_event.id;
  end loop;
end;
$function$;

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
      available_at = now() + interval '5 minutes',
      last_error = left(coalesce(p_error_code, 'outbox_failed'), 128)
  from public.contact_support_requests r
  where o.id = p_outbox_id
    and o.request_row_id = r.id
    and r.request_id = p_request_id
    and o.state = 'processing';
end;
$function$;

alter table public.contact_support_outbox enable row level security;
alter table public.contact_support_delivery_event_inbox enable row level security;

revoke all on table public.contact_support_outbox from anon, authenticated;
revoke all on table public.contact_support_delivery_event_inbox from anon, authenticated;
grant select, insert, update on table public.contact_support_outbox to service_role;
grant select, insert, update on table public.contact_support_delivery_event_inbox to service_role;

revoke all on function public.enqueue_contact_support_request(text, text, text, text, text, text, text, text, text, jsonb, text) from public, anon, authenticated;
revoke all on function public.claim_contact_support_outbox(text) from public, anon, authenticated;
revoke all on function public.record_contact_support_delivery_event(text, text, text, text, text, timestamptz, text) from public, anon, authenticated;
revoke all on function public.complete_contact_support_outbox(uuid, text, text) from public, anon, authenticated;
revoke all on function public.fail_contact_support_outbox(uuid, text, text) from public, anon, authenticated;
grant execute on function public.enqueue_contact_support_request(text, text, text, text, text, text, text, text, text, jsonb, text) to service_role;
grant execute on function public.claim_contact_support_outbox(text) to service_role;
grant execute on function public.record_contact_support_delivery_event(text, text, text, text, text, timestamptz, text) to service_role;
grant execute on function public.complete_contact_support_outbox(uuid, text, text) to service_role;
grant execute on function public.fail_contact_support_outbox(uuid, text, text) to service_role;

commit;
