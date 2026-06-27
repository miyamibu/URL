alter table public.promo_invite_codes
    add column if not exists delivery_event_type text null,
    add column if not exists delivery_event_at timestamptz null;

do $$
begin
    alter table public.promo_invite_codes
        drop constraint if exists promo_invite_codes_delivery_status_check;

    alter table public.promo_invite_codes
        add constraint promo_invite_codes_delivery_status_check
        check (delivery_status in (
            'pending',
            'sent',
            'delivered',
            'delivery_delayed',
            'bounced',
            'complained',
            'failed',
            'revoked'
        ));
end
$$;

do $$
begin
    alter table public.promo_invite_code_events
        drop constraint if exists promo_invite_code_events_event_check;

    alter table public.promo_invite_code_events
        add constraint promo_invite_code_events_event_check
        check (event in (
            'issued',
            'email_sent',
            'email_delivered',
            'email_delivery_delayed',
            'email_bounced',
            'email_complained',
            'email_failed',
            'email_suppressed',
            'revoked',
            'redeemed',
            'failed'
        ));
end
$$;

drop function if exists public.redeem_promo_code(text);

create or replace function public.redeem_promo_code(p_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    caller_email text;
    normalized_code text := upper(regexp_replace(btrim(coalesce(p_code, '')), '\s+', '', 'g'));
    invite_record public.promo_invite_codes%rowtype;
    existing_invite public.promo_invite_codes%rowtype;
    grant_record public.user_entitlement_grants%rowtype;
    claimed_now boolean := false;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    if normalized_code = '' then
        raise exception 'invalid_promo_code';
    end if;

    select lower(btrim(email))
      into caller_email
      from auth.users
     where id = caller
     limit 1;

    if caller_email is null or caller_email = '' then
        raise exception 'auth_required';
    end if;

    update public.promo_invite_codes code
       set claimed_by = caller,
           claimed_at = now()
     where code.code_hash = private.hash_promo_invite_code(normalized_code)
       and code.target_email = caller_email
       and code.claimed_by is null
       and code.revoked_at is null
       and code.delivery_status in ('sent', 'delivered')
       and (code.expires_at is null or code.expires_at > now())
     returning code.* into invite_record;

    if invite_record.id is null then
        select code.*
          into existing_invite
          from public.promo_invite_codes code
         where code.code_hash = private.hash_promo_invite_code(normalized_code)
         limit 1;

        if existing_invite.id is null then
            raise exception 'invalid_promo_code';
        end if;
        if existing_invite.target_email is null or existing_invite.target_email <> caller_email then
            raise exception 'promo_code_email_mismatch';
        end if;
        if existing_invite.delivery_status not in ('sent', 'delivered') then
            raise exception 'promo_code_not_sent';
        end if;
        if existing_invite.revoked_at is not null then
            raise exception 'promo_code_revoked';
        end if;
        if existing_invite.expires_at is not null and existing_invite.expires_at <= now() then
            raise exception 'promo_code_expired';
        end if;
        if existing_invite.claimed_by is not null and existing_invite.claimed_by <> caller then
            raise exception 'promo_code_already_claimed';
        end if;

        invite_record := existing_invite;
    else
        claimed_now := true;
    end if;

    select grant_row.*
      into grant_record
      from public.user_entitlement_grants grant_row
     where grant_row.user_id = caller
       and grant_row.plan = 'promo_pro'
       and grant_row.source = 'admin_grant'
       and grant_row.status = 'active'
       and grant_row.starts_at <= now()
       and grant_row.expires_at is null
     order by grant_row.starts_at desc
     limit 1;

    if grant_record.id is null then
        insert into public.user_entitlement_grants (
            user_id, plan, source, starts_at, expires_at, status
        )
        values (
            caller, 'promo_pro', 'admin_grant', now(), null, 'active'
        )
        returning * into grant_record;
    end if;

    if claimed_now then
        insert into public.promo_invite_code_events (
            code_id, event, actor_user_id, detail
        )
        values (
            invite_record.id,
            'redeemed',
            caller,
            jsonb_build_object('plan', 'promo_pro', 'grant_id', grant_record.id)
        );
    end if;

    return jsonb_build_object(
        'id', grant_record.id,
        'status', grant_record.status,
        'plan', grant_record.plan,
        'source', grant_record.source,
        'store_platform', grant_record.store_platform,
        'store_transaction_id', grant_record.store_transaction_id,
        'starts_at', grant_record.starts_at,
        'expires_at', grant_record.expires_at,
        'grant_id', grant_record.id,
        'claimed_at', invite_record.claimed_at
    );
end;
$$;

revoke all on function public.redeem_promo_code(text) from public;
grant execute on function public.redeem_promo_code(text) to authenticated;
