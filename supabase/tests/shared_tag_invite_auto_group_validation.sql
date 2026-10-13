\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

do $$
<<test_block>>
declare
    owner_id uuid := gen_random_uuid();
    first_invitee_id uuid := gen_random_uuid();
    second_invitee_id uuid := gen_random_uuid();
    outsider_id uuid := gen_random_uuid();
    contributed_tag_editor_id uuid := gen_random_uuid();
    tag_id uuid := gen_random_uuid();
    contributed_tag_id uuid := gen_random_uuid();
    other_tag_id uuid := gen_random_uuid();
    first_invite jsonb;
    second_invite jsonb;
    pending_invite jsonb;
    accepted jsonb;
    group_id uuid;
    repeated_group_id uuid;
    auto_group_count integer;
    snapshot jsonb;
begin
    insert into auth.users (id)
    values
        (owner_id),
        (first_invitee_id),
        (second_invitee_id),
        (outsider_id),
        (contributed_tag_editor_id)
    on conflict (id) do nothing;

    insert into public.user_profiles (user_id, account_status)
    values
        (owner_id, 'active'),
        (first_invitee_id, 'active'),
        (second_invitee_id, 'active'),
        (outsider_id, 'active'),
        (contributed_tag_editor_id, 'active')
    on conflict (user_id) do update
    set account_status = excluded.account_status;

    insert into public.shared_tags (id, name, created_by)
    values
        (tag_id, 'Auto group test tag', owner_id),
        (contributed_tag_id, 'Invitee-owned tag', first_invitee_id),
        (other_tag_id, 'Another member tag', second_invitee_id);

    insert into public.shared_tag_members (tag_id, user_id, role, status)
    values
        (tag_id, owner_id, 'owner', 'active'),
        (contributed_tag_id, first_invitee_id, 'owner', 'active'),
        (contributed_tag_id, contributed_tag_editor_id, 'editor', 'active'),
        (other_tag_id, second_invitee_id, 'owner', 'active');

    perform set_config('request.jwt.claim.sub', owner_id::text, true);
    first_invite := public.create_shared_tag_invite(tag_id, 'viewer');
    second_invite := public.create_shared_tag_invite(tag_id, 'editor');
    pending_invite := public.create_shared_tag_invite(tag_id, 'viewer');

    perform public.preview_shared_invite(first_invite ->> 'invite_token');
    if exists (
        select 1
        from public.shared_tag_groups grp
        where grp.auto_created_from_tag_id = test_block.tag_id
    ) then
        raise exception 'preview or invite creation created an automatic group before acceptance';
    end if;

    perform set_config('request.jwt.claim.sub', first_invitee_id::text, true);
    accepted := public.accept_shared_invite(first_invite ->> 'invite_token');
    group_id := (accepted ->> 'group_id')::uuid;

    if accepted ->> 'invite_type' <> 'tag'
       or accepted ->> 'group_name' <> 'Auto group test tag'
       or group_id is null then
        raise exception 'tag invite acceptance did not return its automatic group';
    end if;

    if not exists (
        select 1
        from public.shared_tag_groups grp
        join public.shared_tag_group_tags group_tag
          on group_tag.group_id = grp.id
         and group_tag.tag_id = test_block.tag_id
        join public.shared_tag_group_members owner_member
          on owner_member.group_id = grp.id
         and owner_member.user_id = owner_id
         and owner_member.role = 'owner'
         and owner_member.status = 'active'
        join public.shared_tag_group_members invitee_member
          on invitee_member.group_id = grp.id
         and invitee_member.user_id = first_invitee_id
         and invitee_member.role = 'viewer'
         and invitee_member.status = 'active'
        where grp.id = test_block.group_id
          and grp.created_by = owner_id
          and grp.auto_created_from_tag_id = test_block.tag_id
          and grp.deleted_at is null
    ) then
        raise exception 'automatic group did not preserve owner, invitee role, and source tag';
    end if;

    if not exists (
        select 1
        from public.shared_tag_members member
        where member.tag_id = test_block.tag_id
          and member.user_id = first_invitee_id
          and member.role = 'viewer'
          and member.status = 'active'
    ) then
        raise exception 'accepted invitee lost the direct shared-tag membership';
    end if;

    accepted := public.accept_shared_tag_invite(first_invite ->> 'invite_token');
    repeated_group_id := (accepted ->> 'group_id')::uuid;
    select count(*) into auto_group_count
    from public.shared_tag_groups grp
    where grp.auto_created_from_tag_id = test_block.tag_id
      and grp.created_by = owner_id
      and grp.deleted_at is null;
    if repeated_group_id <> test_block.group_id or auto_group_count <> 1 then
        raise exception 'same invite acceptance was not idempotent';
    end if;

    perform set_config('request.jwt.claim.sub', second_invitee_id::text, true);
    accepted := public.accept_shared_invite(second_invite ->> 'invite_token');
    if (accepted ->> 'group_id')::uuid <> test_block.group_id then
        raise exception 'a second tag invite created a different automatic group';
    end if;

    perform set_config('request.jwt.claim.sub', first_invitee_id::text, true);
    perform public.add_shared_tag_to_group(group_id, contributed_tag_id);

    if not exists (
        select 1
        from public.shared_tag_group_tags group_tag
        where group_tag.group_id = test_block.group_id
          and group_tag.tag_id = contributed_tag_id
          and group_tag.added_by = first_invitee_id
    ) then
        raise exception 'viewer could not add its own shared tag to the group';
    end if;

    if (
        select member.role
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.user_id = first_invitee_id
          and member.status = 'active'
    ) <> 'viewer' then
        raise exception 'adding an owned tag escalated the viewer group role';
    end if;

    if exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.user_id = test_block.contributed_tag_editor_id
          and member.status = 'active'
    ) then
        raise exception 'viewer contribution imported a source-tag editor into the group';
    end if;

    if not exists (
        select 1
        from public.shared_tag_members member
        where member.tag_id = test_block.contributed_tag_id
          and member.user_id = test_block.contributed_tag_editor_id
          and member.role = 'editor'
          and member.status = 'active'
    ) then
        raise exception 'viewer contribution removed or changed the owned tag direct editor';
    end if;

    if exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.status = 'active'
          and (
              (member.user_id = test_block.owner_id and member.role <> 'owner')
              or (member.user_id = test_block.first_invitee_id and member.role <> 'viewer')
              or (member.user_id = test_block.second_invitee_id and member.role <> 'editor')
          )
    ) then
        raise exception 'viewer contribution changed an existing group member role';
    end if;

    begin
        perform public.add_shared_tag_to_group(group_id, other_tag_id);
        raise exception 'viewer added a tag owned by another member';
    exception
        when others then
            if position('forbidden' in sqlerrm) = 0 then
                raise;
            end if;
    end;

    snapshot := public.pull_shared_tag_snapshot();
    if not exists (
        select 1
        from jsonb_array_elements(snapshot -> 'groups') grp
        where grp ->> 'id' = test_block.group_id::text
    ) or not exists (
        select 1
        from jsonb_array_elements(snapshot -> 'group_tags') group_tag
        where group_tag ->> 'group_id' = test_block.group_id::text
          and group_tag ->> 'tag_id' = test_block.tag_id::text
    ) then
        raise exception 'accepted group/tag relationship was missing from the shared snapshot';
    end if;

    if not exists (
        select 1
        from jsonb_array_elements(snapshot -> 'members') member
        where member ->> 'tag_id' = test_block.tag_id::text
          and member ->> 'user_id' = first_invitee_id::text
          and member ->> 'role' = 'viewer'
    ) then
        raise exception 'viewer role was not preserved in the effective source-tag membership';
    end if;

    perform set_config('request.jwt.claim.sub', outsider_id::text, true);
    begin
        perform public.accept_shared_invite(first_invite ->> 'invite_token');
        raise exception 'claimed tag invite was accepted by another user';
    exception
        when others then
            if position('invalid_or_expired_invite' in sqlerrm) = 0 then
                raise;
            end if;
    end;

    if exists (
        select 1
        from public.shared_tag_members member
        where member.tag_id = test_block.tag_id
          and member.user_id = outsider_id
          and member.status = 'active'
    ) or exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.user_id = outsider_id
          and member.status = 'active'
    ) then
        raise exception 'unaccepted invitee was added to tag or group membership';
    end if;

    perform set_config('request.jwt.claim.sub', owner_id::text, true);
    if coalesce(pending_invite ->> 'invite_token', '') = '' then
        raise exception 'unaccepted invite fixture was not created';
    end if;
    if not exists (
        select 1
        from public.shared_tag_invites invite
        where invite.token_hash = private.hash_shared_tag_invite_token(pending_invite ->> 'invite_token')
          and invite.claimed_by is null
    ) or (
        select count(*)
        from public.shared_tag_group_members member
        where member.group_id = test_block.group_id
          and member.status = 'active'
    ) <> 3 then
        raise exception 'unaccepted invite changed group membership or was claimed';
    end if;
end
$$;

select extensions.pass('shared tag invite acceptance auto-group validation');
select * from extensions.finish();
