-- Final forward definitions for shared-tag group mutations.
-- Keep group administration owner-only, allow group editors to manage group
-- tags, and keep tag-owner self-removal as an explicit, narrow exception.

create or replace function private.require_group_tag_membership(
    p_group_id uuid,
    p_tag_id uuid
)
returns void
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
begin
    if not exists (
        select 1
        from public.shared_tag_group_tags group_tag
        join public.shared_tag_groups grp
          on grp.id = group_tag.group_id
         and grp.deleted_at is null
        where group_tag.group_id = p_group_id
          and group_tag.tag_id = p_tag_id
    ) then
        raise exception 'group_tag_not_found';
    end if;
end;
$$;
revoke all on function private.require_group_tag_membership(uuid, uuid) from public, anon, authenticated;

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

    perform private.require_unrestricted_app_user(caller);

    if group_name is null or group_name = '' or char_length(group_name) > 50 then
        raise exception 'invalid_group_name';
    end if;

    insert into public.shared_tag_groups (id, name, created_by)
    values (group_id, group_name, caller);

    insert into public.shared_tag_group_members (group_id, user_id, role, status)
    values (group_id, caller, 'owner', 'active');

    return jsonb_build_object(
        'group_id', group_id,
        'group_name', group_name,
        'role', 'owner',
        'status', 'active'
    );
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
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);
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
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);
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
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);
    perform private.require_group_role(p_group_id, caller, array['owner', 'editor']);
    perform private.require_direct_tag_owner(p_tag_id, caller);

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
    is_direct_tag_owner boolean;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);
    perform private.require_group_tag_membership(p_group_id, p_tag_id);

    select exists (
        select 1
        from public.shared_tag_members member
        join public.shared_tags tag
          on tag.id = member.tag_id
         and tag.deleted_at is null
        where member.tag_id = p_tag_id
          and member.user_id = caller
          and member.status = 'active'
          and member.role = 'owner'
    )
    into is_direct_tag_owner;

    if not is_direct_tag_owner then
        perform private.require_group_role(p_group_id, caller, array['owner', 'editor']);
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
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);
    perform private.require_group_role(p_group_id, caller, array['owner']);

    if p_role not in ('editor', 'viewer') then
        raise exception 'invalid_group_invite_role';
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

create or replace function public.create_shared_tag_in_group(
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
    v_tag_id uuid := gen_random_uuid();
    tag_name text := btrim(p_name);
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);
    perform private.require_group_role(p_group_id, caller, array['owner', 'editor']);

    if tag_name is null or tag_name = '' or char_length(tag_name) > 50 then
        raise exception 'invalid_tag_name';
    end if;

    insert into public.shared_tags (id, name, created_by, group_id)
    values (v_tag_id, tag_name, caller, p_group_id);

    insert into public.shared_tag_members (tag_id, user_id, role, status)
    select v_tag_id,
           member.user_id,
           case
               when member.user_id = caller then 'owner'
               when member.role in ('owner', 'editor') then 'editor'
               else 'viewer'
           end,
           'active'
    from public.shared_tag_group_members member
    where member.group_id = p_group_id
      and member.status = 'active'
    on conflict (tag_id, user_id) do update
    set role = case
            when public.shared_tag_members.role = 'owner' then public.shared_tag_members.role
            else excluded.role
        end,
        status = 'active'
    where public.shared_tag_members.status <> 'removed';

    return jsonb_build_object(
        'tag_id', v_tag_id,
        'group_id', p_group_id,
        'name', tag_name,
        'status', 'active'
    );
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

    perform private.require_unrestricted_app_user(caller);

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
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);
    return public.accept_shared_invite(p_token) - 'invite_type';
end;
$$;

