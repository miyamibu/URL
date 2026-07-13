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
            order by
                case grant_row.plan
                    when 'promo_pro' then 0
                    when 'pro' then 1
                    when 'launch_standard' then 2
                    when 'free' then 3
                    else 4
                end,
                grant_row.starts_at desc
            limit 1
        ),
        'launch_standard'
    )
$$;

create or replace function private.is_pro_entitlement_plan(p_plan text)
returns boolean
language sql
immutable
as $$
    select p_plan in ('pro', 'promo_pro')
$$;

create or replace function private.shared_tag_group_limit_for_user(p_user_id uuid)
returns integer
language sql
stable
security definer
set search_path = public, private, pg_temp
as $$
    select case
        when private.is_pro_entitlement_plan(private.active_entitlement_plan_for_user(p_user_id)) then 50
        else 2
    end
$$;

create or replace function private.shared_tag_url_limit_for_tag(p_tag_id uuid)
returns integer
language sql
stable
security definer
set search_path = public, private, pg_temp
as $$
    select case
        when private.is_pro_entitlement_plan(private.active_entitlement_plan_for_user(coalesce(owner_member.user_id, tag.created_by))) then 100
        else 50
    end
    from public.shared_tags tag
    left join lateral (
        select member.user_id
        from public.shared_tag_members member
        where member.tag_id = tag.id
          and member.status = 'active'
          and member.role = 'owner'
        order by member.created_at asc
        limit 1
    ) owner_member on true
    where tag.id = p_tag_id
$$;

create or replace function private.shared_tag_group_member_limit_for_group(p_group_id uuid)
returns integer
language sql
stable
security definer
set search_path = public, private, pg_temp
as $$
    select case
        when private.is_pro_entitlement_plan(private.active_entitlement_plan_for_user(coalesce(owner_member.user_id, grp.created_by))) then 50
        else 10
    end
    from public.shared_tag_groups grp
    left join lateral (
        select member.user_id
        from public.shared_tag_group_members member
        where member.group_id = grp.id
          and member.status = 'active'
          and member.role = 'owner'
        order by member.created_at asc
        limit 1
    ) owner_member on true
    where grp.id = p_group_id
$$;

create or replace function private.enforce_shared_tag_group_limit()
returns trigger
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    v_limit integer;
    v_count integer;
begin
    if new.deleted_at is not null then
        return new;
    end if;

    if tg_op = 'UPDATE' and old.deleted_at is null then
        return new;
    end if;

    v_limit := private.shared_tag_group_limit_for_user(new.created_by);

    select count(*)
      into v_count
      from public.shared_tag_groups grp
     where grp.created_by = new.created_by
       and grp.deleted_at is null
       and grp.id <> new.id;

    if v_count >= v_limit then
        raise exception 'shared_tag_group_limit_reached';
    end if;

    return new;
end;
$$;

create or replace function private.enforce_shared_tag_group_member_limit()
returns trigger
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    v_limit integer;
    v_count integer;
begin
    if new.status <> 'active' then
        return new;
    end if;

    if tg_op = 'UPDATE' and old.status = 'active' then
        return new;
    end if;

    v_limit := private.shared_tag_group_member_limit_for_group(new.group_id);

    select count(*)
      into v_count
      from public.shared_tag_group_members member
     where member.group_id = new.group_id
       and member.status = 'active'
       and member.user_id <> new.user_id;

    if v_count >= v_limit then
        raise exception 'shared_tag_group_member_limit_reached';
    end if;

    return new;
end;
$$;

create or replace function private.enforce_shared_tag_url_limit()
returns trigger
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    v_limit integer;
    v_count integer;
begin
    if new.deleted_at is not null then
        return new;
    end if;

    if tg_op = 'UPDATE' and old.deleted_at is null then
        return new;
    end if;

    v_limit := private.shared_tag_url_limit_for_tag(new.tag_id);

    select count(*)
      into v_count
      from public.shared_tag_urls url
     where url.tag_id = new.tag_id
       and url.deleted_at is null
       and url.id <> new.id;

    if v_count >= v_limit then
        raise exception 'shared_tag_url_limit_reached';
    end if;

    return new;
end;
$$;

drop trigger if exists trg_shared_tag_groups_plan_limit on public.shared_tag_groups;
create trigger trg_shared_tag_groups_plan_limit
before insert or update of deleted_at on public.shared_tag_groups
for each row
execute function private.enforce_shared_tag_group_limit();

drop trigger if exists trg_shared_tag_group_members_plan_limit on public.shared_tag_group_members;
create trigger trg_shared_tag_group_members_plan_limit
before insert or update of status on public.shared_tag_group_members
for each row
execute function private.enforce_shared_tag_group_member_limit();

drop trigger if exists trg_shared_tag_urls_plan_limit on public.shared_tag_urls;
create trigger trg_shared_tag_urls_plan_limit
before insert or update of deleted_at on public.shared_tag_urls
for each row
execute function private.enforce_shared_tag_url_limit();
