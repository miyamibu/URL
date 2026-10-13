alter table public.shared_tag_groups
    add column if not exists auto_created_from_tag_id uuid
        references public.shared_tags(id) on delete set null;

create unique index if not exists idx_shared_tag_groups_auto_tag_owner_active
    on public.shared_tag_groups (auto_created_from_tag_id, created_by)
    where auto_created_from_tag_id is not null
      and deleted_at is null;

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
    tag_owner_id uuid;
    auto_group_id uuid;
    auto_group_name text;
    group_name text;
    applied_role text;
    group_role text;
    existing_group_role text;
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
    limit 1
    for update of invite, tag;

    if tag_invite.token_hash is not null then
        select tag.name, member.user_id
        into tag_name, tag_owner_id
        from public.shared_tags tag
        join public.shared_tag_members member
          on member.tag_id = tag.id
         and member.role = 'owner'
         and member.status = 'active'
        where tag.id = tag_invite.tag_id
          and tag.deleted_at is null
        for update of tag, member;

        if tag_owner_id is null then
            raise exception 'shared_tag_owner_required';
        end if;

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

        select member.role into applied_role
        from public.shared_tag_members member
        where member.tag_id = tag_invite.tag_id
          and member.user_id = caller;

        -- Serialize accepted invites for this tag so the owner and invitees
        -- converge on one automatic group without duplicating it.
        select grp.id, grp.name
        into auto_group_id, auto_group_name
        from public.shared_tag_groups grp
        where grp.auto_created_from_tag_id = tag_invite.tag_id
          and grp.created_by = tag_owner_id
          and grp.deleted_at is null
        limit 1
        for update;

        if auto_group_id is null then
            auto_group_id := gen_random_uuid();
            auto_group_name := left(coalesce(nullif(btrim(tag_name), ''), '共有タグ'), 50);

            insert into public.shared_tag_groups (
                id,
                name,
                created_by,
                auto_created_from_tag_id
            )
            values (
                auto_group_id,
                auto_group_name,
                tag_owner_id,
                tag_invite.tag_id
            );

            insert into public.shared_tag_group_members (group_id, user_id, role, status)
            values (auto_group_id, tag_owner_id, 'owner', 'active');
        end if;

        insert into public.shared_tag_group_tags (group_id, tag_id, added_by)
        values (auto_group_id, tag_invite.tag_id, tag_owner_id)
        on conflict (group_id, tag_id) do nothing;

        if caller = tag_owner_id then
            select member.role
            into existing_group_role
            from public.shared_tag_group_members member
            where member.group_id = auto_group_id
              and member.user_id = caller
              and member.status = 'active';
            group_role := coalesce(existing_group_role, 'editor');
        else
            group_role := case applied_role
                when 'viewer' then 'viewer'
                else 'editor'
            end;
        end if;

        insert into public.shared_tag_group_members (group_id, user_id, role, status)
        values (auto_group_id, caller, group_role, 'active')
        on conflict (group_id, user_id) do update
        set role = case
                when public.shared_tag_group_members.role = 'owner' then public.shared_tag_group_members.role
                else private.shared_role_from_rank(greatest(
                    private.shared_role_rank(public.shared_tag_group_members.role),
                    private.shared_role_rank(excluded.role)
                ))
            end,
            status = 'active';

        select grp.name into auto_group_name
        from public.shared_tag_groups grp
        where grp.id = auto_group_id;

        return jsonb_build_object(
            'invite_type', 'tag',
            'tag_id', tag_invite.tag_id,
            'tag_name', tag_name,
            'group_id', auto_group_id,
            'group_name', auto_group_name,
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
    limit 1
    for update of invite;

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
    caller_group_role text;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);
    perform private.require_group_role(p_group_id, caller, array['owner', 'editor', 'viewer']);
    select member.role
    into caller_group_role
    from public.shared_tag_group_members member
    join public.shared_tag_groups grp
      on grp.id = member.group_id
     and grp.deleted_at is null
    where member.group_id = p_group_id
      and member.user_id = caller
      and member.status = 'active'
    for update of member;

    if caller_group_role is null
       or caller_group_role not in ('owner', 'editor', 'viewer') then
        raise exception 'forbidden';
    end if;

    perform private.require_direct_tag_owner(p_tag_id, caller);

    insert into public.shared_tag_group_tags (group_id, tag_id, added_by)
    values (p_group_id, p_tag_id, caller)
    on conflict (group_id, tag_id) do nothing;

    if caller_group_role = 'viewer' then
        return jsonb_build_object('group_id', p_group_id, 'tag_id', p_tag_id, 'status', 'added');
    end if;

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

revoke all on function public.add_shared_tag_to_group(uuid, uuid) from public, anon;
grant execute on function public.add_shared_tag_to_group(uuid, uuid) to authenticated;

revoke all on function public.accept_shared_invite(text) from public, anon;
grant execute on function public.accept_shared_invite(text) to authenticated;
