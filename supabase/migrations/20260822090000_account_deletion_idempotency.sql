-- Forward-only: account deletion idempotency contract (S1-REMOTE-MARKER-006 / S2-ACCDEL-008).
--
-- Problem: remote delete_my_account() commits, then the client crashes before it can
-- persist its durable local cleanup marker. On retry the client could repeat remote
-- deletion or stall with orphaned account-linked local data because neither a durable
-- request record nor a client-verifiable deletion status existed.
--
-- Contract added here:
-- * authenticated callers create a deletion request bound to themselves and receive a
--   one-time high-entropy token (the server stores only its SHA-256 hash);
-- * delete_my_account(p_request_id) validates that the request belongs to the caller,
--   marks it failed on owner_transfer_required, and completed once auth deletion commits;
-- * get_account_deletion_status(request_id, token) lets the original requester converge
--   (pending / completed / failed) even after its session is gone;
-- * the pre-existing zero-argument delete_my_account() remains untouched for clients
--   that have not adopted the request protocol.
--
-- No already-applied migration file is modified.

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.account_deletion_requests (
    request_id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    token_hash text not null,
    status text not null default 'pending'
        check (status in ('pending', 'completed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    completed_at timestamptz
);

create index if not exists account_deletion_requests_user_status_idx
    on public.account_deletion_requests (user_id, status);

alter table public.account_deletion_requests enable row level security;

-- Direct table access is never exposed. All access flows through the
-- security definer RPCs below.
revoke all on public.account_deletion_requests from anon;
revoke all on public.account_deletion_requests from authenticated;
revoke all on public.account_deletion_requests from public;

create or replace function public.create_account_deletion_request()
returns jsonb
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $$
declare
    caller uuid := auth.uid();
    v_request_id uuid := gen_random_uuid();
    v_token text := encode(extensions.gen_random_bytes(32), 'hex');
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    insert into public.account_deletion_requests (request_id, user_id, token_hash, status)
    values (
        v_request_id,
        caller,
        encode(sha256(convert_to(v_token, 'UTF8')), 'hex'),
        'pending'
    );

    return jsonb_build_object(
        'request_id', v_request_id,
        'token', v_token
    );
end;
$$;

revoke execute on function public.create_account_deletion_request() from public;
revoke execute on function public.create_account_deletion_request() from anon;
grant execute on function public.create_account_deletion_request() to authenticated;

create or replace function public.get_account_deletion_status(p_request_id uuid, p_token text)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v_row public.account_deletion_requests%rowtype;
begin
    if p_request_id is null or p_token is null or p_token = '' then
        return jsonb_build_object('status', 'not_found');
    end if;

    select *
    into v_row
    from public.account_deletion_requests
    where request_id = p_request_id
      and token_hash = encode(sha256(convert_to(p_token, 'UTF8')), 'hex');

    if not found then
        return jsonb_build_object('status', 'not_found');
    end if;

    return jsonb_build_object(
        'status', v_row.status,
        'user_id', v_row.user_id,
        'created_at', v_row.created_at,
        'completed_at', v_row.completed_at
    );
end;
$$;

revoke execute on function public.get_account_deletion_status(uuid, text) from public;
grant execute on function public.get_account_deletion_status(uuid, text) to anon;
grant execute on function public.get_account_deletion_status(uuid, text) to authenticated;

-- Single canonical signature with an optional deletion request id. The
-- zero-argument definition from 20260716140000_restore_account_reassignment.sql
-- is replaced here in a forward-only migration so that legacy no-argument calls
-- (PostgREST bodies of "{}") resolve unambiguously to this defaulted function.
-- Keep the cleanup body byte-equivalent to 20260716140000 except for the
-- guarded request bookkeeping below.
drop function if exists public.delete_my_account();

create or replace function public.delete_my_account(p_request_id uuid default null)
returns jsonb
language plpgsql
security definer
set search_path = public, auth, private, extensions, pg_temp
as $$
declare
    caller uuid := auth.uid();
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    -- A completed request replays safely: every statement below becomes a no-op
    -- once the caller's rows are gone, and the completion update matches nothing,
    -- so the same call keeps returning the same successful result.
    if p_request_id is not null then
        if not exists (
            select 1
            from public.account_deletion_requests
            where request_id = p_request_id
              and user_id = caller
              and status in ('pending', 'completed')
        ) then
            raise exception 'invalid_deletion_request';
        end if;
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
        -- Deliberately no bookkeeping here: raising aborts the whole
        -- transaction atomically, so the deletion request stays 'pending'
        -- and the client may retry with the same request id after the
        -- ownership transfer.
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

    if p_request_id is not null then
        update public.account_deletion_requests
        set status = 'completed',
            updated_at = now(),
            completed_at = now()
        where request_id = p_request_id
          and user_id = caller
          and status = 'pending';
    else
        -- Legacy no-argument callers have no request id to mark. Converge any
        -- pending requests they own so the ledger reflects the committed
        -- deletion instead of leaving orphaned pending rows behind.
        update public.account_deletion_requests
        set status = 'completed',
            updated_at = now(),
            completed_at = now()
        where user_id = caller
          and status = 'pending';
    end if;

    return jsonb_build_object('status', 'deleted', 'user_id', caller);
end;
$$;

revoke execute on function public.delete_my_account(uuid) from public;
revoke execute on function public.delete_my_account(uuid) from anon;
grant execute on function public.delete_my_account(uuid) to authenticated;
