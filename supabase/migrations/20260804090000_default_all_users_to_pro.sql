begin;

-- 当面は付与履歴の有無や下位プランにかかわらず、全ユーザーをPro以上として扱う。
create or replace function private.active_entitlement_plan_for_user(p_user_id uuid)
returns text
language sql
stable
security definer
set search_path = public, private, pg_temp
as $$
    select coalesce(
        (
            select grant_row.plan
            from public.user_entitlement_grants grant_row
            where grant_row.user_id = p_user_id
              and grant_row.status = 'active'
              and grant_row.starts_at <= now()
              and (grant_row.expires_at is null or grant_row.expires_at > now())
              and grant_row.plan in ('promo_pro', 'pro')
            order by
                case grant_row.plan
                    when 'promo_pro' then 0
                    when 'pro' then 1
                    else 2
                end,
                grant_row.starts_at desc
            limit 1
        ),
        'pro'
    )
$$;

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
    with enriched_users as (
        select
            auth_user.id as user_id,
            auth_user.email::text as email,
            left(coalesce(
                nullif(btrim(profile.display_name), ''),
                nullif(btrim(shared_profile.display_name), ''),
                nullif(btrim(auth_user.raw_user_meta_data ->> 'full_name'), ''),
                nullif(btrim(auth_user.raw_user_meta_data ->> 'name'), '')
            ), 120) as display_name,
            auth_user.created_at,
            auth_user.last_sign_in_at,
            profile.last_seen_at,
            coalesce(profile.account_status, 'active') as account_status
        from auth.users auth_user
        left join public.user_profiles profile on profile.user_id = auth_user.id
        left join public.shared_user_profiles shared_profile on shared_profile.user_id = auth_user.id
    ),
    matching_users as (
        select enriched.*
        from enriched_users enriched
        where (
            normalized_search = ''
            or position(normalized_search in lower(coalesce(enriched.email, ''))) > 0
            or position(normalized_search in lower(coalesce(enriched.display_name, ''))) > 0
            or lower(enriched.user_id::text) = normalized_search
        )
        and (
            normalized_status is null
            or enriched.account_status = normalized_status
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
        coalesce(active_grant.plan, 'pro') as current_plan,
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
          and entitlement.plan in ('promo_pro', 'pro')
        order by
            case entitlement.plan
                when 'promo_pro' then 0
                when 'pro' then 1
                else 2
            end,
            entitlement.starts_at desc
        limit 1
    ) active_grant on true
    order by counted.created_at desc, counted.user_id;
end;
$$;

revoke all on function private.active_entitlement_plan_for_user(uuid) from public;
grant execute on function private.active_entitlement_plan_for_user(uuid) to service_role;
revoke all on function public.admin_list_users(text, text, integer, integer) from public, anon, authenticated;
grant execute on function public.admin_list_users(text, text, integer, integer) to service_role;

commit;
