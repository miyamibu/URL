create extension if not exists pgcrypto;
create schema if not exists private;
create table if not exists public.promo_invite_codes (
    id uuid primary key default gen_random_uuid(),
    code_hash text not null unique,
    target_email text not null,
    created_by uuid null references public.admin_users(id) on delete set null,
    created_at timestamptz not null default now(),
    expires_at timestamptz null,
    claimed_by uuid null references auth.users(id) on delete set null,
    claimed_at timestamptz null,
    revoked_at timestamptz null,
    note text null,
    check (target_email = lower(btrim(target_email))),
    check (expires_at is null or expires_at > created_at),
    check ((claimed_by is null and claimed_at is null) or (claimed_by is not null and claimed_at is not null))
);
create index if not exists idx_promo_invite_codes_target_email
    on public.promo_invite_codes (target_email, created_at desc);
create index if not exists idx_promo_invite_codes_active
    on public.promo_invite_codes (expires_at)
    where revoked_at is null and claimed_at is null;
alter table public.promo_invite_codes enable row level security;
revoke all on table public.promo_invite_codes from public, anon, authenticated;
create table if not exists public.promo_invite_code_events (
    id uuid primary key default gen_random_uuid(),
    code_id uuid not null references public.promo_invite_codes(id) on delete cascade,
    event text not null check (event in ('redeemed')),
    actor_user_id uuid null references auth.users(id) on delete set null,
    created_at timestamptz not null default now(),
    detail jsonb not null default '{}'::jsonb
);
create index if not exists idx_promo_invite_code_events_code
    on public.promo_invite_code_events (code_id, created_at desc);
create index if not exists idx_promo_invite_code_events_actor
    on public.promo_invite_code_events (actor_user_id, created_at desc);
alter table public.promo_invite_code_events enable row level security;
revoke all on table public.promo_invite_code_events from public, anon, authenticated;
create or replace function private.hash_promo_invite_code(raw_code text)
returns text
language sql
immutable
as $$
    select encode(extensions.digest(upper(regexp_replace(btrim(raw_code), '\s+', '', 'g')), 'sha256'), 'hex')
$$;
revoke all on function private.hash_promo_invite_code(text) from public, anon, authenticated;
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
        if existing_invite.target_email <> caller_email then
            raise exception 'promo_code_email_mismatch';
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
            jsonb_build_object(
                'plan', 'promo_pro',
                'grant_id', grant_record.id
            )
        );
    end if;

    return jsonb_build_object(
        'status', 'active',
        'plan', 'promo_pro',
        'grant_id', grant_record.id,
        'claimed_at', invite_record.claimed_at
    );
end;
$$;
revoke all on function public.redeem_promo_code(text) from public, anon, authenticated;
grant execute on function public.redeem_promo_code(text) to authenticated;
