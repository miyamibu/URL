create table if not exists public.user_blocks (
    blocker_user_id uuid not null,
    blocked_user_id uuid not null,
    reason text,
    created_at timestamptz not null default now(),
    primary key (blocker_user_id, blocked_user_id),
    check (blocker_user_id <> blocked_user_id)
);
create table if not exists public.shared_content_reports (
    id uuid primary key default gen_random_uuid(),
    reporter_user_id uuid,
    reported_user_id uuid,
    shared_tag_id uuid references public.shared_tags(id) on delete set null,
    shared_tag_group_id uuid references public.shared_tag_groups(id) on delete set null,
    shared_url_id uuid references public.shared_tag_urls(id) on delete set null,
    category text not null check (category in ('spam', 'harassment', 'illegal', 'privacy', 'other')),
    details text,
    status text not null default 'open' check (status in ('open', 'reviewing', 'actioned', 'rejected', 'closed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create table if not exists public.moderation_actions (
    id uuid primary key default gen_random_uuid(),
    report_id uuid references public.shared_content_reports(id) on delete set null,
    admin_user_id uuid references public.admin_users(id) on delete set null,
    target_user_id uuid,
    action text not null check (action in ('review', 'warn', 'hide_content', 'suspend_user', 'reject', 'close')),
    reason text,
    created_at timestamptz not null default now()
);
create index if not exists idx_user_blocks_blocked_user
    on public.user_blocks (blocked_user_id, created_at desc);
create index if not exists idx_shared_content_reports_status
    on public.shared_content_reports (status, created_at desc);
create index if not exists idx_shared_content_reports_reporter
    on public.shared_content_reports (reporter_user_id, created_at desc);
create index if not exists idx_moderation_actions_report
    on public.moderation_actions (report_id, created_at desc);
drop trigger if exists trg_shared_content_reports_updated_at on public.shared_content_reports;
create trigger trg_shared_content_reports_updated_at
before update on public.shared_content_reports
for each row
execute function private.set_updated_at();
alter table public.user_blocks enable row level security;
alter table public.shared_content_reports enable row level security;
alter table public.moderation_actions enable row level security;
drop policy if exists user_blocks_select_own on public.user_blocks;
create policy user_blocks_select_own
on public.user_blocks
for select
to authenticated
using (blocker_user_id = auth.uid());
drop policy if exists shared_content_reports_select_own on public.shared_content_reports;
create policy shared_content_reports_select_own
on public.shared_content_reports
for select
to authenticated
using (reporter_user_id = auth.uid());
revoke all on table public.user_blocks, public.shared_content_reports, public.moderation_actions from public, anon, authenticated;
grant select on public.user_blocks, public.shared_content_reports to authenticated;
create or replace function private.users_are_blocked(p_user_a uuid, p_user_b uuid)
returns boolean
language sql
stable
set search_path = public, private, pg_temp
as $$
    select p_user_a is not null
       and p_user_b is not null
       and exists (
           select 1
           from public.user_blocks block
           where (block.blocker_user_id = p_user_a and block.blocked_user_id = p_user_b)
              or (block.blocker_user_id = p_user_b and block.blocked_user_id = p_user_a)
       )
$$;
create or replace function private.require_no_block_between(p_user_a uuid, p_user_b uuid)
returns void
language plpgsql
stable
set search_path = public, private, pg_temp
as $$
begin
    if private.users_are_blocked(p_user_a, p_user_b) then
        raise exception 'user_blocked';
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
          and member.user_id <> p_user_id
          and member.status = 'active'
          and private.users_are_blocked(p_user_id, member.user_id)
    ) then
        raise exception 'user_blocked';
    end if;

    if not exists (
        select 1
        from public.shared_tag_members member
        where member.tag_id = p_tag_id
          and member.user_id = p_user_id
          and member.status = 'active'
          and member.role = any (p_roles)
    ) then
        raise exception 'forbidden';
    end if;
end;
$$;
create or replace function private.require_shared_tag_group_role(
    p_group_id uuid,
    p_user_id uuid,
    allowed_roles text[]
)
returns void
language plpgsql
stable
set search_path = public, private, pg_temp
as $$
begin
    if exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = p_group_id
          and member.user_id <> p_user_id
          and member.status = 'active'
          and private.users_are_blocked(p_user_id, member.user_id)
    ) then
        raise exception 'user_blocked';
    end if;

    if not exists (
        select 1
        from public.shared_tag_group_members member
        join public.shared_tag_groups group_record
          on group_record.id = member.group_id
        where member.group_id = p_group_id
          and member.user_id = p_user_id
          and member.status = 'active'
          and member.role = any(allowed_roles)
          and group_record.deleted_at is null
    ) then
        raise exception 'group_permission_denied';
    end if;
