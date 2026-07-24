-- Personal-link data is owned by auth.users.  The account-deletion RPC deletes
-- auth.users last, so these cascades make deletion immediate and independent
-- of the disabled-sync purge scheduler.  NOT VALID keeps an existing orphan
-- from blocking deployment; all newly written rows are still constrained.
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'personal_link_sync_settings_user_id_fkey') then
        alter table public.personal_link_sync_settings
            add constraint personal_link_sync_settings_user_id_fkey
            foreign key (user_id) references auth.users(id) on delete cascade not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'personal_saved_links_user_id_fkey') then
        alter table public.personal_saved_links
            add constraint personal_saved_links_user_id_fkey
            foreign key (user_id) references auth.users(id) on delete cascade not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'personal_saved_link_tags_user_id_fkey') then
        alter table public.personal_saved_link_tags
            add constraint personal_saved_link_tags_user_id_fkey
            foreign key (user_id) references auth.users(id) on delete cascade not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'personal_saved_link_tag_refs_user_id_fkey') then
        alter table public.personal_saved_link_tag_refs
            add constraint personal_saved_link_tag_refs_user_id_fkey
            foreign key (user_id) references auth.users(id) on delete cascade not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'personal_link_enrichment_cache_user_id_fkey') then
        alter table public.personal_link_enrichment_cache
            add constraint personal_link_enrichment_cache_user_id_fkey
            foreign key (user_id) references auth.users(id) on delete cascade not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'personal_link_applied_client_ops_user_id_fkey') then
        alter table public.personal_link_applied_client_ops
            add constraint personal_link_applied_client_ops_user_id_fkey
            foreign key (user_id) references auth.users(id) on delete cascade not valid;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'personal_link_pending_write_actions_user_id_fkey') then
        alter table public.personal_link_pending_write_actions
            add constraint personal_link_pending_write_actions_user_id_fkey
            foreign key (user_id) references auth.users(id) on delete cascade not valid;
    end if;
end
$$;

create index if not exists idx_personal_saved_links_user_id
    on public.personal_saved_links (user_id);
create index if not exists idx_personal_saved_link_tags_user_id
    on public.personal_saved_link_tags (user_id);
create index if not exists idx_personal_link_enrichment_cache_user_id
    on public.personal_link_enrichment_cache (user_id);
create index if not exists idx_personal_link_applied_client_ops_user_id
    on public.personal_link_applied_client_ops (user_id);
create index if not exists idx_personal_link_pending_write_actions_user_id
    on public.personal_link_pending_write_actions (user_id);

-- Wrap the already-reviewed shared-data deletion routine so callers receive a
-- deletion receipt. The counts are captured before the legacy routine deletes
-- auth.users; the foreign keys above then cascade the personal rows.
do $$
begin
    if to_regprocedure('public.delete_my_account()') is not null
       and to_regprocedure('public.delete_my_account_legacy()') is null then
        alter function public.delete_my_account() rename to delete_my_account_legacy;
    end if;
end
$$;

