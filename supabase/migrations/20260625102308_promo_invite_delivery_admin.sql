create extension if not exists pgcrypto;

create schema if not exists private;

create table if not exists public.admin_users (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade unique,
    email text not null,
    role text not null check (role in ('owner', 'moderator', 'billing', 'readonly')),
    status text not null default 'active' check (status in ('active', 'suspended')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

drop trigger if exists trg_admin_users_updated_at on public.admin_users;
create trigger trg_admin_users_updated_at
before update on public.admin_users
for each row
execute function private.set_updated_at();

alter table public.admin_users enable row level security;
revoke all on table public.admin_users from anon, authenticated;

create table if not exists public.promo_invite_codes (
    id uuid primary key default gen_random_uuid(),
    code_hash text not null unique,
    label text null,
    plan text not null default 'promo_pro' check (plan in ('promo_pro')),
    recipient_email text null,
    expires_at timestamptz null,
    revoked_at timestamptz null,
    claimed_by uuid null references auth.users(id) on delete set null,
    claimed_at timestamptz null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (claimed_at is null or claimed_by is not null)
);

alter table public.promo_invite_codes enable row level security;
revoke all on table public.promo_invite_codes from anon, authenticated;

create table if not exists public.promo_invite_code_events (
    id uuid primary key default gen_random_uuid(),
    promo_invite_code_id uuid null references public.promo_invite_codes(id) on delete set null,
    user_id uuid null references auth.users(id) on delete set null,
    event text not null check (event in ('issued', 'email_sent', 'email_failed', 'revoked', 'redeemed', 'failed')),
    reason text null,
    created_at timestamptz not null default now()
);

alter table public.promo_invite_code_events enable row level security;
revoke all on table public.promo_invite_code_events from anon, authenticated;

alter table public.promo_invite_codes
    add column if not exists target_email text,
    add column if not exists created_by uuid null,
    add column if not exists note text null,
    add column if not exists delivery_status text not null default 'sent',
    add column if not exists sent_at timestamptz null,
    add column if not exists delivery_provider text null,
    add column if not exists delivery_message_id text null,
    add column if not exists delivery_error text null,
    add column if not exists revoked_by uuid null,
    add column if not exists revoked_reason text null;

do $$
begin
    if exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'promo_invite_codes'
          and column_name = 'recipient_email'
    ) then
        update public.promo_invite_codes
           set target_email = lower(btrim(recipient_email))
         where target_email is null
           and recipient_email is not null
           and btrim(recipient_email) <> '';
    end if;
end
$$;

update public.promo_invite_codes
   set target_email = lower(btrim(target_email))
 where target_email is not null
   and target_email <> lower(btrim(target_email));

update public.promo_invite_codes
   set delivery_status = case
        when revoked_at is not null then 'revoked'
        when delivery_status is null then 'sent'
        else delivery_status
   end;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'promo_invite_codes_delivery_status_check'
          and conrelid = 'public.promo_invite_codes'::regclass
    ) then
        alter table public.promo_invite_codes
            add constraint promo_invite_codes_delivery_status_check
            check (delivery_status in ('pending', 'sent', 'failed', 'revoked'));
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conname = 'promo_invite_codes_target_email_normalized_check'
          and conrelid = 'public.promo_invite_codes'::regclass
    ) then
        alter table public.promo_invite_codes
            add constraint promo_invite_codes_target_email_normalized_check
            check (target_email is null or target_email = lower(btrim(target_email)));
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conname = 'promo_invite_codes_created_by_admin_fkey'
          and conrelid = 'public.promo_invite_codes'::regclass
    ) then
        alter table public.promo_invite_codes
            add constraint promo_invite_codes_created_by_admin_fkey
            foreign key (created_by) references public.admin_users(id) on delete set null;
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conname = 'promo_invite_codes_revoked_by_admin_fkey'
          and conrelid = 'public.promo_invite_codes'::regclass
    ) then
        alter table public.promo_invite_codes
            add constraint promo_invite_codes_revoked_by_admin_fkey
            foreign key (revoked_by) references public.admin_users(id) on delete set null;
    end if;
end
$$;

create index if not exists idx_promo_invite_codes_target_email
    on public.promo_invite_codes (target_email, created_at desc);

create index if not exists idx_promo_invite_codes_delivery_status
    on public.promo_invite_codes (delivery_status, created_at desc);

alter table public.promo_invite_code_events
    add column if not exists code_id uuid null,
    add column if not exists actor_user_id uuid null,
    add column if not exists detail jsonb not null default '{}'::jsonb;

do $$
begin
    if exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'promo_invite_code_events'
          and column_name = 'promo_invite_code_id'
    ) then
        update public.promo_invite_code_events
           set code_id = promo_invite_code_id
         where code_id is null
           and promo_invite_code_id is not null;
    end if;

    if exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'promo_invite_code_events'
          and column_name = 'user_id'
    ) then
        update public.promo_invite_code_events
           set actor_user_id = user_id
         where actor_user_id is null
           and user_id is not null;
    end if;
end
$$;

do $$
begin
    alter table public.promo_invite_code_events
        drop constraint if exists promo_invite_code_events_event_check;

    alter table public.promo_invite_code_events
        add constraint promo_invite_code_events_event_check
        check (event in ('issued', 'email_sent', 'email_failed', 'revoked', 'redeemed'));
exception
    when duplicate_object then
        null;
end
$$;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'promo_invite_code_events_code_id_fkey'
          and conrelid = 'public.promo_invite_code_events'::regclass
    ) then
        alter table public.promo_invite_code_events
            add constraint promo_invite_code_events_code_id_fkey
            foreign key (code_id) references public.promo_invite_codes(id) on delete cascade;
    end if;

    if not exists (
        select 1
        from pg_constraint
        where conname = 'promo_invite_code_events_actor_user_id_fkey'
          and conrelid = 'public.promo_invite_code_events'::regclass
    ) then
        alter table public.promo_invite_code_events
            add constraint promo_invite_code_events_actor_user_id_fkey
            foreign key (actor_user_id) references auth.users(id) on delete set null;
    end if;
end
$$;

create index if not exists idx_promo_invite_code_events_code
    on public.promo_invite_code_events (code_id, created_at desc);

create index if not exists idx_promo_invite_code_events_actor
    on public.promo_invite_code_events (actor_user_id, created_at desc);

create or replace function private.hash_promo_invite_code(raw_code text)
returns text
language sql
immutable
as $$
    select encode(extensions.digest(upper(regexp_replace(btrim(coalesce(raw_code, '')), '\s+', '', 'g')), 'sha256'), 'hex')
$$;

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
       and code.delivery_status = 'sent'
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
        if existing_invite.delivery_status <> 'sent' then
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
