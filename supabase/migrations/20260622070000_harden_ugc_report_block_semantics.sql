-- Harden UGC report/block semantics without letting interpersonal blocks
-- remove owner/admin capabilities.

create unique index if not exists idx_shared_content_reports_open_dedupe
on public.shared_content_reports (
    reporter_user_id,
    coalesce(reported_user_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(shared_tag_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(shared_tag_group_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(shared_url_id, '00000000-0000-0000-0000-000000000000'::uuid),
    category
)
where reporter_user_id is not null
  and status in ('open', 'reviewing');
create or replace function private.users_have_shared_context(p_user_a uuid, p_user_b uuid)
returns boolean
language sql
stable
security definer
set search_path = public, private, pg_temp
as $$
    select p_user_a is not null
       and p_user_b is not null
       and p_user_a <> p_user_b
       and (
           exists (
               select 1
               from public.shared_tag_members a
               join public.shared_tag_members b
                 on b.tag_id = a.tag_id
               join public.shared_tags tag
                 on tag.id = a.tag_id
               where a.user_id = p_user_a
                 and b.user_id = p_user_b
                 and a.status = 'active'
                 and b.status = 'active'
                 and tag.deleted_at is null
           )
           or exists (
               select 1
               from public.shared_tag_group_members a
               join public.shared_tag_group_members b
                 on b.group_id = a.group_id
               join public.shared_tag_groups group_record
                 on group_record.id = a.group_id
               where a.user_id = p_user_a
                 and b.user_id = p_user_b
                 and a.status = 'active'
                 and b.status = 'active'
                 and group_record.deleted_at is null
           )
           or exists (
               select 1
               from public.shared_tag_urls url
               join public.shared_tags tag
                 on tag.id = url.tag_id
               join public.shared_tag_members member
                 on member.tag_id = url.tag_id
               where url.deleted_at is null
                 and tag.deleted_at is null
                 and member.status = 'active'
                 and (
                     (member.user_id = p_user_a and url.added_by = p_user_b)
                     or (member.user_id = p_user_b and url.added_by = p_user_a)
                 )
           )
       )
$$;
revoke all on function private.users_have_shared_context(uuid, uuid) from public, anon, authenticated;
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
security definer
set search_path = public, private, pg_temp
as $$
begin
    if p_user_id is null then
        raise exception 'auth_required';
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
    perform private.require_unrestricted_app_user(caller);

    if p_blocked_user_id is null or p_blocked_user_id = caller then
        raise exception 'invalid_block_target';
    end if;

    if not private.users_have_shared_context(caller, p_blocked_user_id) then
        raise exception 'block_target_unrelated';
    end if;

    insert into public.user_blocks (blocker_user_id, blocked_user_id, reason)
    values (caller, p_blocked_user_id, nullif(left(btrim(coalesce(p_reason, '')), 500), ''))
    on conflict (blocker_user_id, blocked_user_id) do update
    set reason = excluded.reason,
        created_at = now();

    return jsonb_build_object('status', 'blocked', 'blocked_user_id', p_blocked_user_id);
end;
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
    safe_details text := nullif(left(btrim(coalesce(p_details, '')), 1000), '');
    target_tag_id uuid;
    target_group_id uuid;
    canonical_reported_user_id uuid;
    existing_report_id uuid;
    report_id uuid;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;
    perform private.require_unrestricted_app_user(caller);

    if safe_category not in ('spam', 'harassment', 'illegal', 'privacy', 'other') then
        safe_category := 'other';
    end if;

    if p_shared_tag_id is null
       and p_shared_tag_group_id is null
       and p_shared_url_id is null
       and p_reported_user_id is null then
        raise exception 'missing_report_target';
    end if;

    if p_shared_url_id is not null then
        select url.tag_id, tag.group_id, url.added_by
        into target_tag_id, target_group_id, canonical_reported_user_id
        from public.shared_tag_urls url
        join public.shared_tags tag
          on tag.id = url.tag_id
        where url.id = p_shared_url_id
          and url.deleted_at is null
          and tag.deleted_at is null;

        if target_tag_id is null then
            raise exception 'report_target_not_found';
        end if;
    end if;

    if p_shared_tag_id is not null then
        if target_tag_id is not null and target_tag_id <> p_shared_tag_id then
            raise exception 'report_target_mismatch';
        end if;

        select tag.id, tag.group_id, coalesce(canonical_reported_user_id, tag.created_by)
        into target_tag_id, target_group_id, canonical_reported_user_id
        from public.shared_tags tag
        where tag.id = p_shared_tag_id
          and tag.deleted_at is null;

        if target_tag_id is null then
            raise exception 'report_target_not_found';
        end if;
    end if;

    if p_shared_tag_group_id is not null then
        if target_group_id is not null and target_group_id <> p_shared_tag_group_id then
            raise exception 'report_target_mismatch';
        end if;

        select group_record.id, coalesce(canonical_reported_user_id, group_record.created_by)
        into target_group_id, canonical_reported_user_id
        from public.shared_tag_groups group_record
        where group_record.id = p_shared_tag_group_id
          and group_record.deleted_at is null;

        if target_group_id is null then
            raise exception 'report_target_not_found';
        end if;
    end if;

    if target_tag_id is not null then
        perform private.require_tag_role(target_tag_id, caller, array['owner', 'editor', 'viewer']);
    elsif target_group_id is not null then
        perform private.require_shared_tag_group_role(target_group_id, caller, array['owner', 'editor', 'viewer']);
    elsif p_reported_user_id is not null then
        if not private.users_have_shared_context(caller, p_reported_user_id) then
            raise exception 'report_target_not_found';
        end if;
        canonical_reported_user_id := p_reported_user_id;
    end if;

    if p_reported_user_id is not null then
        if target_tag_id is not null and not exists (
            select 1
            from public.shared_tags tag
            where tag.id = target_tag_id
              and tag.created_by = p_reported_user_id
            union all
            select 1
            from public.shared_tag_members member
            where member.tag_id = target_tag_id
              and member.user_id = p_reported_user_id
              and member.status = 'active'
        ) then
            raise exception 'report_target_mismatch';
        end if;

        if target_group_id is not null and not exists (
            select 1
            from public.shared_tag_groups group_record
            where group_record.id = target_group_id
              and group_record.created_by = p_reported_user_id
            union all
            select 1
            from public.shared_tag_group_members member
            where member.group_id = target_group_id
              and member.user_id = p_reported_user_id
              and member.status = 'active'
        ) then
            raise exception 'report_target_mismatch';
        end if;

        if canonical_reported_user_id is not null
           and p_shared_url_id is not null
           and canonical_reported_user_id <> p_reported_user_id then
            raise exception 'report_target_mismatch';
        end if;

        canonical_reported_user_id := p_reported_user_id;
    end if;

    if canonical_reported_user_id = caller then
        raise exception 'invalid_report_target';
    end if;

    select report.id
    into existing_report_id
    from public.shared_content_reports report
    where report.reporter_user_id = caller
      and report.status in ('open', 'reviewing')
      and report.category = safe_category
      and report.reported_user_id is not distinct from canonical_reported_user_id
      and report.shared_tag_id is not distinct from target_tag_id
      and report.shared_tag_group_id is not distinct from target_group_id
      and report.shared_url_id is not distinct from p_shared_url_id
    order by report.created_at desc
    limit 1;

    if existing_report_id is not null then
        return jsonb_build_object('status', 'already_reported', 'report_id', existing_report_id);
    end if;

    if (
        select count(*)
        from public.shared_content_reports report
        where report.reporter_user_id = caller
          and report.created_at > now() - interval '1 hour'
    ) >= 5 then
        raise exception 'rate_limited';
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
        canonical_reported_user_id,
        target_tag_id,
        target_group_id,
        p_shared_url_id,
        safe_category,
        safe_details
    )
    returning id into report_id;

    return jsonb_build_object('status', 'reported', 'report_id', report_id);
exception
    when unique_violation then
        select report.id
        into existing_report_id
        from public.shared_content_reports report
        where report.reporter_user_id = caller
          and report.status in ('open', 'reviewing')
          and report.category = safe_category
          and report.reported_user_id is not distinct from canonical_reported_user_id
          and report.shared_tag_id is not distinct from target_tag_id
          and report.shared_tag_group_id is not distinct from target_group_id
          and report.shared_url_id is not distinct from p_shared_url_id
        order by report.created_at desc
        limit 1;

        if existing_report_id is null then
            raise;
        end if;

        return jsonb_build_object('status', 'already_reported', 'report_id', existing_report_id);
end;
$$;
revoke execute on function private.users_have_shared_context(uuid, uuid) from public, anon, authenticated;
revoke execute on function public.block_user(uuid, text) from public, anon;
revoke execute on function public.report_shared_content(uuid, uuid, uuid, uuid, text, text) from public, anon;
grant execute on function public.block_user(uuid, text) to authenticated;
grant execute on function public.report_shared_content(uuid, uuid, uuid, uuid, text, text) to authenticated;
