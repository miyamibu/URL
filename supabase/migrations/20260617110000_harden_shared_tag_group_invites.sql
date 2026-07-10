-- Harden shared-tag group RPCs without rewriting the already-applied group migration.

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
        'name', group_name,
        'role', 'owner',
        'status', 'active'
    );
end;
$$;
create or replace function public.list_shared_tag_groups()
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

    return coalesce(
        (
            select jsonb_agg(
                jsonb_build_object(
                    'group_id', group_record.id,
                    'name', group_record.name,
                    'role', member.role,
                    'member_count', (
                        select count(*)
                        from public.shared_tag_group_members count_member
                        where count_member.group_id = group_record.id
                          and count_member.status = 'active'
                    ),
                    'tag_count', (
                        select count(*)
                        from public.shared_tags tag
                        where tag.group_id = group_record.id
                          and tag.deleted_at is null
                    )
                )
                order by group_record.name
            )
            from public.shared_tag_groups group_record
            join public.shared_tag_group_members member
              on member.group_id = group_record.id
            where member.user_id = caller
              and member.status = 'active'
              and group_record.deleted_at is null
        ),
        '[]'::jsonb
    );
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

    if p_role not in ('editor', 'viewer') then
        raise exception 'invalid_group_invite_role';
    end if;

    perform private.require_shared_tag_group_role(p_group_id, caller, array['owner', 'editor']);

    invite_token := encode(extensions.gen_random_bytes(24), 'hex');
    invite_hash := private.hash_shared_tag_group_invite_token(invite_token);

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

    if tag_name is null or tag_name = '' or char_length(tag_name) > 50 then
        raise exception 'invalid_tag_name';
    end if;

    perform private.require_shared_tag_group_role(p_group_id, caller, array['owner', 'editor']);

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
create or replace function public.preview_shared_tag_group_invite(p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    invite_record public.shared_tag_group_invites%rowtype;
    group_name text;
begin
    -- Token possession is the access control for preview. A valid token holder
    -- can see only group_name and role, not membership or URL contents.
    select invite.*
    into invite_record
    from public.shared_tag_group_invites invite
    join public.shared_tag_groups group_record
      on group_record.id = invite.group_id
    join public.shared_tag_group_members creator_member
      on creator_member.group_id = invite.group_id
     and creator_member.user_id = invite.created_by
    where invite.token_hash = private.hash_shared_tag_group_invite_token(p_token)
      and invite.revoked_at is null
      and invite.expires_at > now()
      and invite.claimed_by is null
      and group_record.deleted_at is null
      and creator_member.status = 'active'
      and creator_member.role in ('owner', 'editor')
    limit 1;

    if invite_record.token_hash is null then
        raise exception 'invalid_or_expired_group_invite';
    end if;

    select group_record.name into group_name
    from public.shared_tag_groups group_record
    where group_record.id = invite_record.group_id;

    return jsonb_build_object(
        'group_id', invite_record.group_id,
        'group_name', group_name,
        'role', invite_record.role
    );
end;
$$;
revoke execute on function public.create_shared_tag_group(text) from public, anon;
revoke execute on function public.list_shared_tag_groups() from public, anon;
revoke execute on function public.create_shared_tag_group_invite(uuid, text) from public, anon;
revoke execute on function public.create_shared_tag_in_group(uuid, text) from public, anon;
revoke execute on function public.accept_shared_tag_group_invite(text) from public, anon;
revoke execute on function public.preview_shared_tag_group_invite(text) from public;
grant execute on function public.create_shared_tag_group(text) to authenticated;
grant execute on function public.list_shared_tag_groups() to authenticated;
grant execute on function public.create_shared_tag_group_invite(uuid, text) to authenticated;
grant execute on function public.create_shared_tag_in_group(uuid, text) to authenticated;
grant execute on function public.accept_shared_tag_group_invite(text) to authenticated;
grant execute on function public.preview_shared_tag_group_invite(text) to anon, authenticated;
