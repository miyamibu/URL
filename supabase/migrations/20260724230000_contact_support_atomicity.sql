-- Contact support hardening: atomic reservations, idempotent retries, and
-- monotonic provider delivery events. The Edge Function uses service_role
-- only; end users never receive table or function access.

alter table public.contact_support_requests
    add column if not exists idempotency_key_hash text null;

do $$
begin
    if exists (
        select 1
        from public.contact_support_requests
        where idempotency_key_hash is not null
        group by idempotency_key_hash
        having count(*) > 1
    ) then
        raise exception 'contact_support_migration_blocked: duplicate idempotency keys exist';
    end if;
end;
$$;

create unique index if not exists idx_contact_support_requests_idempotency
    on public.contact_support_requests (idempotency_key_hash)
    where idempotency_key_hash is not null;

create table if not exists public.contact_support_delivery_events (
    id uuid primary key default gen_random_uuid(),
    provider text not null check (provider in ('resend')),
    provider_event_id text not null,
    email_id text not null,
    event_type text not null,
    delivery_status text not null check (delivery_status in (
        'sent',
        'delivered',
        'delivery_delayed',
        'bounced',
        'complained',
        'failed',
        'suppressed'
    )),
    event_at timestamptz not null,
    delivery_error text null,
    received_at timestamptz not null default now(),
    unique (provider, provider_event_id)
);

create index if not exists idx_contact_support_delivery_events_email
    on public.contact_support_delivery_events (provider, email_id, event_at desc);

alter table public.contact_support_delivery_events enable row level security;
revoke all on table public.contact_support_delivery_events from public, anon, authenticated;
grant select, insert on table public.contact_support_delivery_events to service_role;

create or replace function public.reserve_contact_support_request(
    p_request_id text,
    p_idempotency_key_hash text,
    p_email_hash text,
    p_auth_user_id_hash text,
    p_ip_hash text,
    p_platform text,
    p_app_version text,
    p_build_type text,
    p_is_signed_in boolean
)
returns table (
    id uuid,
    request_id text,
    delivery_status text,
    existing boolean
)
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    existing_row public.contact_support_requests%rowtype;
    email_lock bigint := hashtextextended('contact:email:' || p_email_hash, 0);
    ip_lock bigint := hashtextextended('contact:ip:' || p_ip_hash, 0);
begin
    if nullif(trim(coalesce(p_request_id, '')), '') is null
       or nullif(trim(coalesce(p_idempotency_key_hash, '')), '') is null then
        raise exception 'invalid_request';
    end if;

    -- Serialize the same client retry before applying rate limits.
    perform pg_advisory_xact_lock(hashtextextended('contact:key:' || p_idempotency_key_hash, 0));

    select *
      into existing_row
      from public.contact_support_requests
     where idempotency_key_hash = p_idempotency_key_hash
     for update;

    if existing_row.id is not null then
        return query select existing_row.id, existing_row.request_id, existing_row.delivery_status, true;
        return;
    end if;

    -- Lock the two rate-limit buckets in a deterministic order to avoid
    -- deadlocks when concurrent requests exchange their email/IP hashes.
    if email_lock = ip_lock then
        perform pg_advisory_xact_lock(email_lock);
    elsif email_lock < ip_lock then
        perform pg_advisory_xact_lock(email_lock);
        perform pg_advisory_xact_lock(ip_lock);
    else
        perform pg_advisory_xact_lock(ip_lock);
        perform pg_advisory_xact_lock(email_lock);
    end if;

    if (
        select count(*)
          from public.contact_support_requests
         where email_hash = p_email_hash
           and created_at > now() - interval '1 hour'
    ) >= 10 then
        raise exception 'rate_limited_email';
    end if;

    if (
        select count(*)
          from public.contact_support_requests
         where ip_hash = p_ip_hash
           and created_at > now() - interval '1 hour'
    ) >= 50
    or (
        select count(*)
          from public.contact_support_requests
         where ip_hash = p_ip_hash
           and created_at > now() - interval '24 hours'
    ) >= 200 then
        raise exception 'rate_limited';
    end if;

    insert into public.contact_support_requests (
        request_id,
        idempotency_key_hash,
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
        p_idempotency_key_hash,
        p_email_hash,
        p_auth_user_id_hash,
        p_ip_hash,
        p_platform,
        p_app_version,
        p_build_type,
        p_is_signed_in,
        'pending'
    )
    returning public.contact_support_requests.id,
              public.contact_support_requests.request_id,
              public.contact_support_requests.delivery_status
         into id, request_id, delivery_status;

    existing := false;
    return next;
end;
$$;

revoke all on function public.reserve_contact_support_request(text, text, text, text, text, text, text, text, boolean)
    from public, anon, authenticated;
grant execute on function public.reserve_contact_support_request(text, text, text, text, text, text, text, text, boolean)
    to service_role;

create or replace function public.record_contact_support_delivery_event(
    p_provider_event_id text,
    p_email_id text,
    p_event_type text,
    p_delivery_status text,
    p_event_at timestamptz,
    p_delivery_error text default null
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if nullif(trim(coalesce(p_provider_event_id, '')), '') is null
       or nullif(trim(coalesce(p_email_id, '')), '') is null then
        raise exception 'invalid_delivery_event';
    end if;

    insert into public.contact_support_delivery_events (
        provider,
        provider_event_id,
        email_id,
        event_type,
        delivery_status,
        event_at,
        delivery_error
    )
    values (
        'resend',
        p_provider_event_id,
        p_email_id,
        p_event_type,
        p_delivery_status,
        coalesce(p_event_at, now()),
        left(p_delivery_error, 500)
    )
    on conflict (provider, provider_event_id) do nothing;

    update public.contact_support_requests
       set delivery_status = p_delivery_status,
           delivery_provider = 'resend',
           delivery_message_id = p_email_id,
           delivery_event_type = p_event_type,
           delivery_event_at = coalesce(p_event_at, now()),
           delivery_error = left(p_delivery_error, 500)
     where delivery_provider = 'resend'
       and delivery_message_id = p_email_id
       and (
           case delivery_status
             when 'pending' then 0
             when 'sent' then 10
             when 'delivery_delayed' then 20
             when 'delivered' then 30
             else 40
           end < case p_delivery_status
             when 'sent' then 10
             when 'delivery_delayed' then 20
             when 'delivered' then 30
             else 40
           end
           or (
             case delivery_status
               when 'pending' then 0
               when 'sent' then 10
               when 'delivery_delayed' then 20
               when 'delivered' then 30
               else 40
             end = case p_delivery_status
               when 'sent' then 10
               when 'delivery_delayed' then 20
               when 'delivered' then 30
               else 40
             end
             and (delivery_event_at is null or delivery_event_at <= coalesce(p_event_at, now()))
           )
       );

    return true;
end;
$$;

revoke all on function public.record_contact_support_delivery_event(text, text, text, text, timestamptz, text)
    from public, anon, authenticated;
grant execute on function public.record_contact_support_delivery_event(text, text, text, text, timestamptz, text)
    to service_role;
