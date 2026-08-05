\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

create or replace function pg_temp.assert_rpc_error(
    p_user_id uuid,
    p_label text,
    p_sql text,
    p_expected text
)
returns void
language plpgsql
as $$
declare
    ignored jsonb;
begin
    perform set_config('request.jwt.claim.sub', p_user_id::text, true);

    begin
        execute p_sql into ignored;
    exception
        when others then
            if position(p_expected in sqlerrm) = 0 then
                raise exception '% expected %, got %', p_label, p_expected, sqlerrm;
            end if;
            return;
    end;

    raise exception '% unexpectedly succeeded', p_label;
end;
$$;

create or replace function pg_temp.assert_restricted_rpc(
    p_user_id uuid,
    p_status text,
    p_label text,
    p_sql text
)
returns void
language plpgsql
as $$
declare
    ignored jsonb;
begin
    update public.user_profiles
    set account_status = p_status
    where user_id = p_user_id;

    perform set_config('request.jwt.claim.sub', p_user_id::text, true);

    begin
        execute p_sql into ignored;
    exception
        when others then
            if position('account_restricted' in sqlerrm) = 0 then
                raise exception '% expected account_restricted, got %', p_label, sqlerrm;
            end if;
            return;
    end;

    raise exception '% unexpectedly succeeded for % account', p_label, p_status;
end;
$$;

do $$
<<test_block>>
declare
    owner_id uuid := gen_random_uuid();
    editor_id uuid := gen_random_uuid();
    viewer_id uuid := gen_random_uuid();
    invitee_id uuid := gen_random_uuid();
    legacy_invitee_id uuid := gen_random_uuid();
    wrapper_invitee_id uuid := gen_random_uuid();
    outsider_id uuid := gen_random_uuid();
    tag_outsider_id uuid := gen_random_uuid();
    suspended_id uuid := gen_random_uuid();
    banned_id uuid := gen_random_uuid();
    group_id uuid;
    delete_group_id uuid;
    owner_tag_id uuid := gen_random_uuid();
    editor_tag_id uuid := gen_random_uuid();
    outsider_tag_id uuid := gen_random_uuid();
    group_payload jsonb;
    invite_payload jsonb;
    legacy_invite_payload jsonb;
    wrapper_invite_payload jsonb;
    accepted_payload jsonb;
    created_tag_payload jsonb;
