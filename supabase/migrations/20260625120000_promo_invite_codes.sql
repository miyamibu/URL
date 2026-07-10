create extension if not exists pgcrypto;

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

create index if not exists idx_promo_invite_codes_claimed_by
    on public.promo_invite_codes (claimed_by, claimed_at desc);

drop trigger if exists trg_promo_invite_codes_updated_at on public.promo_invite_codes;
create trigger trg_promo_invite_codes_updated_at
before update on public.promo_invite_codes
for each row
execute function private.set_updated_at();

alter table public.promo_invite_codes enable row level security;

revoke all on table public.promo_invite_codes from anon, authenticated;

create table if not exists public.promo_invite_code_events (
    id uuid primary key default gen_random_uuid(),
    promo_invite_code_id uuid null references public.promo_invite_codes(id) on delete set null,
    user_id uuid null references auth.users(id) on delete set null,
    event text not null check (event in ('redeemed', 'failed')),
    reason text null,
    created_at timestamptz not null default now()
);

create index if not exists idx_promo_invite_code_events_user
    on public.promo_invite_code_events (user_id, created_at desc);

alter table public.promo_invite_code_events enable row level security;

revoke all on table public.promo_invite_code_events from anon, authenticated;

create or replace function public.redeem_promo_code(p_code text)
returns table (
    id uuid,
    plan text,
    source text,
    store_platform text,
    store_transaction_id text,
    starts_at timestamptz,
    expires_at timestamptz,
    status text
)
language plpgsql
security definer
set search_path = public, auth, pg_temp
as $$
declare
    v_user_id uuid := auth.uid();
    v_user_email text;
    v_code_hash text;
    v_code public.promo_invite_codes%rowtype;
    v_grant_id uuid;
begin
    if v_user_id is null then
        raise exception 'サインインが必要です';
    end if;

    if p_code is null or length(trim(p_code)) = 0 then
        raise exception '優待コードを入力してください';
    end if;

    v_code_hash := encode(digest(upper(trim(p_code)), 'sha256'), 'hex');

    select lower(email)
      into v_user_email
      from auth.users
     where id = v_user_id;

    select *
      into v_code
      from public.promo_invite_codes
     where code_hash = v_code_hash
     for update;

    if not found then
        insert into public.promo_invite_code_events(user_id, event, reason)
        values (v_user_id, 'failed', 'not_found');
        raise exception '優待コードが無効です';
    end if;

    if v_code.revoked_at is not null then
        insert into public.promo_invite_code_events(promo_invite_code_id, user_id, event, reason)
        values (v_code.id, v_user_id, 'failed', 'revoked');
        raise exception 'この優待コードは利用できません';
    end if;

    if v_code.expires_at is not null and v_code.expires_at <= now() then
        insert into public.promo_invite_code_events(promo_invite_code_id, user_id, event, reason)
        values (v_code.id, v_user_id, 'failed', 'expired');
        raise exception 'この優待コードは期限切れです';
    end if;

    if v_code.claimed_by is not null then
        insert into public.promo_invite_code_events(promo_invite_code_id, user_id, event, reason)
        values (v_code.id, v_user_id, 'failed', 'already_claimed');
        raise exception 'この優待コードはすでに使用されています';
    end if;

    if v_code.recipient_email is not null and lower(v_code.recipient_email) <> v_user_email then
        insert into public.promo_invite_code_events(promo_invite_code_id, user_id, event, reason)
        values (v_code.id, v_user_id, 'failed', 'email_mismatch');
        raise exception 'このアカウントでは利用できない優待コードです';
    end if;

    update public.promo_invite_codes
       set claimed_by = v_user_id,
           claimed_at = now()
     where promo_invite_codes.id = v_code.id;

    insert into public.user_entitlement_grants (
        user_id,
        plan,
        source,
        store_platform,
        store_transaction_id,
        starts_at,
        expires_at,
        status
    )
    values (
        v_user_id,
        v_code.plan,
        'store_promo_code',
        'promo_code',
        v_code.id::text,
        now(),
        null,
        'active'
    )
    returning user_entitlement_grants.id into v_grant_id;

    insert into public.promo_invite_code_events(promo_invite_code_id, user_id, event, reason)
    values (v_code.id, v_user_id, 'redeemed', null);

    return query
    select
        grant_row.id,
        grant_row.plan,
        grant_row.source,
        grant_row.store_platform,
        grant_row.store_transaction_id,
        grant_row.starts_at,
        grant_row.expires_at,
        grant_row.status
    from public.user_entitlement_grants grant_row
    where grant_row.user_id = v_user_id
      and grant_row.status = 'active'
      and grant_row.starts_at <= now()
      and (grant_row.expires_at is null or grant_row.expires_at > now())
    order by
        case grant_row.plan
            when 'promo_pro' then 0
            when 'pro' then 1
            when 'launch_standard' then 2
            when 'free' then 3
            else 4
        end,
        grant_row.starts_at desc;
end;
$$;

revoke all on function public.redeem_promo_code(text) from public;
grant execute on function public.redeem_promo_code(text) to authenticated;
