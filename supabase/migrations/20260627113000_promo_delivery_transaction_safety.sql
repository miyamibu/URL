alter table public.promo_invite_code_events
    add column if not exists provider text null,
    add column if not exists provider_event_id text null,
    add column if not exists event_at timestamptz null;

create unique index if not exists idx_promo_invite_code_events_provider_event
    on public.promo_invite_code_events (provider, provider_event_id)
    where provider_event_id is not null;

create unique index if not exists idx_promo_invite_codes_provider_message
    on public.promo_invite_codes (delivery_provider, delivery_message_id)
    where delivery_provider is not null
      and delivery_message_id is not null;

create table if not exists public.promo_invite_delivery_event_inbox (
    id uuid primary key default gen_random_uuid(),
    provider text not null,
    provider_event_id text null,
    delivery_message_id text not null,
    event_type text not null,
    delivery_status text not null,
    event_name text not null,
    event_at timestamptz not null,
    delivery_error text null,
    detail jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    processed_at timestamptz null,
    code_id uuid null references public.promo_invite_codes(id) on delete set null
);

alter table public.promo_invite_delivery_event_inbox enable row level security;
revoke all on table public.promo_invite_delivery_event_inbox from anon, authenticated;
grant select, insert, update, delete on table public.promo_invite_delivery_event_inbox to service_role;

create unique index if not exists idx_promo_invite_delivery_event_inbox_provider_event
    on public.promo_invite_delivery_event_inbox (provider, provider_event_id)
    where provider_event_id is not null;

create index if not exists idx_promo_invite_delivery_event_inbox_message
    on public.promo_invite_delivery_event_inbox (provider, delivery_message_id, processed_at, event_at);

create or replace function private.promo_delivery_rank(p_status text)
returns integer
language sql
immutable
as $$
    select case p_status
        when 'revoked' then 1000
        when 'complained' then 950
        when 'bounced' then 940
        when 'failed' then 930
        when 'delivered' then 900
        when 'delivery_delayed' then 200
        when 'sent' then 100
        else 0
    end
$$;

create or replace function private.lock_promo_delivery_message(
    p_provider text,
    p_delivery_message_id text
)
returns void
language sql
as $$
    select pg_advisory_xact_lock(
        hashtextextended(
            'promo_delivery:' || coalesce(p_provider, '') || ':' || coalesce(p_delivery_message_id, ''),
            0
        )
    )
$$;