end;
$$;
create or replace function public.block_user(
    p_blocked_user_id uuid,
    p_reason text default null
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
    if p_blocked_user_id is null or p_blocked_user_id = caller then
        raise exception 'invalid_block_target';
    end if;

    insert into public.user_blocks (blocker_user_id, blocked_user_id, reason)
    values (caller, p_blocked_user_id, nullif(left(btrim(coalesce(p_reason, '')), 500), ''))
    on conflict (blocker_user_id, blocked_user_id) do update
    set reason = excluded.reason,
        created_at = now();

    return jsonb_build_object('status', 'blocked', 'blocked_user_id', p_blocked_user_id);
end;
$$;
create or replace function public.unblock_user(p_blocked_user_id uuid)
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

    delete from public.user_blocks
    where blocker_user_id = caller
      and blocked_user_id = p_blocked_user_id;

    return jsonb_build_object('status', 'unblocked', 'blocked_user_id', p_blocked_user_id);
end;
$$;
create or replace function public.list_my_blocks()
returns jsonb
language sql
security definer
stable
set search_path = public, private, pg_temp
as $$
    select coalesce(
        jsonb_agg(
            jsonb_build_object(
                'blocked_user_id', block.blocked_user_id,
                'reason', block.reason,
                'created_at', block.created_at
            )
            order by block.created_at desc
        ),
        '[]'::jsonb
    )
    from public.user_blocks block
    where block.blocker_user_id = auth.uid()
$$;
create or replace function public.report_shared_content(
    p_reported_user_id uuid default null,
    p_shared_tag_id uuid default null,
    p_shared_tag_group_id uuid default null,
    p_shared_url_id uuid default null,
    p_category text default 'other',
    p_details text default null
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    safe_category text := lower(btrim(coalesce(p_category, 'other')));
    report_id uuid;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;
    if safe_category not in ('spam', 'harassment', 'illegal', 'privacy', 'other') then
        safe_category := 'other';
    end if;
    if p_reported_user_id = caller then
        raise exception 'invalid_report_target';
    end if;
    if p_shared_tag_id is null and p_shared_tag_group_id is null and p_shared_url_id is null and p_reported_user_id is null then
        raise exception 'missing_report_target';
    end if;

    if p_shared_tag_id is not null then
        perform private.require_tag_role(p_shared_tag_id, caller, array['owner', 'editor', 'viewer']);
    end if;
    if p_shared_tag_group_id is not null then
        perform private.require_shared_tag_group_role(p_shared_tag_group_id, caller, array['owner', 'editor', 'viewer']);
    end if;

    insert into public.shared_content_reports (
        reporter_user_id,
        reported_user_id,
        shared_tag_id,
        shared_tag_group_id,
        shared_url_id,
        category,
        details
    )
    values (
        caller,
        p_reported_user_id,
        p_shared_tag_id,
        p_shared_tag_group_id,
        p_shared_url_id,
        safe_category,
        nullif(left(btrim(coalesce(p_details, '')), 4000), '')
    )
    returning id into report_id;

    return jsonb_build_object('status', 'reported', 'report_id', report_id);
end;
$$;
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

    delete from public.shared_tag_groups group_record
    where exists (
        select 1
        from public.shared_tag_group_members owner_member
        where owner_member.group_id = group_record.id
          and owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
    )
      and not exists (
          select 1
          from public.shared_tag_group_members other_member
          where other_member.group_id = group_record.id
            and other_member.user_id <> caller
            and other_member.status = 'active'
      );

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
          and not exists (
              select 1
              from public.shared_tag_members other_owner
              where other_owner.tag_id = owner_member.tag_id
                and other_owner.user_id <> caller
                and other_owner.role = 'owner'
                and other_owner.status = 'active'
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

    update public.shared_tag_group_invites
    set revoked_at = now()
    where created_by = caller
      and revoked_at is null;

    with active_owner_by_group as (
        select distinct on (member.group_id)
            member.group_id,
            member.user_id
        from public.shared_tag_group_members member
        where member.user_id <> caller
          and member.role = 'owner'
          and member.status = 'active'
        order by member.group_id, member.updated_at desc, member.created_at desc
    )
    update public.shared_tag_groups group_record
    set created_by = owner_member.user_id
    from active_owner_by_group owner_member
    where group_record.created_by = caller
      and group_record.id = owner_member.group_id
      and group_record.deleted_at is null;

    with active_owner_by_group as (
        select distinct on (member.group_id)
            member.group_id,
            member.user_id
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

    update public.shared_content_reports
    set reporter_user_id = null
    where reporter_user_id = caller;

    update public.shared_content_reports
    set reported_user_id = null
    where reported_user_id = caller;

    update public.moderation_actions
    set target_user_id = null
    where target_user_id = caller;

    delete from public.user_blocks
    where blocker_user_id = caller
       or blocked_user_id = caller;

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
revoke execute on function public.block_user(uuid, text) from public, anon;
revoke execute on function public.unblock_user(uuid) from public, anon;
revoke execute on function public.list_my_blocks() from public, anon;
revoke execute on function public.report_shared_content(uuid, uuid, uuid, uuid, text, text) from public, anon;
grant execute on function public.block_user(uuid, text) to authenticated;
grant execute on function public.unblock_user(uuid) to authenticated;
grant execute on function public.list_my_blocks() to authenticated;
grant execute on function public.report_shared_content(uuid, uuid, uuid, uuid, text, text) to authenticated;
