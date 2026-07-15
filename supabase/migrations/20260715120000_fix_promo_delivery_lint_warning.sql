-- Keep the promo delivery reconciliation side effect while discarding its JSON result.
-- This replaces the previously applied function without rewriting migration history.
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
            perform private.apply_promo_delivery_event(
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