create or replace function private.apply_promo_delivery_event(
    p_code_id uuid,
    p_delivery_message_id text,
    p_provider_event_id text,
    p_event_type text,
    p_delivery_status text,
    p_event_name text,
    p_event_at timestamptz,
    p_delivery_error text,
    p_detail jsonb default '{}'::jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    code_record public.promo_invite_codes%rowtype;
    effective_event_at timestamptz := coalesce(p_event_at, now());
    normalized_message_id text := nullif(btrim(coalesce(p_delivery_message_id, '')), '');
    normalized_provider_event_id text := nullif(btrim(coalesce(p_provider_event_id, '')), '');
    current_rank integer;
    incoming_rank integer;
    should_update boolean;
    inserted_event_id uuid;
begin
    if normalized_message_id is null then
        raise exception 'delivery_message_id_required';
    end if;

    select *
      into code_record
      from public.promo_invite_codes
     where id = p_code_id
     for update;

    if code_record.id is null then
        raise exception 'promo_code_not_found';
    end if;

    insert into public.promo_invite_code_events (
        code_id, event, provider, provider_event_id, event_at, detail
    )
    values (
        code_record.id,
        p_event_name,
        'resend',
        normalized_provider_event_id,
        effective_event_at,
        coalesce(p_detail, '{}'::jsonb) ||
            jsonb_build_object(
                'provider', 'resend',
                'email_id', normalized_message_id,
                'received_at', effective_event_at,
                'delivery_status', p_delivery_status
            )
    )
    on conflict (provider, provider_event_id) where provider_event_id is not null do nothing
    returning id into inserted_event_id;

    if normalized_provider_event_id is not null and inserted_event_id is null then
        return jsonb_build_object('accepted', true, 'matched', true, 'duplicate', true);
    end if;

    current_rank := private.promo_delivery_rank(code_record.delivery_status);
    incoming_rank := private.promo_delivery_rank(p_delivery_status);

    should_update :=
        code_record.revoked_at is null
        and (
            incoming_rank > current_rank
            or (
                incoming_rank = current_rank
                and (code_record.delivery_event_at is null or effective_event_at >= code_record.delivery_event_at)
            )
        );

    if should_update then
        update public.promo_invite_codes
           set delivery_status = p_delivery_status,
               delivery_event_type = p_event_type,
               delivery_event_at = effective_event_at,
               delivery_error = p_delivery_error
         where id = code_record.id;
    end if;

    return jsonb_build_object(
        'accepted', true,
        'matched', true,
        'duplicate', false,
        'updated', should_update,
        'status', case when should_update then p_delivery_status else code_record.delivery_status end
    );
end;
$$;

create or replace function public.admin_record_promo_email_sent(
    p_code_id uuid,
    p_actor_user_id uuid,
    p_message_id text,
    p_event_at timestamptz default now()
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    code_record public.promo_invite_codes%rowtype;
    effective_event_at timestamptz := coalesce(p_event_at, now());
    normalized_message_id text := nullif(btrim(coalesce(p_message_id, '')), '');
    inbox_record public.promo_invite_delivery_event_inbox%rowtype;
    reconcile_result jsonb;
begin
    select *
      into code_record
      from public.promo_invite_codes
     where id = p_code_id
     for update;

    if code_record.id is null then
        raise exception 'promo_code_not_found';
    end if;
    if code_record.revoked_at is not null then
        raise exception 'promo_code_revoked';
    end if;
    if normalized_message_id is not null then
        perform private.lock_promo_delivery_message('resend', normalized_message_id);
    end if;

    update public.promo_invite_codes
       set delivery_status = 'sent',
           sent_at = coalesce(sent_at, effective_event_at),
           delivery_provider = 'resend',
           delivery_message_id = normalized_message_id,
           delivery_event_type = 'email.sent',
           delivery_event_at = effective_event_at,
           delivery_error = null
     where id = p_code_id;

    insert into public.promo_invite_code_events (
        code_id, event, actor_user_id, provider, provider_event_id, event_at, detail
    )
    values (
        p_code_id,
        'email_sent',
        p_actor_user_id,
        'resend',
        case when normalized_message_id is null
            then null
            else 'send:' || normalized_message_id
        end,
        effective_event_at,
        jsonb_build_object('provider', 'resend', 'message_id', normalized_message_id)
    )
    on conflict (provider, provider_event_id) where provider_event_id is not null do nothing;

    if normalized_message_id is not null then
        for inbox_record in
            select *
              from public.promo_invite_delivery_event_inbox
             where provider = 'resend'
               and delivery_message_id = normalized_message_id
               and processed_at is null
             order by event_at asc, created_at asc
             for update
        loop
            reconcile_result := private.apply_promo_delivery_event(
                p_code_id,
                normalized_message_id,
                inbox_record.provider_event_id,
                inbox_record.event_type,
                inbox_record.delivery_status,
                inbox_record.event_name,
                inbox_record.event_at,
                inbox_record.delivery_error,
                inbox_record.detail
            );

            update public.promo_invite_delivery_event_inbox
               set processed_at = now(),
                   code_id = p_code_id
             where id = inbox_record.id;
        end loop;
    end if;

    return jsonb_build_object('ok', true, 'id', p_code_id, 'delivery_status', 'sent');
end;
$$;

create or replace function public.admin_record_promo_email_failed(
    p_code_id uuid,
    p_admin_id uuid,
    p_actor_user_id uuid,
    p_error text,
    p_event_at timestamptz default now()
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    code_record public.promo_invite_codes%rowtype;
    effective_event_at timestamptz := coalesce(p_event_at, now());
    error_message text := left(coalesce(nullif(btrim(p_error), ''), 'メール送信に失敗しました'), 2000);
begin
    select *
      into code_record
      from public.promo_invite_codes
     where id = p_code_id
     for update;

    if code_record.id is null then
        raise exception 'promo_code_not_found';
    end if;
    if code_record.revoked_at is not null
        or code_record.claimed_at is not null
        or code_record.delivery_status <> 'pending'
        or code_record.delivery_message_id is not null
        or code_record.sent_at is not null then
        return jsonb_build_object('ok', false, 'id', p_code_id, 'skipped', true);
    end if;

    update public.promo_invite_codes
       set delivery_status = 'failed',
           revoked_at = effective_event_at,
           revoked_by = p_admin_id,
           revoked_reason = 'email_failed',
           delivery_provider = 'resend',
           delivery_event_type = 'email.failed',
           delivery_event_at = effective_event_at,
           delivery_error = error_message
     where id = p_code_id;

    insert into public.promo_invite_code_events (
        code_id, event, actor_user_id, provider, event_at, detail
    )
    values (
        p_code_id,
        'email_failed',
        p_actor_user_id,
        'resend',
        effective_event_at,
        jsonb_build_object('provider', 'resend', 'error', error_message)
    );

    return jsonb_build_object('ok', true, 'id', p_code_id, 'delivery_status', 'failed');
end;
$$;

create or replace function public.admin_revoke_promo_invite_code(
    p_code_id uuid,
    p_admin_id uuid,
    p_actor_user_id uuid,
    p_reason text,
    p_event_at timestamptz default now()
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    code_record public.promo_invite_codes%rowtype;
    effective_event_at timestamptz := coalesce(p_event_at, now());
    revoke_reason text := left(coalesce(nullif(btrim(p_reason), ''), 'admin_revoked'), 500);
begin
    select *
      into code_record
      from public.promo_invite_codes
     where id = p_code_id
     for update;

    if code_record.id is null then
        raise exception 'promo_code_not_found';
    end if;
    if code_record.revoked_at is not null then
        raise exception 'promo_code_already_revoked';
    end if;
    if code_record.claimed_at is not null then
        raise exception 'promo_code_already_claimed';
    end if;

    update public.promo_invite_codes
       set delivery_status = 'revoked',
           revoked_at = effective_event_at,
           revoked_by = p_admin_id,
           revoked_reason = revoke_reason
     where id = p_code_id;

    insert into public.promo_invite_code_events (
        code_id, event, actor_user_id, event_at, detail
    )
    values (
        p_code_id,
        'revoked',
        p_actor_user_id,
        effective_event_at,
        jsonb_build_object('reason', revoke_reason)
    );

    return jsonb_build_object('ok', true, 'id', p_code_id, 'delivery_status', 'revoked');
end;
$$;

create or replace function public.record_resend_promo_delivery_event(
    p_delivery_message_id text,
    p_provider_event_id text,
    p_event_type text,
    p_delivery_status text,
    p_event_name text,
    p_event_at timestamptz,
    p_delivery_error text,
    p_detail jsonb default '{}'::jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    effective_event_at timestamptz := coalesce(p_event_at, now());
    normalized_message_id text := nullif(btrim(coalesce(p_delivery_message_id, '')), '');
    normalized_provider_event_id text := nullif(btrim(coalesce(p_provider_event_id, '')), '');
    code_id uuid;
begin
    if normalized_message_id is null then
        raise exception 'delivery_message_id_required';
    end if;
    perform private.lock_promo_delivery_message('resend', normalized_message_id);

    if normalized_provider_event_id is not null and exists (
        select 1
          from public.promo_invite_code_events
         where provider = 'resend'
           and provider_event_id = normalized_provider_event_id
    ) then
        return jsonb_build_object('accepted', true, 'matched', false, 'duplicate', true);
    end if;

    select id
      into code_id
      from public.promo_invite_codes
     where delivery_provider = 'resend'
       and delivery_message_id = normalized_message_id
     for update;

    if code_id is null then
        insert into public.promo_invite_delivery_event_inbox (
            provider,
            provider_event_id,
            delivery_message_id,
            event_type,
            delivery_status,
            event_name,
            event_at,
            delivery_error,
            detail
        )
        values (
            'resend',
            normalized_provider_event_id,
            normalized_message_id,
            p_event_type,
            p_delivery_status,
            p_event_name,
            effective_event_at,
            p_delivery_error,
            coalesce(p_detail, '{}'::jsonb)
        )
        on conflict (provider, provider_event_id) where provider_event_id is not null do nothing;

        return jsonb_build_object('accepted', true, 'matched', false, 'duplicate', false);
    end if;

    return private.apply_promo_delivery_event(
        code_id,
        normalized_message_id,
        normalized_provider_event_id,
        p_event_type,
        p_delivery_status,
        p_event_name,
        effective_event_at,
        p_delivery_error,
        coalesce(p_detail, '{}'::jsonb)
    );
end;
$$;

revoke all on function public.admin_record_promo_email_sent(uuid, uuid, text, timestamptz) from public;
revoke all on function public.admin_record_promo_email_failed(uuid, uuid, uuid, text, timestamptz) from public;
revoke all on function public.admin_revoke_promo_invite_code(uuid, uuid, uuid, text, timestamptz) from public;
revoke all on function public.record_resend_promo_delivery_event(text, text, text, text, text, timestamptz, text, jsonb) from public;

grant execute on function public.admin_record_promo_email_sent(uuid, uuid, text, timestamptz) to service_role;
grant execute on function public.admin_record_promo_email_failed(uuid, uuid, uuid, text, timestamptz) to service_role;
grant execute on function public.admin_revoke_promo_invite_code(uuid, uuid, uuid, text, timestamptz) to service_role;
grant execute on function public.record_resend_promo_delivery_event(text, text, text, text, text, timestamptz, text, jsonb) to service_role;
