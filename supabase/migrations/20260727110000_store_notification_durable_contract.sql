-- Durable App Store Server Notifications V2 / Google RTDN contract.
--
-- Provider adapters are responsible for cryptographic verification and for
-- checking the provider app identity. This migration deliberately never
-- creates a grant from a notification. It only applies a verified event to a
-- purchase verification and grant that already belong to the same provider
-- binding.

create table if not exists public.store_subscription_notification_events (
    id uuid primary key default gen_random_uuid(),
    provider text not null check (provider in ('app_store', 'google_play')),
    notification_id text not null,
    event_id text not null,
    event_type text not null,
    subscription_key text not null,
    purchase_token_hash text null,
    store_transaction_id text null,
    store_product_id text null,
    user_id uuid null references auth.users(id) on delete set null,
    action text not null check (action in ('activate', 'revoke', 'preserve', 'noop')),
    subscription_state text not null check (
        subscription_state in (
            'active',
            'billing_retry',
            'grace_period',
            'on_hold',
            'paused',
            'canceled',
            'revoked',
            'refunded',
            'expired',
            'pending',
            'unsupported'
        )
    ),
    expires_at timestamptz null,
    occurred_at timestamptz not null,
    signature_verified boolean not null default false,
    processing_status text not null default 'received' check (
        processing_status in ('received', 'applied', 'ignored', 'rejected')
    ),
    failure_reason text null,
    grant_id uuid null references public.user_entitlement_grants(id) on delete set null,
    detail jsonb not null default '{}'::jsonb check (jsonb_typeof(detail) = 'object'),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (length(trim(notification_id)) between 1 and 256),
    check (length(trim(event_id)) between 1 and 256),
    check (length(trim(subscription_key)) between 1 and 512),
    check (purchase_token_hash is null or purchase_token_hash ~ '^[0-9a-f]{64}$')
);

create unique index if not exists idx_store_notification_events_notification
    on public.store_subscription_notification_events (provider, notification_id);

create unique index if not exists idx_store_notification_events_event
    on public.store_subscription_notification_events (provider, event_id, event_type);

create index if not exists idx_store_notification_events_reconciliation
    on public.store_subscription_notification_events (processing_status, signature_verified, created_at)
    where processing_status = 'ignored' and signature_verified;

create index if not exists idx_store_notification_events_subscription
    on public.store_subscription_notification_events (provider, subscription_key, occurred_at desc);

create table if not exists public.store_subscription_states (
    provider text not null check (provider in ('app_store', 'google_play')),
    subscription_key text not null,
    user_id uuid not null references auth.users(id) on delete cascade,
    store_product_id text not null,
    state text not null check (
        state in (
            'active',
            'billing_retry',
            'grace_period',
            'on_hold',
            'paused',
            'canceled',
            'revoked',
            'refunded',
            'expired',
            'pending',
            'unsupported'
        )
    ),
    grant_id uuid null references public.user_entitlement_grants(id) on delete set null,
    expires_at timestamptz null,
    last_notification_id text not null,
    last_event_id text not null,
    last_event_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (provider, subscription_key)
);

create index if not exists idx_store_subscription_states_user
    on public.store_subscription_states (user_id, updated_at desc);

drop trigger if exists trg_store_subscription_notification_events_updated_at
    on public.store_subscription_notification_events;
create trigger trg_store_subscription_notification_events_updated_at
before update on public.store_subscription_notification_events
for each row
execute function private.set_updated_at();

drop trigger if exists trg_store_subscription_states_updated_at
    on public.store_subscription_states;
create trigger trg_store_subscription_states_updated_at
before update on public.store_subscription_states
for each row
execute function private.set_updated_at();

alter table public.store_subscription_notification_events enable row level security;
alter table public.store_subscription_states enable row level security;
revoke all on table public.store_subscription_notification_events from public, anon, authenticated;
revoke all on table public.store_subscription_states from public, anon, authenticated;
grant all on table public.store_subscription_notification_events to service_role;
grant all on table public.store_subscription_states to service_role;

create or replace function private.process_store_subscription_notification(
    p_event_row_id uuid,
    p_allow_replay boolean default false
)
returns table (
    event_id uuid,
    result text,
    grant_id uuid,
    user_id uuid,
    failure_reason text,
    grant_changed boolean
)
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    event_row public.store_subscription_notification_events%rowtype;
    verification_row public.store_purchase_verifications%rowtype;
    grant_row public.user_entitlement_grants%rowtype;
    state_row public.store_subscription_states%rowtype;
    v_original_transaction_id text;
    changed boolean := false;
