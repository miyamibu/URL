begin;

-- The user directory contains personal data and is intentionally callable
-- only through the service-role-backed Admin Web after owner authorization.
create or replace function public.admin_list_users(
    p_search text default '',
    p_status text default null,
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    user_id uuid,
    email text,
    display_name text,
    created_at timestamptz,
    last_sign_in_at timestamptz,
    last_seen_at timestamptz,
    current_plan text,
    account_status text,
    total_count bigint
)
language plpgsql
security definer
stable
set search_path = pg_catalog, public, auth, pg_temp
as $$
declare
    normalized_search text := lower(btrim(coalesce(p_search, '')));
    normalized_status text := nullif(lower(btrim(coalesce(p_status, ''))), '');
    safe_limit integer := least(greatest(coalesce(p_limit, 50), 1), 200);
    safe_offset integer := greatest(coalesce(p_offset, 0), 0);
begin
    if normalized_status is not null
       and normalized_status not in ('active', 'suspended', 'banned') then
        raise exception 'admin_user_status_invalid';
    end if;

    return query
    with matching_users as (
        select
            auth_user.id as user_id,
            auth_user.email::text as email,
            profile.display_name,
            auth_user.created_at,
            auth_user.last_sign_in_at,
            profile.last_seen_at,
            coalesce(profile.account_status, 'active') as account_status
        from auth.users auth_user
        left join public.user_profiles profile on profile.user_id = auth_user.id
        where (
            normalized_search = ''
            or position(normalized_search in lower(coalesce(auth_user.email, ''))) > 0
            or position(normalized_search in lower(coalesce(profile.display_name, ''))) > 0
            or lower(auth_user.id::text) = normalized_search
        )
        and (
            normalized_status is null
            or coalesce(profile.account_status, 'active') = normalized_status
        )
    ),
    counted_users as (
        select matching_users.*, count(*) over () as total_count
        from matching_users
        order by matching_users.created_at desc, matching_users.user_id
        limit safe_limit
        offset safe_offset
    )
    select
        counted.user_id,
        counted.email,
        counted.display_name,
        counted.created_at,
        counted.last_sign_in_at,
        counted.last_seen_at,
        coalesce(active_grant.plan, 'free') as current_plan,
        counted.account_status,
        counted.total_count
    from counted_users counted
    left join lateral (
        select entitlement.plan
        from public.user_entitlement_grants entitlement
        where entitlement.user_id = counted.user_id
          and entitlement.status = 'active'
          and entitlement.starts_at <= now()
          and (entitlement.expires_at is null or entitlement.expires_at > now())
        order by
            case entitlement.plan
                when 'promo_pro' then 0
                when 'pro' then 1
                when 'standard' then 2
                when 'launch_standard' then 3
                when 'free' then 4
                else 5
            end,
            entitlement.starts_at desc
        limit 1
    ) active_grant on true
    order by counted.created_at desc, counted.user_id;
end;
$$;

