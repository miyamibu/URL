create or replace function public.transfer_shared_tag_ownership(
    p_tag_id uuid,
    p_new_owner_user_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = public, auth, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    transferred_at timestamptz := now();
    target_role text;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    if p_new_owner_user_id is null or p_new_owner_user_id = caller then
        raise exception 'invalid_new_owner';
    end if;

    if not exists (
        select 1
        from public.shared_tags tag
        where tag.id = p_tag_id
          and tag.deleted_at is null
    ) then
        raise exception 'tag_not_found';
    end if;

    perform private.require_tag_role(p_tag_id, caller, array['owner']);

    select member.role
    into target_role
    from public.shared_tag_members member
    where member.tag_id = p_tag_id
      and member.user_id = p_new_owner_user_id
      and member.status = 'active'
    limit 1;

    if target_role is null then
        raise exception 'target_member_not_found';
    end if;

    if target_role = 'owner' then
        raise exception 'target_already_owner';
    end if;

    update public.shared_tag_members
    set role = 'editor',
        updated_at = transferred_at
    where tag_id = p_tag_id
      and user_id = caller
      and role = 'owner'
      and status = 'active';

    if not found then
        raise exception 'forbidden';
    end if;

    update public.shared_tag_members
    set role = 'owner',
        updated_at = transferred_at
    where tag_id = p_tag_id
      and user_id = p_new_owner_user_id
      and status = 'active';

    return jsonb_build_object(
        'tag_id', p_tag_id,
        'previous_owner_user_id', caller,
        'new_owner_user_id', p_new_owner_user_id
    );
end;
$$;

grant execute on function public.transfer_shared_tag_ownership(uuid, uuid) to authenticated;

create or replace function private.require_single_active_shared_tag_owner()
returns trigger
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    affected_tag_id uuid;
    active_member_count integer;
    active_owner_count integer;
begin
    if tg_op = 'DELETE' then
        affected_tag_id := old.tag_id;
    else
        affected_tag_id := new.tag_id;
    end if;

    if not exists (
        select 1
        from public.shared_tags tag
        where tag.id = affected_tag_id
          and tag.deleted_at is null
    ) then
        if tg_op = 'DELETE' then
            return old;
        end if;
        return new;
    end if;

    select
        count(*) filter (where member.status = 'active'),
        count(*) filter (where member.status = 'active' and member.role = 'owner')
    into active_member_count, active_owner_count
    from public.shared_tag_members member
    where member.tag_id = affected_tag_id;

    if active_member_count > 0 and active_owner_count <> 1 then
        raise exception 'shared_tag_owner_required';
    end if;

    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_shared_tag_members_single_owner on public.shared_tag_members;
create constraint trigger trg_shared_tag_members_single_owner
after insert or update or delete on public.shared_tag_members
deferrable initially deferred
for each row
execute function private.require_single_active_shared_tag_owner();

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
    ) then
        raise exception 'owner_transfer_required';
    end if;

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

    -- Keep shared tags usable while removing the deleted account's user id from retained rows.
    with active_owner_by_tag as (
        select distinct on (member.tag_id)
            member.tag_id,
            member.user_id
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
        select distinct on (member.tag_id)
            member.tag_id,
            member.user_id
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
        select distinct on (member.tag_id)
            member.tag_id,
            member.user_id
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
