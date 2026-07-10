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