create or replace function public.admin_manage_user_audited(
    p_target_user_id uuid,
    p_admin_id uuid,
    p_actor_user_id uuid,
    p_action text,
    p_reason text,
    p_operation_id uuid,
    p_assurance jsonb default '{}'::jsonb,
    p_account_status text default null,
    p_admin_note text default null,
    p_plan text default null,
    p_expires_at timestamptz default null,
    p_grant_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth, pg_temp
as $$
declare
    admin_record public.admin_users%rowtype;
    before_profile public.user_profiles%rowtype;
    after_profile public.user_profiles%rowtype;
    before_grant public.user_entitlement_grants%rowtype;
    after_grant public.user_entitlement_grants%rowtype;
    normalized_action text := lower(btrim(coalesce(p_action, '')));
    normalized_reason text := nullif(left(btrim(coalesce(p_reason, '')), 1000), '');
    normalized_status text := lower(btrim(coalesce(p_account_status, '')));
    normalized_plan text := lower(btrim(coalesce(p_plan, '')));
    audit_action text;
    before_value jsonb;
    after_value jsonb;
begin
    if p_target_user_id is null or p_admin_id is null or p_actor_user_id is null or p_operation_id is null then
        raise exception 'admin_operation_required';
    end if;
    if normalized_reason is null then
        raise exception 'admin_reason_required';
    end if;
    if normalized_action not in ('update_note', 'set_status', 'grant_entitlement', 'revoke_entitlement') then
        raise exception 'admin_user_action_invalid';
    end if;
    if not exists (select 1 from auth.users where id = p_target_user_id) then
        raise exception 'admin_target_user_not_found';
    end if;

    select * into admin_record
      from public.admin_users
     where id = p_admin_id;
    if admin_record.id is null or admin_record.status <> 'active'
       or admin_record.role <> 'owner' or admin_record.user_id <> p_actor_user_id then
        raise exception 'admin_capability_denied';
    end if;

    insert into public.user_profiles (user_id)
    values (p_target_user_id)
    on conflict (user_id) do nothing;

    select * into before_profile
      from public.user_profiles
     where user_id = p_target_user_id
     for update;

    if normalized_action = 'update_note' then
        audit_action := 'user_admin_note_updated';
        before_value := jsonb_build_object('admin_note', before_profile.admin_note);
        update public.user_profiles
           set admin_note = nullif(left(btrim(coalesce(p_admin_note, '')), 2000), '')
         where user_id = p_target_user_id
         returning * into after_profile;
        after_value := jsonb_build_object('admin_note', after_profile.admin_note);
    elsif normalized_action = 'set_status' then
        if normalized_status not in ('active', 'suspended', 'banned') then
            raise exception 'admin_user_status_invalid';
        end if;
        if p_target_user_id = admin_record.user_id and normalized_status <> 'active' then
            raise exception 'admin_self_lockout_denied';
        end if;
        audit_action := 'user_status_updated';
        before_value := jsonb_build_object('account_status', before_profile.account_status);
        update public.user_profiles
           set account_status = normalized_status
         where user_id = p_target_user_id
         returning * into after_profile;
        after_value := jsonb_build_object('account_status', after_profile.account_status);
    elsif normalized_action = 'grant_entitlement' then
        if normalized_plan not in ('launch_standard', 'standard', 'pro', 'promo_pro') then
            raise exception 'admin_entitlement_plan_invalid';
        end if;
        if p_expires_at is not null and p_expires_at <= now() then
            raise exception 'admin_entitlement_expiry_invalid';
        end if;
        audit_action := 'user_entitlement_granted';
        insert into public.user_entitlement_grants (
            user_id, plan, source, starts_at, expires_at, status
        ) values (
            p_target_user_id, normalized_plan, 'admin_grant', now(), p_expires_at, 'active'
        ) returning * into after_grant;
        before_value := null;
        after_value := jsonb_build_object(
            'grant_id', after_grant.id,
            'plan', after_grant.plan,
            'expires_at', after_grant.expires_at,
            'status', after_grant.status
        );
    else
        if p_grant_id is null then
            raise exception 'admin_entitlement_grant_required';
        end if;
        select * into before_grant
          from public.user_entitlement_grants
         where id = p_grant_id
           and user_id = p_target_user_id
         for update;
        if before_grant.id is null then
            raise exception 'admin_entitlement_grant_not_found';
        end if;
        if before_grant.status <> 'active' then
            raise exception 'admin_entitlement_grant_not_active';
        end if;
        audit_action := 'user_entitlement_revoked';
        update public.user_entitlement_grants
           set status = 'revoked'
         where id = p_grant_id
         returning * into after_grant;
        before_value := jsonb_build_object(
            'grant_id', before_grant.id,
            'plan', before_grant.plan,
            'status', before_grant.status
        );
        after_value := jsonb_build_object(
            'grant_id', after_grant.id,
            'plan', after_grant.plan,
            'status', after_grant.status
        );
    end if;

    insert into public.admin_audit_logs (
        admin_user_id, target_user_id, action, reason, before_value,
        after_value, operation_id, phase, assurance
    ) values (
        p_admin_id, p_target_user_id, audit_action, normalized_reason,
        before_value, after_value, p_operation_id, 'completed', coalesce(p_assurance, '{}'::jsonb)
    );

    return jsonb_build_object(
        'action', normalized_action,
        'targetUserId', p_target_user_id,
        'accountStatus', coalesce(after_profile.account_status, before_profile.account_status),
        'grantId', after_grant.id
    );
end;
$$;

alter table public.admin_operation_idempotency
    drop constraint if exists admin_operation_idempotency_operation_check;
alter table public.admin_operation_idempotency
    add constraint admin_operation_idempotency_operation_check
    check (operation in (
        'promo_code_issue',
        'support_update',
        'moderation_action',
        'promo_code_revoke',
        'user_manage'
    ));

create or replace function public.admin_get_user(p_user_id uuid)
returns table (
    user_id uuid,
    email text,
    email_confirmed_at timestamptz,
    display_name text,
    auth_provider text,
    created_at timestamptz,
    last_sign_in_at timestamptz,
    last_seen_at timestamptz,
    account_status text,
    admin_note text,
    support_ticket_id text,
    entitlement_grants jsonb
)
language sql
security definer
stable
set search_path = pg_catalog, public, auth, pg_temp
as $$
    select
        auth_user.id,
        auth_user.email::text,
        auth_user.email_confirmed_at,
        profile.display_name,
        profile.auth_provider,
        auth_user.created_at,
        auth_user.last_sign_in_at,
        profile.last_seen_at,
        coalesce(profile.account_status, 'active'),
        profile.admin_note,
        profile.support_ticket_id,
        coalesce((
            select jsonb_agg(
                jsonb_build_object(
                    'id', entitlement.id,
                    'plan', entitlement.plan,
                    'source', entitlement.source,
                    'storePlatform', entitlement.store_platform,
                    'startsAt', entitlement.starts_at,
                    'expiresAt', entitlement.expires_at,
                    'status', entitlement.status,
                    'createdAt', entitlement.created_at
                )
                order by entitlement.created_at desc
            )
            from public.user_entitlement_grants entitlement
            where entitlement.user_id = auth_user.id
        ), '[]'::jsonb)
    from auth.users auth_user
    left join public.user_profiles profile on profile.user_id = auth_user.id
    where auth_user.id = p_user_id
    limit 1;
$$;

revoke all on function public.admin_list_users(text, text, integer, integer) from public, anon, authenticated;
revoke all on function public.admin_get_user(uuid) from public, anon, authenticated;
revoke all on function public.admin_manage_user_audited(uuid, uuid, uuid, text, text, uuid, jsonb, text, text, text, timestamptz, uuid) from public, anon, authenticated;
grant execute on function public.admin_list_users(text, text, integer, integer) to service_role;
grant execute on function public.admin_get_user(uuid) to service_role;
grant execute on function public.admin_manage_user_audited(uuid, uuid, uuid, text, text, uuid, jsonb, text, text, text, timestamptz, uuid) to service_role;

commit;