create or replace function public.accept_shared_tag_group_invite(p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    invite_hash text := private.hash_shared_tag_group_invite_token(p_token);
    invite_record public.shared_tag_group_invites%rowtype;
    inspect_record public.shared_tag_group_invites%rowtype;
    existing_member public.shared_tag_group_members%rowtype;
    group_name text;
    applied_role text;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);

    update public.shared_tag_group_invites invite
    set claimed_by = caller,
        claimed_at = now()
    from public.shared_tag_groups group_record,
         public.shared_tag_group_members creator_member
    where invite.token_hash = invite_hash
      and invite.group_id = group_record.id
      and creator_member.group_id = invite.group_id
      and creator_member.user_id = invite.created_by
      and creator_member.status = 'active'
      and creator_member.role in ('owner', 'editor')
      and invite.revoked_at is null
      and invite.expires_at > now()
      and invite.claimed_by is null
      and group_record.deleted_at is null
      and not exists (
          select 1
          from public.shared_tag_group_members removed_member
          where removed_member.group_id = invite.group_id
            and removed_member.user_id = caller
            and removed_member.status = 'removed'
      )
    returning invite.* into invite_record;

    if invite_record.token_hash is null then
        select invite.*
        into inspect_record
        from public.shared_tag_group_invites invite
        where invite.token_hash = invite_hash
        limit 1;

        if inspect_record.token_hash is null or
           inspect_record.revoked_at is not null or
           inspect_record.expires_at <= now() or
           not exists (
               select 1
               from public.shared_tag_groups group_record
               where group_record.id = inspect_record.group_id
                 and group_record.deleted_at is null
           ) then
            raise exception 'invalid_or_expired_group_invite';
        end if;

        select member.*
        into existing_member
        from public.shared_tag_group_members member
        where member.group_id = inspect_record.group_id
          and member.user_id = caller
        limit 1;

        if existing_member.status = 'removed' then
            raise exception 'member_removed';
        end if;

        if inspect_record.claimed_by is not null and inspect_record.claimed_by <> caller then
            raise exception 'invite_already_claimed';
        end if;

        if not exists (
            select 1
            from public.shared_tag_group_members creator_member
            where creator_member.group_id = inspect_record.group_id
              and creator_member.user_id = inspect_record.created_by
              and creator_member.status = 'active'
              and creator_member.role in ('owner', 'editor')
        ) then
            raise exception 'invalid_or_expired_group_invite';
        end if;

        if inspect_record.claimed_by = caller and existing_member.status = 'active' then
            invite_record := inspect_record;
        else
            raise exception 'invalid_or_expired_group_invite';
        end if;
    end if;

    insert into public.shared_tag_group_members (group_id, user_id, role, status)
    values (invite_record.group_id, caller, invite_record.role, 'active')
    on conflict (group_id, user_id) do update
    set role = case
            when public.shared_tag_group_members.role = 'owner' then public.shared_tag_group_members.role
            else excluded.role
        end,
        status = 'active'
    where public.shared_tag_group_members.status <> 'removed';

    insert into public.shared_tag_members (tag_id, user_id, role, status)
    select tag.id,
           caller,
           invite_record.role,
           'active'
    from public.shared_tags tag
    where tag.group_id = invite_record.group_id
      and tag.deleted_at is null
    on conflict (tag_id, user_id) do update
    set role = case
            when public.shared_tag_members.role = 'owner' then public.shared_tag_members.role
            else excluded.role
        end,
        status = 'active'
    where public.shared_tag_members.status <> 'removed';

    select group_record.name into group_name
    from public.shared_tag_groups group_record
    where group_record.id = invite_record.group_id;

    select member.role into applied_role
    from public.shared_tag_group_members member
    where member.group_id = invite_record.group_id
      and member.user_id = caller
      and member.status = 'active';

    if applied_role is null then
        raise exception 'member_removed';
    end if;

    return jsonb_build_object(
        'group_id', invite_record.group_id,
        'group_name', group_name,
        'role', applied_role,
        'status', 'active'
    );
end;
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
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);
    perform private.require_group_role(p_group_id, caller, array['owner']);

    if p_role not in ('editor', 'viewer') then
        raise exception 'invalid_group_role';
    end if;

    if p_user_id = caller then
        raise exception 'group_owner_transfer_required';
    end if;

    if not exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = p_group_id
          and member.user_id = p_user_id
          and member.status = 'active'
    ) then
        raise exception 'invalid_group_member_target';
    end if;

    update public.shared_tag_group_members
    set role = p_role,
        status = 'active'
    where group_id = p_group_id
      and user_id = p_user_id
      and status = 'active';

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
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);
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
      and user_id = p_new_owner_user_id
      and status = 'active';

    update public.shared_tag_group_members
    set role = 'editor'
    where group_id = p_group_id
      and user_id = caller
      and status = 'active';

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
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);

    if p_user_id = caller then
        perform private.require_group_role(p_group_id, caller, array['editor', 'viewer']);
    else
        perform private.require_group_role(p_group_id, caller, array['owner']);
    end if;

    if not exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = p_group_id
          and member.user_id = p_user_id
          and member.status = 'active'
    ) then
        raise exception 'invalid_group_member_target';
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
      and user_id = p_user_id
      and status = 'active';

    return jsonb_build_object('group_id', p_group_id, 'user_id', p_user_id, 'status', 'removed');
end;
$$;

revoke all on function public.create_shared_tag_group(text) from public, anon;
revoke all on function public.rename_shared_tag_group(uuid, text) from public, anon;
revoke all on function public.delete_shared_tag_group(uuid) from public, anon;
revoke all on function public.add_shared_tag_to_group(uuid, uuid) from public, anon;
revoke all on function public.remove_shared_tag_from_group(uuid, uuid) from public, anon;
revoke all on function public.create_shared_tag_group_invite(uuid, text) from public, anon;
revoke all on function public.create_shared_tag_in_group(uuid, text) from public, anon;
revoke all on function public.accept_shared_invite(text) from public, anon;
revoke all on function public.accept_shared_tag_invite(text) from public, anon;
revoke all on function public.accept_shared_tag_group_invite(text) from public, anon;
revoke all on function public.change_shared_tag_group_member_role(uuid, uuid, text) from public, anon;
revoke all on function public.transfer_shared_tag_group_ownership(uuid, uuid) from public, anon;
revoke all on function public.remove_shared_tag_group_member(uuid, uuid) from public, anon;

grant execute on function public.create_shared_tag_group(text) to authenticated;
grant execute on function public.rename_shared_tag_group(uuid, text) to authenticated;
grant execute on function public.delete_shared_tag_group(uuid) to authenticated;
grant execute on function public.add_shared_tag_to_group(uuid, uuid) to authenticated;
grant execute on function public.remove_shared_tag_from_group(uuid, uuid) to authenticated;
grant execute on function public.create_shared_tag_group_invite(uuid, text) to authenticated;
grant execute on function public.create_shared_tag_in_group(uuid, text) to authenticated;
grant execute on function public.accept_shared_invite(text) to authenticated;
grant execute on function public.accept_shared_tag_invite(text) to authenticated;
grant execute on function public.accept_shared_tag_group_invite(text) to authenticated;
grant execute on function public.change_shared_tag_group_member_role(uuid, uuid, text) to authenticated;
grant execute on function public.transfer_shared_tag_group_ownership(uuid, uuid) to authenticated;
grant execute on function public.remove_shared_tag_group_member(uuid, uuid) to authenticated;