begin
    insert into auth.users (id)
    values
        (owner_id),
        (editor_id),
        (viewer_id),
        (invitee_id),
        (legacy_invitee_id),
        (wrapper_invitee_id),
        (outsider_id),
        (tag_outsider_id),
        (suspended_id),
        (banned_id)
    on conflict (id) do nothing;

    insert into public.user_profiles (user_id, account_status)
    values
        (owner_id, 'active'),
        (editor_id, 'active'),
        (viewer_id, 'active'),
        (invitee_id, 'active'),
        (legacy_invitee_id, 'active'),
        (wrapper_invitee_id, 'active'),
        (outsider_id, 'active'),
        (tag_outsider_id, 'active'),
        (suspended_id, 'active'),
        (banned_id, 'active')
    on conflict (user_id) do update
    set account_status = excluded.account_status;

    perform set_config('request.jwt.claim.sub', owner_id::text, true);
    group_payload := public.create_shared_tag_group('Mutation authorization group');
    group_id := (group_payload ->> 'group_id')::uuid;

    if group_id is null or (group_payload ->> 'role') <> 'owner' then
        raise exception 'active owner could not create a group';
    end if;

    insert into public.shared_tag_group_members (group_id, user_id, role, status)
    values
        (group_id, editor_id, 'editor', 'active'),
        (group_id, viewer_id, 'viewer', 'active');

    insert into public.shared_tags (id, name, created_by)
    values
        (owner_tag_id, 'Owner Tag', owner_id),
        (editor_tag_id, 'Editor Tag', editor_id),
        (outsider_tag_id, 'Outsider Tag', tag_outsider_id);

    insert into public.shared_tag_members (tag_id, user_id, role, status)
    values
        (owner_tag_id, owner_id, 'owner', 'active'),
        (editor_tag_id, editor_id, 'owner', 'active'),
        (outsider_tag_id, tag_outsider_id, 'owner', 'active');

    perform public.rename_shared_tag_group(group_id, 'Renamed authorization group');
    if (
        select group_record.name
        from public.shared_tag_groups group_record
        where group_record.id = test_block.group_id
    ) <> 'Renamed authorization group' then
        raise exception 'owner could not rename the group';
    end if;

    perform public.add_shared_tag_to_group(group_id, owner_tag_id);
    perform set_config('request.jwt.claim.sub', editor_id::text, true);
    perform public.add_shared_tag_to_group(group_id, editor_tag_id);

    perform public.remove_shared_tag_from_group(group_id, owner_tag_id);
    perform set_config('request.jwt.claim.sub', owner_id::text, true);
    perform public.add_shared_tag_to_group(group_id, owner_tag_id);

    perform set_config('request.jwt.claim.sub', editor_id::text, true);
    perform public.remove_shared_tag_from_group(group_id, owner_tag_id);
    perform public.add_shared_tag_to_group(group_id, editor_tag_id);
    perform public.remove_shared_tag_from_group(group_id, editor_tag_id);

    -- The tag owner can leave its own tag without being a group member, but
    -- only for an existing group-tag relation.
    insert into public.shared_tag_group_tags (group_id, tag_id, added_by)
    values (group_id, outsider_tag_id, owner_id);
    perform set_config('request.jwt.claim.sub', tag_outsider_id::text, true);
    perform public.remove_shared_tag_from_group(group_id, outsider_tag_id);
    if exists (
        select 1
        from public.shared_tag_group_tags group_tag
        where group_tag.group_id = test_block.group_id
          and group_tag.tag_id = outsider_tag_id
    ) then
        raise exception 'tag owner self-removal did not remove the group tag';
    end if;

    perform set_config('request.jwt.claim.sub', owner_id::text, true);
    perform public.add_shared_tag_to_group(group_id, owner_tag_id);

    invite_payload := public.create_shared_tag_group_invite(group_id, 'viewer');
    legacy_invite_payload := public.create_shared_tag_group_invite(group_id, 'editor');
    wrapper_invite_payload := public.create_shared_tag_group_invite(group_id, 'viewer');

    if private.hash_shared_tag_invite_token(legacy_invite_payload ->> 'invite_token') <>
       private.hash_shared_tag_group_invite_token(legacy_invite_payload ->> 'invite_token') then
        raise exception 'group invite hash functions diverged';
    end if;

    if coalesce(invite_payload ->> 'invite_token', '') = '' or
       coalesce(legacy_invite_payload ->> 'invite_token', '') = '' or
       coalesce(wrapper_invite_payload ->> 'invite_token', '') = '' then
        raise exception 'owner could not create group invites';
    end if;

    -- Every group mutation must fail closed before role checks for restricted users.
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'create group',
        'select public.create_shared_tag_group(''blocked'')'
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'rename group',
        format('select public.rename_shared_tag_group(%L::uuid, %L)', group_id, 'blocked')
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'delete group',
        format('select public.delete_shared_tag_group(%L::uuid)', group_id)
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'add tag to group',
        format('select public.add_shared_tag_to_group(%L::uuid, %L::uuid)', group_id, owner_tag_id)
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'remove tag from group',
        format('select public.remove_shared_tag_from_group(%L::uuid, %L::uuid)', group_id, owner_tag_id)
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'create group invite',
        format('select public.create_shared_tag_group_invite(%L::uuid, %L)', group_id, 'editor')
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'create tag in group',
        format('select public.create_shared_tag_in_group(%L::uuid, %L)', group_id, 'blocked tag')
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'accept generic invite',
        format('select public.accept_shared_invite(%L)', invite_payload ->> 'invite_token')
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'accept tag invite wrapper',
        format('select public.accept_shared_tag_invite(%L)', wrapper_invite_payload ->> 'invite_token')
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'accept group invite wrapper',
        format('select public.accept_shared_tag_group_invite(%L)', legacy_invite_payload ->> 'invite_token')
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'change member role',
        format('select public.change_shared_tag_group_member_role(%L::uuid, %L::uuid, %L)', group_id, viewer_id, 'editor')
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'transfer group ownership',
        format('select public.transfer_shared_tag_group_ownership(%L::uuid, %L::uuid)', group_id, viewer_id)
    );
    perform pg_temp.assert_restricted_rpc(
        suspended_id,
        'suspended',
        'remove group member',
        format('select public.remove_shared_tag_group_member(%L::uuid, %L::uuid)', group_id, viewer_id)
    );

    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'create group',
        'select public.create_shared_tag_group(''blocked'')'
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'rename group',
        format('select public.rename_shared_tag_group(%L::uuid, %L)', group_id, 'blocked')
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'delete group',
        format('select public.delete_shared_tag_group(%L::uuid)', group_id)
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'add tag to group',
        format('select public.add_shared_tag_to_group(%L::uuid, %L::uuid)', group_id, owner_tag_id)
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'remove tag from group',
        format('select public.remove_shared_tag_from_group(%L::uuid, %L::uuid)', group_id, owner_tag_id)
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'create group invite',
        format('select public.create_shared_tag_group_invite(%L::uuid, %L)', group_id, 'editor')
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'create tag in group',
        format('select public.create_shared_tag_in_group(%L::uuid, %L)', group_id, 'blocked tag')
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'accept generic invite',
        format('select public.accept_shared_invite(%L)', invite_payload ->> 'invite_token')
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'accept tag invite wrapper',
        format('select public.accept_shared_tag_invite(%L)', wrapper_invite_payload ->> 'invite_token')
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'accept group invite wrapper',
        format('select public.accept_shared_tag_group_invite(%L)', legacy_invite_payload ->> 'invite_token')
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'change member role',
        format('select public.change_shared_tag_group_member_role(%L::uuid, %L::uuid, %L)', group_id, viewer_id, 'editor')
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'transfer group ownership',
        format('select public.transfer_shared_tag_group_ownership(%L::uuid, %L::uuid)', group_id, viewer_id)
    );
    perform pg_temp.assert_restricted_rpc(
        banned_id,
        'banned',
        'remove group member',
        format('select public.remove_shared_tag_group_member(%L::uuid, %L::uuid)', group_id, viewer_id)
    );

    update public.user_profiles
    set account_status = 'active'
    where user_id in (suspended_id, banned_id);

    -- Outsiders and viewers cannot mutate group content or administration.
    perform pg_temp.assert_rpc_error(
        editor_id,
        'editor cannot rename group',
        format('select public.rename_shared_tag_group(%L::uuid, %L)', group_id, 'editor rename'),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        viewer_id,
        'viewer cannot rename group',
        format('select public.rename_shared_tag_group(%L::uuid, %L)', group_id, 'viewer rename'),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        outsider_id,
        'outsider cannot rename group',
        format('select public.rename_shared_tag_group(%L::uuid, %L)', group_id, 'outsider rename'),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        editor_id,
        'editor cannot delete group',
        format('select public.delete_shared_tag_group(%L::uuid)', group_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        viewer_id,
        'viewer cannot delete group',
        format('select public.delete_shared_tag_group(%L::uuid)', group_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        outsider_id,
        'outsider cannot delete group',
        format('select public.delete_shared_tag_group(%L::uuid)', group_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        editor_id,
        'editor cannot create invite',
        format('select public.create_shared_tag_group_invite(%L::uuid, %L)', group_id, 'viewer'),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        viewer_id,
        'viewer cannot create invite',
        format('select public.create_shared_tag_group_invite(%L::uuid, %L)', group_id, 'viewer'),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        outsider_id,
        'outsider cannot create invite',
        format('select public.create_shared_tag_group_invite(%L::uuid, %L)', group_id, 'viewer'),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        viewer_id,
        'viewer cannot add tag',
        format('select public.add_shared_tag_to_group(%L::uuid, %L::uuid)', group_id, owner_tag_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        editor_id,
        'editor cannot add a tag it does not own',
        format('select public.add_shared_tag_to_group(%L::uuid, %L::uuid)', group_id, owner_tag_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        outsider_id,
        'outsider cannot add its tag',
        format('select public.add_shared_tag_to_group(%L::uuid, %L::uuid)', group_id, outsider_tag_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        viewer_id,
        'viewer cannot remove group tag',
        format('select public.remove_shared_tag_from_group(%L::uuid, %L::uuid)', group_id, owner_tag_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        outsider_id,
        'outsider cannot remove group tag',
        format('select public.remove_shared_tag_from_group(%L::uuid, %L::uuid)', group_id, owner_tag_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        editor_id,
        'editor cannot change member role',
        format('select public.change_shared_tag_group_member_role(%L::uuid, %L::uuid, %L)', group_id, viewer_id, 'editor'),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        viewer_id,
        'viewer cannot change member role',
        format('select public.change_shared_tag_group_member_role(%L::uuid, %L::uuid, %L)', group_id, editor_id, 'viewer'),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        outsider_id,
        'outsider cannot change member role',
        format('select public.change_shared_tag_group_member_role(%L::uuid, %L::uuid, %L)', group_id, viewer_id, 'editor'),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        owner_id,
        'owner cannot target an absent member',
        format('select public.change_shared_tag_group_member_role(%L::uuid, %L::uuid, %L)', group_id, outsider_id, 'editor'),
        'invalid_group_member_target'
    );
    perform pg_temp.assert_rpc_error(
        owner_id,
        'owner cannot demote itself through role change',
        format('select public.change_shared_tag_group_member_role(%L::uuid, %L::uuid, %L)', group_id, owner_id, 'editor'),
        'group_owner_transfer_required'
    );
    perform pg_temp.assert_rpc_error(
        editor_id,
        'editor cannot transfer ownership',
        format('select public.transfer_shared_tag_group_ownership(%L::uuid, %L::uuid)', group_id, viewer_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        viewer_id,
        'viewer cannot transfer ownership',
        format('select public.transfer_shared_tag_group_ownership(%L::uuid, %L::uuid)', group_id, editor_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        outsider_id,
        'outsider cannot transfer ownership',
        format('select public.transfer_shared_tag_group_ownership(%L::uuid, %L::uuid)', group_id, viewer_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        owner_id,
        'owner cannot transfer to an absent member',
        format('select public.transfer_shared_tag_group_ownership(%L::uuid, %L::uuid)', group_id, outsider_id),
        'invalid_group_owner_target'
    );
    perform pg_temp.assert_rpc_error(
        editor_id,
        'editor cannot remove another member',
        format('select public.remove_shared_tag_group_member(%L::uuid, %L::uuid)', group_id, viewer_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        viewer_id,
        'viewer cannot remove another member',
        format('select public.remove_shared_tag_group_member(%L::uuid, %L::uuid)', group_id, editor_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        outsider_id,
        'outsider cannot leave or remove a member',
        format('select public.remove_shared_tag_group_member(%L::uuid, %L::uuid)', group_id, outsider_id),
        'forbidden'
    );
    perform pg_temp.assert_rpc_error(
        owner_id,
        'owner cannot remove an absent member',
        format('select public.remove_shared_tag_group_member(%L::uuid, %L::uuid)', group_id, outsider_id),
        'invalid_group_member_target'
    );

    -- Editors may manage tags they own and create tags inside the group.
    perform set_config('request.jwt.claim.sub', editor_id::text, true);
    perform public.add_shared_tag_to_group(group_id, editor_tag_id);
    created_tag_payload := public.create_shared_tag_in_group(group_id, 'Editor-created tag');
    if (created_tag_payload ->> 'group_id')::uuid <> group_id then
        raise exception 'editor could not create a tag in the group';
    end if;
    perform public.remove_shared_tag_from_group(group_id, editor_tag_id);

    -- Active invite acceptance is allowed, while the restricted cases above are not.
    perform set_config('request.jwt.claim.sub', invitee_id::text, true);
    accepted_payload := public.accept_shared_invite(invite_payload ->> 'invite_token');
    if accepted_payload ->> 'invite_type' <> 'group' or
       (accepted_payload ->> 'group_id')::uuid <> group_id then
        raise exception 'active generic group invite acceptance failed';
    end if;

    perform set_config('request.jwt.claim.sub', legacy_invitee_id::text, true);
    accepted_payload := public.accept_shared_tag_group_invite(legacy_invite_payload ->> 'invite_token');
    if (accepted_payload ->> 'group_id')::uuid <> group_id then
        raise exception 'active legacy group invite acceptance failed';
    end if;

    perform set_config('request.jwt.claim.sub', wrapper_invitee_id::text, true);
    accepted_payload := public.accept_shared_tag_invite(wrapper_invite_payload ->> 'invite_token');
    if (accepted_payload ->> 'group_id')::uuid <> group_id then
        raise exception 'active generic invite wrapper acceptance failed';
    end if;

    if (
        select count(*)
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.user_id in (invitee_id, legacy_invitee_id, wrapper_invitee_id)
          and member.status = 'active'
    ) <> 3 then
        raise exception 'active invite acceptance did not create all memberships';
    end if;

    perform set_config('request.jwt.claim.sub', owner_id::text, true);
    perform public.change_shared_tag_group_member_role(group_id, viewer_id, 'editor');
    if (
        select member.role
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.user_id = viewer_id
    ) <> 'editor' then
        raise exception 'owner could not change an active member role';
    end if;

    perform public.remove_shared_tag_group_member(group_id, invitee_id);
    if exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.user_id = invitee_id
          and member.status = 'active'
    ) then
        raise exception 'owner could not remove another active member';
    end if;

    perform set_config('request.jwt.claim.sub', editor_id::text, true);
    perform public.remove_shared_tag_group_member(group_id, editor_id);
    if exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.user_id = editor_id
          and member.status = 'active'
    ) then
        raise exception 'editor self-leave did not remove the membership';
    end if;

    perform set_config('request.jwt.claim.sub', owner_id::text, true);
    group_payload := public.create_shared_tag_group('Group to delete');
    delete_group_id := (group_payload ->> 'group_id')::uuid;
    perform public.delete_shared_tag_group(delete_group_id);
    if not exists (
        select 1
        from public.shared_tag_groups
        where id = delete_group_id
          and deleted_at is not null
    ) then
        raise exception 'owner could not delete a group';
    end if;

    perform public.transfer_shared_tag_group_ownership(group_id, wrapper_invitee_id);
    if (
        select member.role
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.user_id = wrapper_invitee_id
    ) <> 'owner' or (
        select member.role
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.user_id = owner_id
    ) <> 'editor' then
        raise exception 'owner could not transfer group ownership';
    end if;
end
$$;

select extensions.pass('shared tag group mutation authorization validation');
select * from extensions.finish();