begin
    select notification_event.*
      into event_row
      from public.store_subscription_notification_events notification_event
     where notification_event.id = p_event_row_id
     for update;

    if event_row.id is null then
        raise exception 'store_notification_not_found';
    end if;

    if event_row.processing_status = 'applied'
       or (
           event_row.processing_status = 'ignored'
           and (
               not p_allow_replay
               or event_row.failure_reason not in ('binding_not_found', 'grant_not_found')
           )
       )
       or event_row.processing_status = 'rejected' then
        event_id := event_row.id;
        result := event_row.processing_status;
        grant_id := event_row.grant_id;
        user_id := event_row.user_id;
        failure_reason := event_row.failure_reason;
        grant_changed := false;
        return next;
        return;
    end if;

    if not event_row.signature_verified then
        update public.store_subscription_notification_events
           set processing_status = 'rejected',
               failure_reason = 'provider_signature_unverified'
         where id = event_row.id;
        event_id := event_row.id;
        result := 'rejected';
        failure_reason := 'provider_signature_unverified';
        grant_changed := false;
        return next;
        return;
    end if;

    if event_row.provider = 'app_store' then
        v_original_transaction_id := nullif(
            regexp_replace(event_row.subscription_key, '^app_store:', ''),
            event_row.subscription_key
        );
        select verification.*
          into verification_row
          from public.store_purchase_verifications verification
         where verification.store_platform = 'app_store'
           and verification.original_transaction_id = v_original_transaction_id
           and verification.status = 'verified'
         order by verification.verified_at desc nulls last, verification.updated_at desc
         limit 1
         for update;
    else
        select verification.*
          into verification_row
          from public.store_purchase_verifications verification
         where verification.store_platform = 'google_play'
           and verification.purchase_token_hash = event_row.purchase_token_hash
           and verification.status = 'verified'
         order by verification.verified_at desc nulls last, verification.updated_at desc
         limit 1
         for update;
    end if;

    if verification_row.id is null then
        update public.store_subscription_notification_events
           set processing_status = 'ignored',
               failure_reason = 'binding_not_found'
         where id = event_row.id;
        event_id := event_row.id;
        result := 'ignored';
        failure_reason := 'binding_not_found';
        grant_changed := false;
        return next;
        return;
    end if;

    if event_row.user_id is not null and event_row.user_id <> verification_row.user_id then
        update public.store_subscription_notification_events
           set processing_status = 'rejected',
               failure_reason = 'user_binding_mismatch',
               user_id = verification_row.user_id
         where id = event_row.id;
        event_id := event_row.id;
        result := 'rejected';
        user_id := verification_row.user_id;
        failure_reason := 'user_binding_mismatch';
        grant_changed := false;
        return next;
        return;
    end if;

    if event_row.store_product_id is not null
       and event_row.store_product_id <> verification_row.store_product_id then
        update public.store_subscription_notification_events
           set processing_status = 'rejected',
               failure_reason = 'product_binding_mismatch',
               user_id = verification_row.user_id
         where id = event_row.id;
        event_id := event_row.id;
        result := 'rejected';
        user_id := verification_row.user_id;
        failure_reason := 'product_binding_mismatch';
        grant_changed := false;
        return next;
        return;
    end if;

    if verification_row.grant_id is not null then
        select grant_record.*
          into grant_row
          from public.user_entitlement_grants grant_record
         where grant_record.id = verification_row.grant_id
         for update;
    end if;

    if grant_row.id is null then
        select grant_record.*
          into grant_row
          from public.user_entitlement_grants grant_record
         where grant_record.user_id = verification_row.user_id
           and grant_record.source = 'store_subscription'
           and grant_record.store_platform = verification_row.store_platform
           and (
               grant_record.store_subscription_key = event_row.subscription_key
               or grant_record.store_transaction_id = verification_row.store_transaction_id
           )
         order by grant_record.updated_at desc
         limit 1
         for update;
    end if;

    if grant_row.id is not null
       and (
           grant_row.user_id <> verification_row.user_id
           or grant_row.source <> 'store_subscription'
           or grant_row.store_platform <> verification_row.store_platform
       ) then
        update public.store_subscription_notification_events
           set processing_status = 'rejected',
               failure_reason = 'grant_binding_mismatch',
               user_id = verification_row.user_id
         where id = event_row.id;
        event_id := event_row.id;
        result := 'rejected';
        user_id := verification_row.user_id;
        failure_reason := 'grant_binding_mismatch';
        grant_changed := false;
        return next;
        return;
    end if;

    if grant_row.id is null then
        update public.store_subscription_notification_events
           set processing_status = 'ignored',
               failure_reason = 'grant_not_found',
               user_id = verification_row.user_id
         where id = event_row.id;
        event_id := event_row.id;
        result := 'ignored';
        user_id := verification_row.user_id;
        failure_reason := 'grant_not_found';
        grant_changed := false;
        return next;
        return;
    end if;

    select state_record.*
      into state_row
      from public.store_subscription_states state_record
     where state_record.provider = event_row.provider
       and state_record.subscription_key = event_row.subscription_key
     for update;

    if state_row.last_event_at is not null
       and (
           event_row.occurred_at < state_row.last_event_at
           or (
               event_row.occurred_at = state_row.last_event_at
               and event_row.event_id <= state_row.last_event_id
           )
       ) then
        update public.store_subscription_notification_events
           set processing_status = 'ignored',
               failure_reason = 'stale_event',
               user_id = verification_row.user_id,
               grant_id = grant_row.id
         where id = event_row.id;
        event_id := event_row.id;
        result := 'ignored';
        grant_id := grant_row.id;
        user_id := verification_row.user_id;
        failure_reason := 'stale_event';
        grant_changed := false;
        return next;
        return;
    end if;

    if event_row.action = 'activate' then
        if event_row.expires_at is null or event_row.expires_at <= now() then
            update public.store_subscription_notification_events
               set processing_status = 'ignored',
                   failure_reason = 'authoritative_expiry_required',
                   user_id = verification_row.user_id,
                   grant_id = grant_row.id
             where id = event_row.id;
            event_id := event_row.id;
            result := 'ignored';
            grant_id := grant_row.id;
            user_id := verification_row.user_id;
            failure_reason := 'authoritative_expiry_required';
            grant_changed := false;
            return next;
            return;
        end if;

        changed := grant_row.status <> 'active'
            or grant_row.expires_at is distinct from event_row.expires_at
            or grant_row.store_subscription_key is distinct from event_row.subscription_key;

        update public.user_entitlement_grants
           set status = 'active',
               expires_at = event_row.expires_at,
               store_product_id = verification_row.store_product_id,
               billing_period = verification_row.billing_period,
               store_subscription_key = event_row.subscription_key
         where id = grant_row.id;
    elsif event_row.action = 'revoke' then
        changed := grant_row.status <> 'revoked';
        update public.user_entitlement_grants
           set status = 'revoked',
               expires_at = case
                   when event_row.expires_at is not null
                        and event_row.expires_at > starts_at
                   then least(coalesce(expires_at, event_row.expires_at), event_row.expires_at)
                   else expires_at
               end,
               store_subscription_key = event_row.subscription_key
         where id = grant_row.id;
    end if;

    if state_row.provider is null then
        insert into public.store_subscription_states (
            provider,
            subscription_key,
            user_id,
            store_product_id,
            state,
            grant_id,
            expires_at,
            last_notification_id,
            last_event_id,
            last_event_at
        ) values (
            event_row.provider,
            event_row.subscription_key,
            verification_row.user_id,
            verification_row.store_product_id,
            event_row.subscription_state,
            grant_row.id,
            event_row.expires_at,
            event_row.notification_id,
            event_row.event_id,
            event_row.occurred_at
        );
    else
        update public.store_subscription_states
           set user_id = verification_row.user_id,
               store_product_id = verification_row.store_product_id,
               state = event_row.subscription_state,
               grant_id = grant_row.id,
               expires_at = event_row.expires_at,
               last_notification_id = event_row.notification_id,
               last_event_id = event_row.event_id,
               last_event_at = event_row.occurred_at
         where provider = event_row.provider
           and subscription_key = event_row.subscription_key;
    end if;

    update public.store_subscription_notification_events
       set processing_status = 'applied',
           failure_reason = null,
           user_id = verification_row.user_id,
           grant_id = grant_row.id
     where id = event_row.id;

    event_id := event_row.id;
    result := 'applied';
    grant_id := grant_row.id;
    user_id := verification_row.user_id;
    failure_reason := null;
    grant_changed := changed;
    return next;