create or replace function public.delete_my_account()
returns jsonb
language plpgsql
security definer
set search_path = public, auth, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    personal_sync_settings_count bigint;
    personal_saved_links_count bigint;
    personal_saved_link_tags_count bigint;
    personal_saved_link_tag_refs_count bigint;
    personal_enrichment_cache_count bigint;
    personal_applied_ops_count bigint;
    personal_pending_actions_count bigint;
    legacy_result jsonb;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    select count(*) into personal_sync_settings_count
    from public.personal_link_sync_settings where user_id = caller;
    select count(*) into personal_saved_links_count
    from public.personal_saved_links where user_id = caller;
    select count(*) into personal_saved_link_tags_count
    from public.personal_saved_link_tags where user_id = caller;
    select count(*) into personal_saved_link_tag_refs_count
    from public.personal_saved_link_tag_refs where user_id = caller;
    select count(*) into personal_enrichment_cache_count
    from public.personal_link_enrichment_cache where user_id = caller;
    select count(*) into personal_applied_ops_count
    from public.personal_link_applied_client_ops where user_id = caller;
    select count(*) into personal_pending_actions_count
    from public.personal_link_pending_write_actions where user_id = caller;

    -- The latest shared-tag deletion function was created by a later migration
    -- than the UGC cleanup function. Scrub every non-FK user reference here
    -- before auth.users is deleted; FK cascades handle the remaining owned rows.
    delete from public.shared_user_profiles
    where user_id = caller;

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

    update public.shared_tag_invites
       set claimed_by = null
     where claimed_by = caller;
    update public.shared_tag_group_invites
       set claimed_by = null
     where claimed_by = caller;

    -- Transfer direct shared-tag ownership before the legacy routine runs.
    -- That routine intentionally rejects deleting an owner while another
    -- active member remains, so changing only created_by is insufficient.
    with replacement as (
        select owner_member.tag_id,
            (
                select member.user_id
                from public.shared_tag_members member
                where member.tag_id = owner_member.tag_id
                  and member.user_id <> caller
                  and member.status = 'active'
                order by (member.role = 'owner') desc,
                         member.updated_at desc,
                         member.created_at asc,
                         member.user_id
                limit 1
            ) as user_id
        from public.shared_tag_members owner_member
        where owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
    )
    update public.shared_tag_members member
       set role = 'owner',
           updated_at = now()
      from replacement
     where member.tag_id = replacement.tag_id
       and member.user_id = replacement.user_id
       and member.status = 'active'
       and replacement.user_id is not null;

    with replacement as (
        select owner_member.tag_id,
            (
                select member.user_id
                from public.shared_tag_members member
                where member.tag_id = owner_member.tag_id
                  and member.user_id <> caller
                  and member.status = 'active'
                order by (member.role = 'owner') desc,
                         member.updated_at desc,
                         member.created_at asc,
                         member.user_id
                limit 1
            ) as user_id
        from public.shared_tag_members owner_member
        where owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
    )
    update public.shared_tag_members member
       set role = 'editor',
           updated_at = now()
      from replacement
     where member.tag_id = replacement.tag_id
       and member.user_id = caller
       and member.role = 'owner'
       and member.status = 'active'
       and replacement.user_id is not null;

    -- Transfer shared-tag-group ownership before the legacy routine runs.
    -- The legacy routine applies the same owner-transfer guard to groups, so
    -- direct tag ownership alone is not sufficient for account deletion.
    with replacement as (
        select owner_member.group_id,
            (
                select member.user_id
                from public.shared_tag_group_members member
                where member.group_id = owner_member.group_id
                  and member.user_id <> caller
                  and member.status = 'active'
                order by (member.role = 'owner') desc,
                         member.updated_at desc,
                         member.created_at asc,
                         member.user_id
                limit 1
            ) as user_id
        from public.shared_tag_group_members owner_member
        where owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
    )
    update public.shared_tag_group_members member
       set role = 'owner',
           updated_at = now()
      from replacement
     where member.group_id = replacement.group_id
       and member.user_id = replacement.user_id
       and member.status = 'active'
       and replacement.user_id is not null;

    with replacement as (
        select owner_member.group_id,
            (
                select member.user_id
                from public.shared_tag_group_members member
                where member.group_id = owner_member.group_id
                  and member.user_id <> caller
                  and member.status = 'active'
                order by (member.role = 'owner') desc,
                         member.updated_at desc,
                         member.created_at asc,
                         member.user_id
                limit 1
            ) as user_id
        from public.shared_tag_group_members owner_member
        where owner_member.user_id = caller
          and owner_member.role = 'owner'
          and owner_member.status = 'active'
    )
    update public.shared_tag_group_members member
       set role = 'editor',
           updated_at = now()
      from replacement
     where member.group_id = replacement.group_id
       and member.user_id = caller
       and member.role = 'owner'
       and member.status = 'active'
       and replacement.user_id is not null;

    -- Preserve shared rows by attributing creator/adder fields to an active
    -- member. Rows without a remaining active member are removed below rather
    -- than retaining a reference to the deleted account.
    with replacement as (
        select tag.id,
            coalesce(
                (
                    select member.user_id
                    from public.shared_tag_members member
                    where member.tag_id = tag.id
                      and member.user_id <> caller
                      and member.status = 'active'
                    order by (member.role = 'owner') desc,
                             member.updated_at desc,
                             member.created_at asc,
                             member.user_id
                    limit 1
                ),
                (
                    select member.user_id
                    from public.shared_tag_group_tags group_tag
                    join public.shared_tag_group_members member
                      on member.group_id = group_tag.group_id
                     and member.user_id <> caller
                     and member.status = 'active'
                    where group_tag.tag_id = tag.id
                    order by (member.role = 'owner') desc,
                             member.updated_at desc,
                             member.created_at asc,
                             member.user_id
                    limit 1
                )
            ) as user_id
        from public.shared_tags tag
        where tag.created_by = caller
    )
    update public.shared_tags tag
       set created_by = replacement.user_id
      from replacement
     where tag.id = replacement.id
       and replacement.user_id is not null;

    with replacement as (
        select group_record.id,
            (
                select member.user_id
                from public.shared_tag_group_members member
                where member.group_id = group_record.id
                  and member.user_id <> caller
                  and member.status = 'active'
                order by (member.role = 'owner') desc,
                         member.updated_at desc,
                         member.created_at asc,
                         member.user_id
                limit 1
            ) as user_id
        from public.shared_tag_groups group_record
        where group_record.created_by = caller
    )
    update public.shared_tag_groups group_record
       set created_by = replacement.user_id
      from replacement
     where group_record.id = replacement.id
       and replacement.user_id is not null;

    with replacement as (
        select url.id,
            coalesce(
                (
                    select member.user_id
                    from public.shared_tag_members member
                    where member.tag_id = url.tag_id
                      and member.user_id <> caller
                      and member.status = 'active'
                    order by (member.role = 'owner') desc,
                             member.updated_at desc,
                             member.created_at asc,
                             member.user_id
                    limit 1
                ),
                (
                    select member.user_id
                    from public.shared_tag_group_tags group_tag
                    join public.shared_tag_group_members member
                      on member.group_id = group_tag.group_id
                     and member.user_id <> caller
                     and member.status = 'active'
                    where group_tag.tag_id = url.tag_id
                    order by (member.role = 'owner') desc,
                             member.updated_at desc,
                             member.created_at asc,
                             member.user_id
                    limit 1
                )
            ) as user_id
        from public.shared_tag_urls url
        where url.added_by = caller
    )
    update public.shared_tag_urls url
       set added_by = replacement.user_id
      from replacement
     where url.id = replacement.id
       and replacement.user_id is not null;

    with replacement as (
        select group_tag.group_id,
               group_tag.tag_id,
            (
                select member.user_id
                from public.shared_tag_group_members member
                where member.group_id = group_tag.group_id
                  and member.user_id <> caller
                  and member.status = 'active'
                order by (member.role = 'owner') desc,
                         member.updated_at desc,
                         member.created_at asc,
                         member.user_id
                limit 1
            ) as user_id
        from public.shared_tag_group_tags group_tag
        where group_tag.added_by = caller
    )
    update public.shared_tag_group_tags group_tag
       set added_by = replacement.user_id
      from replacement
     where group_tag.group_id = replacement.group_id
       and group_tag.tag_id = replacement.tag_id
       and replacement.user_id is not null;

    with replacement as (
        select invite.token_hash,
            (
                select member.user_id
                from public.shared_tag_group_members member
                where member.group_id = invite.group_id
                  and member.user_id <> caller
                  and member.status = 'active'
                order by (member.role = 'owner') desc,
                         member.updated_at desc,
                         member.created_at asc,
                         member.user_id
                limit 1
            ) as user_id
        from public.shared_tag_group_invites invite
        where invite.created_by = caller
    )
    update public.shared_tag_group_invites invite
       set created_by = replacement.user_id
      from replacement
     where invite.token_hash = replacement.token_hash
       and replacement.user_id is not null;

    with replacement as (
        select invite.token_hash,
            coalesce(
                (
                    select member.user_id
                    from public.shared_tag_members member
                    where member.tag_id = invite.tag_id
                      and member.user_id <> caller
                      and member.status = 'active'
                    order by (member.role = 'owner') desc,
                             member.updated_at desc,
                             member.created_at asc,
                             member.user_id
                    limit 1
                ),
                (
                    select member.user_id
                    from public.shared_tag_group_tags group_tag
                    join public.shared_tag_group_members member
                      on member.group_id = group_tag.group_id
                     and member.user_id <> caller
                     and member.status = 'active'
                    where group_tag.tag_id = invite.tag_id
                    order by (member.role = 'owner') desc,
                             member.updated_at desc,
                             member.created_at asc,
                             member.user_id
                    limit 1
                )
            ) as user_id
        from public.shared_tag_invites invite
        where invite.created_by = caller
    )
    update public.shared_tag_invites invite
       set created_by = replacement.user_id
      from replacement
     where invite.token_hash = replacement.token_hash
       and replacement.user_id is not null;

    -- Remove creator/adder rows for which no active owner/member remains.
    delete from public.shared_tag_group_tags where added_by = caller;
    delete from public.shared_tag_group_invites where created_by = caller;
    delete from public.shared_tag_invites where created_by = caller;
    delete from public.shared_tag_urls where added_by = caller;
    delete from public.shared_tag_groups where created_by = caller;
    delete from public.shared_tags where created_by = caller;

    legacy_result := public.delete_my_account_legacy();

    return coalesce(legacy_result, '{}'::jsonb) || jsonb_build_object(
        'account_deletion_version', '2026-07-24-personal-link-cascade-v3',
        'personal_data_counts', jsonb_build_object(
            'personal_link_sync_settings', personal_sync_settings_count,
            'personal_saved_links', personal_saved_links_count,
            'personal_saved_link_tags', personal_saved_link_tags_count,
            'personal_saved_link_tag_refs', personal_saved_link_tag_refs_count,
            'personal_link_enrichment_cache', personal_enrichment_cache_count,
            'personal_link_applied_client_ops', personal_applied_ops_count,
            'personal_link_pending_write_actions', personal_pending_actions_count
        )
    );
end;
$$;

revoke execute on function public.delete_my_account_legacy() from public, anon, authenticated;
grant execute on function public.delete_my_account() to authenticated;

-- Keep the old per-row RPC harmless even if a stale grant is restored.  The
-- mobile clients must not enable it until begin/chunk/commit snapshot semantics
-- with stable client identity and idempotency keys are deployed.
create or replace function public.set_personal_link_chatgpt_sync(
    p_enabled boolean,
    p_content_fetch_enabled boolean
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, extensions, pg_temp
as $$
begin
    if auth.uid() is null then
        raise exception 'auth_required';
    end if;
    raise exception 'snapshot_protocol_required';
end;
$$;

create or replace function public.apply_personal_link_ops(payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, private, extensions, pg_temp
as $$
begin
    if auth.uid() is null then
        raise exception 'auth_required';
    end if;
    raise exception 'snapshot_protocol_required';
end;
$$;

revoke execute on function public.set_personal_link_chatgpt_sync(boolean, boolean) from public, anon, authenticated;
revoke execute on function public.apply_personal_link_ops(jsonb) from public, anon, authenticated;
