-- The group-account-deletion migration replaced delete_my_account() and
-- dropped the reassignment of retained shared data after ownership transfer.
-- Restore that invariant while keeping the later group/moderation cleanup.
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

    update public.shared_tag_group_members member
    set role = 'owner'
    where member.user_id <> caller
      and member.status = 'active'
      and member.role <> 'owner'
      and exists (
          select 1
          from public.shared_tag_group_members caller_owner
          where caller_owner.group_id = member.group_id
            and caller_owner.user_id = caller
            and caller_owner.status = 'active'
            and caller_owner.role = 'owner'
      )
      and not exists (
          select 1
          from public.shared_tag_group_members existing_owner
          where existing_owner.group_id = member.group_id
            and existing_owner.user_id <> caller
            and existing_owner.status = 'active'
            and existing_owner.role = 'owner'
      )
      and member.user_id = (
          select next_member.user_id
          from public.shared_tag_group_members next_member
          where next_member.group_id = member.group_id
            and next_member.user_id <> caller
            and next_member.status = 'active'
          order by next_member.created_at asc, next_member.user_id asc
          limit 1
      );

    update public.shared_tag_groups grp
    set deleted_at = now()
    where exists (
        select 1 from public.shared_tag_group_members owner_member
        where owner_member.group_id = grp.id
          and owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
    )
      and not exists (
          select 1 from public.shared_tag_group_members other_member
          where other_member.group_id = grp.id
            and other_member.user_id <> caller
            and other_member.status = 'active'
      );

    delete from public.shared_tags tag
    where exists (
        select 1 from public.shared_tag_members owner_member
        where owner_member.tag_id = tag.id
          and owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
    )
      and not exists (
          select 1 from public.shared_tag_members other_member
          where other_member.tag_id = tag.id
            and other_member.user_id <> caller
            and other_member.status = 'active'
      );

    update public.shared_tag_invites
    set revoked_at = now()
    where created_by = caller and revoked_at is null;

    update public.shared_tag_group_invites
    set revoked_at = now()
    where created_by = caller and revoked_at is null;

    with active_owner_by_group as (
        select distinct on (member.group_id) member.group_id, member.user_id
        from public.shared_tag_group_members member
        where member.user_id <> caller
          and member.role = 'owner'
          and member.status = 'active'
        order by member.group_id, member.updated_at desc, member.created_at desc
    )
    update public.shared_tag_groups grp
    set created_by = owner_member.user_id
    from active_owner_by_group owner_member
    where grp.created_by = caller
      and grp.id = owner_member.group_id
      and grp.deleted_at is null;

    with active_owner_by_group as (
        select distinct on (member.group_id) member.group_id, member.user_id
        from public.shared_tag_group_members member
        where member.user_id <> caller
          and member.role = 'owner'
          and member.status = 'active'
        order by member.group_id, member.updated_at desc, member.created_at desc
    )
    update public.shared_tag_group_invites invite
    set created_by = owner_member.user_id
    from active_owner_by_group owner_member
    where invite.created_by = caller
      and invite.group_id = owner_member.group_id;

    with active_owner_by_tag as (
        select distinct on (member.tag_id) member.tag_id, member.user_id
        from public.shared_tag_members member
        where member.user_id <> caller
          and member.role = 'owner'
          and member.status = 'active'
        order by member.tag_id, member.updated_at desc, member.created_at desc
    )
    update public.shared_tags tag
    set created_by = owner_member.user_id
    from active_owner_by_tag owner_member
    where tag.created_by = caller
      and tag.id = owner_member.tag_id
      and tag.deleted_at is null;

    with active_owner_by_tag as (
        select distinct on (member.tag_id) member.tag_id, member.user_id
        from public.shared_tag_members member
        where member.user_id <> caller
          and member.role = 'owner'
          and member.status = 'active'
        order by member.tag_id, member.updated_at desc, member.created_at desc
    )
    update public.shared_tag_invites invite
    set created_by = owner_member.user_id
    from active_owner_by_tag owner_member
    where invite.created_by = caller
      and invite.tag_id = owner_member.tag_id;

    with active_owner_by_tag as (
        select distinct on (member.tag_id) member.tag_id, member.user_id
        from public.shared_tag_members member
        where member.user_id <> caller
          and member.role = 'owner'
          and member.status = 'active'
        order by member.tag_id, member.updated_at desc, member.created_at desc
    )
    update public.shared_tag_urls url
    set added_by = owner_member.user_id
    from active_owner_by_tag owner_member
    where url.added_by = caller
      and url.tag_id = owner_member.tag_id;

    update public.shared_content_reports
    set reporter_user_id = null where reporter_user_id = caller;
    update public.shared_content_reports
    set reported_user_id = null where reported_user_id = caller;
    update public.moderation_actions
    set target_user_id = null where target_user_id = caller;
    delete from public.user_blocks
    where blocker_user_id = caller or blocked_user_id = caller;

    delete from public.shared_tag_group_members where user_id = caller;
    delete from public.shared_tag_members where user_id = caller;
    delete from public.applied_client_ops where user_id = caller;
    delete from auth.users where id = caller;

    return jsonb_build_object('status', 'deleted', 'user_id', caller);
end;
$$;

grant execute on function public.delete_my_account() to authenticated;