end;
$$;

revoke all on function private.process_store_subscription_notification(uuid, boolean)
    from public, anon, authenticated, service_role;

create or replace function public.apply_store_subscription_notification(
    p_provider text,
    p_notification_id text,
    p_event_id text,
    p_event_type text,
    p_subscription_key text,
    p_purchase_token_hash text,
    p_store_transaction_id text,
    p_store_product_id text,
    p_user_id uuid,
    p_action text,
    p_subscription_state text,
    p_expires_at timestamptz,
    p_occurred_at timestamptz,
    p_signature_verified boolean,
    p_detail jsonb default '{}'::jsonb
)
returns table (
    event_id uuid,
    result text,
    grant_id uuid,
    user_id uuid,
    failure_reason text,
    grant_changed boolean
)
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    event_row public.store_subscription_notification_events%rowtype;
    detail_value jsonb := coalesce(p_detail, '{}'::jsonb);
begin
    if p_provider not in ('app_store', 'google_play')
       or nullif(trim(coalesce(p_notification_id, '')), '') is null
       or nullif(trim(coalesce(p_event_id, '')), '') is null
       or nullif(trim(coalesce(p_event_type, '')), '') is null
       or nullif(trim(coalesce(p_subscription_key, '')), '') is null
       or p_action not in ('activate', 'revoke', 'preserve', 'noop')
       or p_subscription_state not in (
           'active', 'billing_retry', 'grace_period', 'on_hold', 'paused',
           'canceled', 'revoked', 'refunded', 'expired', 'pending', 'unsupported'
       )
       or p_occurred_at is null
       or jsonb_typeof(detail_value) <> 'object' then
        raise exception 'invalid_store_notification';
    end if;

    if not coalesce(p_signature_verified, false) then
        raise exception 'provider_signature_unverified';
    end if;

    if p_provider = 'google_play'
       and p_event_type <> 'test'
       and (p_purchase_token_hash is null or p_purchase_token_hash !~ '^[0-9a-f]{64}$') then
        raise exception 'google_purchase_binding_required';
    end if;

    if p_provider = 'app_store' and p_user_id is null then
        raise exception 'app_store_user_binding_required';
    end if;

    begin
        insert into public.store_subscription_notification_events (
            provider,
            notification_id,
            event_id,
            event_type,
            subscription_key,
            purchase_token_hash,
            store_transaction_id,
            store_product_id,
            user_id,
            action,
            subscription_state,
            expires_at,
            occurred_at,
            signature_verified,
            processing_status,
            detail
        ) values (
            p_provider,
            trim(p_notification_id),
            trim(p_event_id),
            trim(p_event_type),
            trim(p_subscription_key),
            nullif(trim(coalesce(p_purchase_token_hash, '')), ''),
            nullif(trim(coalesce(p_store_transaction_id, '')), ''),
            nullif(trim(coalesce(p_store_product_id, '')), ''),
            p_user_id,
            p_action,
            p_subscription_state,
            p_expires_at,
            p_occurred_at,
            true,
            'received',
            detail_value
        )
        returning * into event_row;
    exception
        when unique_violation then
            select notification_event.*
              into event_row
              from public.store_subscription_notification_events notification_event
             where notification_event.provider = p_provider
               and (
                   notification_event.notification_id = trim(p_notification_id)
                   or (
                       notification_event.event_id = trim(p_event_id)
                       and notification_event.event_type = trim(p_event_type)
                   )
               )
             order by notification_event.created_at
             limit 1;

            if event_row.id is null then
                raise;
            end if;
    end;

    return query
    select processed.event_id,
           processed.result,
           processed.grant_id,
           processed.user_id,
           processed.failure_reason,
           processed.grant_changed
      from private.process_store_subscription_notification(event_row.id, false) processed;
end;
$$;

revoke all on function public.apply_store_subscription_notification(
    text, text, text, text, text, text, text, text, uuid, text, text, timestamptz, timestamptz, boolean, jsonb
) from public, anon, authenticated;
grant execute on function public.apply_store_subscription_notification(
    text, text, text, text, text, text, text, text, uuid, text, text, timestamptz, timestamptz, boolean, jsonb
) to service_role;

create or replace function public.reconcile_store_subscription_notification(p_event_row_id uuid)
returns table (
    event_id uuid,
    result text,
    grant_id uuid,
    user_id uuid,
    failure_reason text,
    grant_changed boolean
)
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
begin
    return query
    select processed.event_id,
           processed.result,
           processed.grant_id,
           processed.user_id,
           processed.failure_reason,
           processed.grant_changed
      from private.process_store_subscription_notification(p_event_row_id, true) processed;
end;
$$;

revoke all on function public.reconcile_store_subscription_notification(uuid)
    from public, anon, authenticated;
grant execute on function public.reconcile_store_subscription_notification(uuid)
    to service_role;
