create table if not exists public.shared_tag_groups (
    id uuid primary key,
    name text not null check (char_length(btrim(name)) between 1 and 50),
    created_by uuid not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz null
);

create table if not exists public.shared_tag_group_members (
    group_id uuid not null references public.shared_tag_groups(id) on delete cascade,
    user_id uuid not null,
    role text not null check (role in ('owner', 'editor', 'viewer')),
    status text not null check (status in ('active', 'removed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (group_id, user_id)
);

create table if not exists public.shared_tag_group_tags (
    group_id uuid not null references public.shared_tag_groups(id) on delete cascade,
    tag_id uuid not null references public.shared_tags(id) on delete cascade,
    added_by uuid not null,
    created_at timestamptz not null default now(),
    primary key (group_id, tag_id)
);

create table if not exists public.shared_tag_group_invites (
    token_hash text primary key,
    group_id uuid not null references public.shared_tag_groups(id) on delete cascade,
    role text not null check (role in ('editor', 'viewer')),
    created_by uuid not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    claimed_by uuid null,
    claimed_at timestamptz null,
    revoked_at timestamptz null
);

create index if not exists idx_shared_tag_groups_visible
    on public.shared_tag_groups (updated_at desc)
    where deleted_at is null;

create index if not exists idx_shared_tag_group_members_user
    on public.shared_tag_group_members (user_id, status);

create index if not exists idx_shared_tag_group_tags_tag
    on public.shared_tag_group_tags (tag_id);

create index if not exists idx_shared_tag_group_invites_group
    on public.shared_tag_group_invites (group_id, created_at desc);

create index if not exists idx_shared_tag_group_invites_active
    on public.shared_tag_group_invites (expires_at)
    where revoked_at is null and claimed_at is null;

drop trigger if exists trg_shared_tag_groups_updated_at on public.shared_tag_groups;
create trigger trg_shared_tag_groups_updated_at
before update on public.shared_tag_groups
for each row
execute function private.set_updated_at();

drop trigger if exists trg_shared_tag_group_members_updated_at on public.shared_tag_group_members;
create trigger trg_shared_tag_group_members_updated_at
before update on public.shared_tag_group_members
for each row
execute function private.set_updated_at();

create or replace function private.shared_role_rank(p_role text)
returns integer
language sql
immutable
as $$
    select case p_role
        when 'owner' then 3
        when 'editor' then 2
        when 'viewer' then 1
        else 0
    end
$$;

create or replace function private.shared_role_from_rank(p_rank integer)
returns text
language sql
immutable
as $$
    select case
        when p_rank >= 3 then 'owner'
        when p_rank = 2 then 'editor'
        else 'viewer'
    end
$$;

create or replace function private.require_group_role(
    p_group_id uuid,
    p_user_id uuid,
    p_roles text[]
)
returns void
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
begin
    if p_user_id is null then
        raise exception 'auth_required';
    end if;

    if not exists (
        select 1
        from public.shared_tag_group_members member
        join public.shared_tag_groups grp
          on grp.id = member.group_id
         and grp.deleted_at is null
        where member.group_id = p_group_id
          and member.user_id = p_user_id
          and member.status = 'active'
          and member.role = any (p_roles)
    ) then
        raise exception 'forbidden';
    end if;
end;
$$;

create or replace function private.require_direct_tag_owner(
    p_tag_id uuid,
    p_user_id uuid
)
returns void
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
begin
    if p_user_id is null then
        raise exception 'auth_required';
    end if;

    if not exists (
        select 1
        from public.shared_tag_members member
        join public.shared_tags tag
          on tag.id = member.tag_id
         and tag.deleted_at is null
        where member.tag_id = p_tag_id
          and member.user_id = p_user_id
          and member.status = 'active'
          and member.role = 'owner'
    ) then
        raise exception 'forbidden';
    end if;
end;
$$;

create or replace function private.require_tag_role(
    p_tag_id uuid,
    p_user_id uuid,
    p_roles text[]
)
returns void
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
begin
    if p_user_id is null then
        raise exception 'auth_required';
    end if;

    if exists (
        select 1
        from public.shared_tag_members member
        where member.tag_id = p_tag_id
          and member.user_id = p_user_id
          and member.status = 'active'
          and member.role = any (p_roles)
    ) then
        return;
    end if;

    if exists (
        select 1
        from public.shared_tag_group_tags group_tag
        join public.shared_tag_group_members group_member
          on group_member.group_id = group_tag.group_id
         and group_member.user_id = p_user_id
         and group_member.status = 'active'
        join public.shared_tag_groups grp
          on grp.id = group_tag.group_id
         and grp.deleted_at is null
        where group_tag.tag_id = p_tag_id
          and group_member.role = any (p_roles)
    ) then
        return;
    end if;

    raise exception 'forbidden';
end;
$$;

create or replace function public.create_shared_tag_group(p_name text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    group_id uuid := gen_random_uuid();
    group_name text := btrim(p_name);
begin
    if caller is null then
        raise exception 'auth_required';
    end if;
    if group_name is null or group_name = '' or char_length(group_name) > 50 then
        raise exception 'invalid_group_name';
    end if;

    insert into public.shared_tag_groups (id, name, created_by)
    values (group_id, group_name, caller);

    insert into public.shared_tag_group_members (group_id, user_id, role, status)
    values (group_id, caller, 'owner', 'active');

    return jsonb_build_object('group_id', group_id, 'group_name', group_name, 'role', 'owner');
end;
$$;

create or replace function public.rename_shared_tag_group(
    p_group_id uuid,
    p_name text
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    group_name text := btrim(p_name);
begin
    perform private.require_group_role(p_group_id, caller, array['owner']);
    if group_name is null or group_name = '' or char_length(group_name) > 50 then
        raise exception 'invalid_group_name';
    end if;

    update public.shared_tag_groups
    set name = group_name
    where id = p_group_id
      and deleted_at is null;

    return jsonb_build_object('group_id', p_group_id, 'group_name', group_name);
end;
$$;

create or replace function public.delete_shared_tag_group(p_group_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
begin
    perform private.require_group_role(p_group_id, caller, array['owner']);

    update public.shared_tag_groups
    set deleted_at = now()
    where id = p_group_id
      and deleted_at is null;

    update public.shared_tag_group_members
    set status = 'removed'
    where group_id = p_group_id;

    update public.shared_tag_group_invites
    set revoked_at = now()
    where group_id = p_group_id
      and revoked_at is null;

    return jsonb_build_object('group_id', p_group_id, 'status', 'deleted');
end;
$$;

create or replace function public.add_shared_tag_to_group(
    p_group_id uuid,
    p_tag_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
begin
    perform private.require_direct_tag_owner(p_tag_id, caller);

    if not exists (
        select 1 from public.shared_tag_groups grp
        where grp.id = p_group_id and grp.deleted_at is null
    ) then
        raise exception 'group_not_found';
    end if;

    insert into public.shared_tag_group_tags (group_id, tag_id, added_by)
    values (p_group_id, p_tag_id, caller)
    on conflict (group_id, tag_id) do nothing;

    insert into public.shared_tag_group_members (group_id, user_id, role, status)
    select
        p_group_id,
        member.user_id,
        case
            when member.role in ('owner', 'editor') then 'editor'
            else 'viewer'
        end,
        'active'
    from public.shared_tag_members member
    where member.tag_id = p_tag_id
      and member.status = 'active'
      and member.role <> 'owner'
    on conflict (group_id, user_id) do update
    set role = private.shared_role_from_rank(greatest(
            private.shared_role_rank(public.shared_tag_group_members.role),
            private.shared_role_rank(excluded.role)
        )),
        status = 'active';

    update public.shared_tag_members
    set status = 'removed'
    where tag_id = p_tag_id
      and status = 'active'
      and role <> 'owner';

    return jsonb_build_object('group_id', p_group_id, 'tag_id', p_tag_id, 'status', 'added');
end;
$$;

create or replace function public.remove_shared_tag_from_group(
    p_group_id uuid,
    p_tag_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
begin
    if not exists (
        select 1
        from public.shared_tag_members member
        where member.tag_id = p_tag_id
          and member.user_id = caller
          and member.status = 'active'
          and member.role = 'owner'
    ) then
        perform private.require_group_role(p_group_id, caller, array['owner']);
    end if;

    delete from public.shared_tag_group_tags
    where group_id = p_group_id
      and tag_id = p_tag_id;

    return jsonb_build_object('group_id', p_group_id, 'tag_id', p_tag_id, 'status', 'removed');
end;
$$;

create or replace function public.create_shared_tag_group_invite(
    p_group_id uuid,
    p_role text default 'editor'
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    invite_token text;
    invite_hash text;
    expires_at timestamptz := now() + interval '7 days';
begin
    perform private.require_group_role(p_group_id, caller, array['owner']);

    if p_role not in ('editor', 'viewer') then
        raise exception 'invalid_invite_role';
    end if;

    invite_token := encode(extensions.gen_random_bytes(24), 'hex');
    invite_hash := private.hash_shared_tag_invite_token(invite_token);

    insert into public.shared_tag_group_invites (
        token_hash,
        group_id,
        role,
        created_by,
        expires_at
    )
    values (
        invite_hash,
        p_group_id,
        p_role,
        caller,
        expires_at
    );

    return jsonb_build_object(
        'invite_type', 'group',
        'group_id', p_group_id,
        'invite_token', invite_token,
        'expires_at', expires_at,
        'role', p_role
    );
end;
$$;

create or replace function public.preview_shared_invite(p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    v_token_hash text := private.hash_shared_tag_invite_token(p_token);
    tag_preview jsonb;
    group_preview jsonb;
begin
    select jsonb_build_object(
        'invite_type', 'tag',
        'tag_id', tag.id,
        'tag_name', tag.name,
        'role', invite.role
    )
    into tag_preview
    from public.shared_tag_invites invite
    join public.shared_tags tag
      on tag.id = invite.tag_id
    where invite.token_hash = v_token_hash
      and invite.revoked_at is null
      and invite.expires_at > now()
      and tag.deleted_at is null
    limit 1;

    if tag_preview is not null then
        return tag_preview;
    end if;

    select jsonb_build_object(
        'invite_type', 'group',
        'group_id', grp.id,
        'group_name', grp.name,
        'role', invite.role
    )
    into group_preview
    from public.shared_tag_group_invites invite
    join public.shared_tag_groups grp
      on grp.id = invite.group_id
    where invite.token_hash = v_token_hash
      and invite.revoked_at is null
      and invite.expires_at > now()
      and grp.deleted_at is null
    limit 1;

    if group_preview is not null then
        return group_preview;
    end if;

    raise exception 'invalid_or_expired_invite';
end;
$$;

create or replace function public.accept_shared_invite(p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    v_token_hash text := private.hash_shared_tag_invite_token(p_token);
    tag_invite public.shared_tag_invites%rowtype;
    group_invite public.shared_tag_group_invites%rowtype;
    tag_name text;
    group_name text;
    applied_role text;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    select invite.*
    into tag_invite
    from public.shared_tag_invites invite
    join public.shared_tags tag
      on tag.id = invite.tag_id
    where invite.token_hash = v_token_hash
      and invite.revoked_at is null
      and invite.expires_at > now()
      and (invite.claimed_by is null or invite.claimed_by = caller)
      and tag.deleted_at is null
    limit 1;

    if tag_invite.token_hash is not null then
        insert into public.shared_tag_members (tag_id, user_id, role, status)
        values (tag_invite.tag_id, caller, tag_invite.role, 'active')
        on conflict (tag_id, user_id) do update
        set role = case
                when public.shared_tag_members.role = 'owner' then public.shared_tag_members.role
                else excluded.role
            end,
            status = 'active';

        update public.shared_tag_invites
        set claimed_by = caller,
            claimed_at = now()
        where token_hash = tag_invite.token_hash
          and claimed_by is null;

        select tag.name into tag_name
        from public.shared_tags tag
        where tag.id = tag_invite.tag_id;

        select member.role into applied_role
        from public.shared_tag_members member
        where member.tag_id = tag_invite.tag_id
          and member.user_id = caller;

        return jsonb_build_object(
            'invite_type', 'tag',
            'tag_id', tag_invite.tag_id,
            'tag_name', tag_name,
            'role', applied_role,
            'status', 'active'
        );
    end if;

    select invite.*
    into group_invite
    from public.shared_tag_group_invites invite
    join public.shared_tag_groups grp
      on grp.id = invite.group_id
    where invite.token_hash = v_token_hash
      and invite.revoked_at is null
      and invite.expires_at > now()
      and (invite.claimed_by is null or invite.claimed_by = caller)
      and grp.deleted_at is null
    limit 1;

    if group_invite.token_hash is null then
        raise exception 'invalid_or_expired_invite';
    end if;

    insert into public.shared_tag_group_members (group_id, user_id, role, status)
    values (group_invite.group_id, caller, group_invite.role, 'active')
    on conflict (group_id, user_id) do update
    set role = case
            when public.shared_tag_group_members.role = 'owner' then public.shared_tag_group_members.role
            else excluded.role
        end,
        status = 'active';

    update public.shared_tag_group_invites
    set claimed_by = caller,
        claimed_at = now()
    where token_hash = group_invite.token_hash
      and claimed_by is null;

    select grp.name into group_name
    from public.shared_tag_groups grp
    where grp.id = group_invite.group_id;

    select member.role into applied_role
    from public.shared_tag_group_members member
    where member.group_id = group_invite.group_id
      and member.user_id = caller;

    return jsonb_build_object(
        'invite_type', 'group',
        'group_id', group_invite.group_id,
        'group_name', group_name,
        'role', applied_role,
        'status', 'active'
    );
end;
$$;

create or replace function public.accept_shared_tag_invite(p_token text)
returns jsonb
language sql
security definer
set search_path = public, private, pg_temp
as $$
    select public.accept_shared_invite(p_token) - 'invite_type'
$$;

create or replace function public.preview_shared_tag_invite(p_token text)
returns jsonb
language sql
security definer
set search_path = public, private, pg_temp
as $$
    select jsonb_build_object('tag_name', public.preview_shared_invite(p_token) ->> 'tag_name')
$$;

create or replace function public.change_shared_tag_group_member_role(
    p_group_id uuid,
    p_user_id uuid,
    p_role text
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
begin
    perform private.require_group_role(p_group_id, caller, array['owner']);
    if p_role not in ('owner', 'editor', 'viewer') then
        raise exception 'invalid_group_role';
    end if;
    if p_user_id = caller and p_role <> 'owner' and not exists (
        select 1 from public.shared_tag_group_members member
        where member.group_id = p_group_id
          and member.user_id <> caller
          and member.status = 'active'
          and member.role = 'owner'
    ) then
        raise exception 'group_owner_transfer_required';
    end if;

    update public.shared_tag_group_members
    set role = p_role,
        status = 'active'
    where group_id = p_group_id
      and user_id = p_user_id;

    return jsonb_build_object('group_id', p_group_id, 'user_id', p_user_id, 'role', p_role);
end;
$$;

create or replace function public.transfer_shared_tag_group_ownership(
    p_group_id uuid,
    p_new_owner_user_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
begin
    perform private.require_group_role(p_group_id, caller, array['owner']);

    if p_new_owner_user_id = caller then
        raise exception 'invalid_group_owner_target';
    end if;

    if not exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = p_group_id
          and member.user_id = p_new_owner_user_id
          and member.status = 'active'
    ) then
        raise exception 'invalid_group_owner_target';
    end if;

    update public.shared_tag_group_members
    set role = 'owner'
    where group_id = p_group_id
      and user_id = p_new_owner_user_id;

    update public.shared_tag_group_members
    set role = 'editor'
    where group_id = p_group_id
      and user_id = caller;

    return jsonb_build_object(
        'group_id', p_group_id,
        'previous_owner_user_id', caller,
        'new_owner_user_id', p_new_owner_user_id
    );
end;
$$;

create or replace function public.remove_shared_tag_group_member(
    p_group_id uuid,
    p_user_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
begin
    if p_user_id = caller then
        perform private.require_group_role(p_group_id, caller, array['editor', 'viewer']);
    else
        perform private.require_group_role(p_group_id, caller, array['owner']);
    end if;

    if exists (
        select 1
        from public.shared_tag_group_tags group_tag
        join public.shared_tag_members tag_member
          on tag_member.tag_id = group_tag.tag_id
         and tag_member.user_id = p_user_id
         and tag_member.status = 'active'
         and tag_member.role = 'owner'
        where group_tag.group_id = p_group_id
    ) then
        raise exception 'group_member_owns_group_tag';
    end if;

    if exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = p_group_id
          and member.user_id = p_user_id
          and member.role = 'owner'
          and member.status = 'active'
    ) and not exists (
        select 1
        from public.shared_tag_group_members other_member
        where other_member.group_id = p_group_id
          and other_member.user_id <> p_user_id
          and other_member.role = 'owner'
          and other_member.status = 'active'
    ) then
        raise exception 'group_owner_transfer_required';
    end if;

    update public.shared_tag_group_members
    set status = 'removed'
    where group_id = p_group_id
      and user_id = p_user_id;

    return jsonb_build_object('group_id', p_group_id, 'user_id', p_user_id, 'status', 'removed');
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
                        'role', member.role,
                        'status', member.status,
                        'created_at', member.created_at,
                        'updated_at', member.updated_at
                    )
                    order by member.tag_id, member.user_id
                )
                from effective_members member
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
                        'role', member.role,
                        'status', member.status,
                        'created_at', member.created_at,
                        'updated_at', member.updated_at
                    )
                    order by member.group_id, member.user_id
                )
                from public.shared_tag_group_members member
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

alter table public.shared_tag_groups enable row level security;
alter table public.shared_tag_group_members enable row level security;
alter table public.shared_tag_group_tags enable row level security;
alter table public.shared_tag_group_invites enable row level security;

drop policy if exists shared_tag_groups_select_active_member on public.shared_tag_groups;
create policy shared_tag_groups_select_active_member
on public.shared_tag_groups
for select
to authenticated
using (
    exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = shared_tag_groups.id
          and member.user_id = auth.uid()
          and member.status = 'active'
    )
);

drop policy if exists shared_tag_group_members_select_group_member on public.shared_tag_group_members;
create policy shared_tag_group_members_select_group_member
on public.shared_tag_group_members
for select
to authenticated
using (
    exists (
        select 1
        from public.shared_tag_group_members access_member
        where access_member.group_id = shared_tag_group_members.group_id
          and access_member.user_id = auth.uid()
          and access_member.status = 'active'
    )
);

drop policy if exists shared_tag_group_tags_select_group_member on public.shared_tag_group_tags;
create policy shared_tag_group_tags_select_group_member
on public.shared_tag_group_tags
for select
to authenticated
using (
    exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = shared_tag_group_tags.group_id
          and member.user_id = auth.uid()
          and member.status = 'active'
    )
);

create or replace function public.delete_my_account()
returns jsonb
language plpgsql
security definer
set search_path = public, auth, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    if exists (
        select 1
        from public.shared_tag_members owner_member
        join public.shared_tags tag
          on tag.id = owner_member.tag_id
         and tag.deleted_at is null
        where owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
          and exists (
              select 1
              from public.shared_tag_members other_member
              where other_member.tag_id = owner_member.tag_id
                and other_member.user_id <> caller
                and other_member.status = 'active'
          )
    ) or exists (
        select 1
        from public.shared_tag_group_members owner_member
        join public.shared_tag_groups grp
          on grp.id = owner_member.group_id
         and grp.deleted_at is null
        where owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
          and exists (
              select 1
              from public.shared_tag_group_members other_member
              where other_member.group_id = owner_member.group_id
                and other_member.user_id <> caller
                and other_member.status = 'active'
          )
    ) then
        raise exception 'owner_transfer_required';
    end if;

    update public.shared_tag_groups grp
    set deleted_at = now()
    where exists (
        select 1
        from public.shared_tag_group_members owner_member
        where owner_member.group_id = grp.id
          and owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
    )
      and not exists (
          select 1
          from public.shared_tag_group_members other_member
          where other_member.group_id = grp.id
            and other_member.user_id <> caller
            and other_member.status = 'active'
      );

    update public.shared_tag_group_invites invite
    set revoked_at = now()
    where invite.created_by = caller
      and invite.revoked_at is null;

    delete from public.shared_tags tag
    where exists (
        select 1
        from public.shared_tag_members owner_member
        where owner_member.tag_id = tag.id
          and owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
    )
      and not exists (
          select 1
          from public.shared_tag_members other_member
          where other_member.tag_id = tag.id
            and other_member.user_id <> caller
            and other_member.status = 'active'
      );

    update public.shared_tag_invites
    set revoked_at = now()
    where created_by = caller
      and revoked_at is null;

    delete from public.shared_tag_group_members
    where user_id = caller;

    delete from public.shared_tag_members
    where user_id = caller;

    delete from public.applied_client_ops
    where user_id = caller;

    delete from auth.users
    where id = caller;

    return jsonb_build_object(
        'status', 'deleted',
        'user_id', caller
    );
end;
$$;

grant execute on function public.create_shared_tag_group(text) to authenticated;
grant execute on function public.rename_shared_tag_group(uuid, text) to authenticated;
grant execute on function public.delete_shared_tag_group(uuid) to authenticated;
grant execute on function public.add_shared_tag_to_group(uuid, uuid) to authenticated;
grant execute on function public.remove_shared_tag_from_group(uuid, uuid) to authenticated;
grant execute on function public.create_shared_tag_group_invite(uuid, text) to authenticated;
grant execute on function public.preview_shared_invite(text) to anon, authenticated;
grant execute on function public.accept_shared_invite(text) to authenticated;
grant execute on function public.change_shared_tag_group_member_role(uuid, uuid, text) to authenticated;
grant execute on function public.transfer_shared_tag_group_ownership(uuid, uuid) to authenticated;
grant execute on function public.remove_shared_tag_group_member(uuid, uuid) to authenticated;
grant select on public.shared_tag_groups, public.shared_tag_group_members, public.shared_tag_group_tags, public.shared_tag_group_invites to authenticated;
