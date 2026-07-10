create table if not exists public.shared_user_profiles (
    user_id uuid primary key,
    display_name text not null check (char_length(display_name) <= 40),
    updated_at timestamptz not null default now()
);

drop trigger if exists trg_shared_user_profiles_updated_at on public.shared_user_profiles;
create trigger trg_shared_user_profiles_updated_at
before update on public.shared_user_profiles
for each row
execute function private.set_updated_at();

create or replace function public.upsert_my_shared_profile(p_display_name text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    profile_name text := left(btrim(coalesce(p_display_name, '')), 40);
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    insert into public.shared_user_profiles (user_id, display_name)
    values (caller, profile_name)
    on conflict (user_id) do update
    set display_name = excluded.display_name;

    return jsonb_build_object('user_id', caller, 'display_name', profile_name);
end;
$$;

create or replace function public.pull_shared_tag_snapshot()
returns jsonb
language sql
security definer
set search_path = public, private, pg_temp
as $$
    with active_direct_memberships as (
        select
            member.tag_id,
            member.user_id,
            member.role,
            member.status,
            member.created_at,
            member.updated_at,
            private.shared_role_rank(member.role) as role_rank
        from public.shared_tag_members member
        where member.user_id = auth.uid()
          and member.status = 'active'
    ),
    active_groups as (
        select grp.*
        from public.shared_tag_groups grp
        join public.shared_tag_group_members member
          on member.group_id = grp.id
         and member.user_id = auth.uid()
         and member.status = 'active'
        where grp.deleted_at is null
    ),
    active_group_memberships as (
        select
            group_tag.tag_id,
            group_member.user_id,
            group_member.role,
            group_member.status,
            group_member.created_at,
            group_member.updated_at,
            private.shared_role_rank(group_member.role) as role_rank
        from public.shared_tag_group_tags group_tag
        join active_groups grp
          on grp.id = group_tag.group_id
        join public.shared_tag_group_members group_member
          on group_member.group_id = group_tag.group_id
         and group_member.status = 'active'
    ),
    visible_tag_ids as (
        select tag_id from active_direct_memberships
        union
        select tag_id
        from public.shared_tag_group_tags group_tag
        join active_groups grp
          on grp.id = group_tag.group_id
    ),
    visible_tags as (
        select tag.*
        from public.shared_tags tag
        where tag.id in (select tag_id from visible_tag_ids)
          and tag.deleted_at is null
    ),
    effective_members as (
        select
            memberships.tag_id,
            memberships.user_id,
            private.shared_role_from_rank(max(memberships.role_rank)) as role,
            'active'::text as status,
            min(memberships.created_at) as created_at,
            max(memberships.updated_at) as updated_at
        from (
            select * from active_direct_memberships
            union all
            select * from active_group_memberships
        ) memberships
        group by memberships.tag_id, memberships.user_id
    )
    select jsonb_build_object(
        'pulled_at', now(),
        'normalization_version', 1,
        'tags',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'id', tag.id,
                        'name', tag.name,
                        'created_by', tag.created_by,
                        'created_at', tag.created_at,
                        'updated_at', tag.updated_at,
                        'deleted_at', tag.deleted_at,
                        'version', tag.version
                    )
                    order by tag.updated_at desc, tag.id
                )
                from visible_tags tag
            ),
            '[]'::jsonb
        ),
        'members',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'tag_id', member.tag_id,
                        'user_id', member.user_id,
                        'display_name', profile.display_name,
                        'role', member.role,
                        'status', member.status,
                        'created_at', member.created_at,
                        'updated_at', member.updated_at
                    )
                    order by member.tag_id, member.user_id
                )
                from effective_members member
                left join public.shared_user_profiles profile
                  on profile.user_id = member.user_id
            ),
            '[]'::jsonb
        ),
        'urls',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'id', url.id,
                        'tag_id', url.tag_id,
                        'raw_url', url.raw_url,
                        'normalized_url', url.normalized_url,
                        'normalization_version', url.normalization_version,
                        'added_by', url.added_by,
                        'created_at', url.created_at,
                        'updated_at', url.updated_at,
                        'deleted_at', url.deleted_at
                    )
                    order by url.updated_at desc, url.id
                )
                from public.shared_tag_urls url
                where url.tag_id in (select id from visible_tags)
                  and url.deleted_at is null
            ),
            '[]'::jsonb
        ),
        'groups',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'id', grp.id,
                        'name', grp.name,
                        'created_by', grp.created_by,
                        'created_at', grp.created_at,
                        'updated_at', grp.updated_at,
                        'deleted_at', grp.deleted_at
                    )
                    order by grp.updated_at desc, grp.id
                )
                from active_groups grp
            ),
            '[]'::jsonb
        ),
        'group_members',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'group_id', member.group_id,
                        'user_id', member.user_id,
                        'display_name', profile.display_name,
                        'role', member.role,
                        'status', member.status,
                        'created_at', member.created_at,
                        'updated_at', member.updated_at
                    )
                    order by member.group_id, member.user_id
                )
                from public.shared_tag_group_members member
                left join public.shared_user_profiles profile
                  on profile.user_id = member.user_id
                where member.group_id in (select id from active_groups)
            ),
            '[]'::jsonb
        ),
        'group_tags',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'group_id', group_tag.group_id,
                        'tag_id', group_tag.tag_id,
                        'added_by', group_tag.added_by,
                        'created_at', group_tag.created_at
                    )
                    order by group_tag.group_id, group_tag.created_at desc
                )
                from public.shared_tag_group_tags group_tag
                where group_tag.group_id in (select id from active_groups)
            ),
            '[]'::jsonb
        )
    );
$$;

alter table public.shared_user_profiles enable row level security;

drop policy if exists shared_user_profiles_select_shared_context on public.shared_user_profiles;
create policy shared_user_profiles_select_shared_context
on public.shared_user_profiles
for select
to authenticated
using (
    user_id = auth.uid()
    or exists (
        select 1
        from public.shared_tag_members mine
        join public.shared_tag_members other_member
          on other_member.tag_id = mine.tag_id
         and other_member.user_id = shared_user_profiles.user_id
        where mine.user_id = auth.uid()
          and mine.status = 'active'
          and other_member.status = 'active'
    )
    or exists (
        select 1
        from public.shared_tag_group_members mine
        join public.shared_tag_group_members other_member
          on other_member.group_id = mine.group_id
         and other_member.user_id = shared_user_profiles.user_id
        where mine.user_id = auth.uid()
          and mine.status = 'active'
          and other_member.status = 'active'
    )
);

drop policy if exists shared_user_profiles_upsert_self on public.shared_user_profiles;
create policy shared_user_profiles_upsert_self
on public.shared_user_profiles
for all
to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

grant execute on function public.upsert_my_shared_profile(text) to authenticated;
grant select, insert, update on public.shared_user_profiles to authenticated;
