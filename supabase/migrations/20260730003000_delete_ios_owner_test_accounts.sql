begin;

-- One-time production cleanup for disposable iOS owner test identities.
-- Fail closed unless the complete, exact fixture set is present and unused.
do $$
declare
    target_count integer;
    admin_count integer;
    related_usage_count bigint;
    deleted_count integer;
begin
    select count(*)
      into target_count
      from auth.users auth_user
     where auth_user.email ~* '^ios-owner-[0-9a-f]{8}@example[.]com$';

    -- A replay after the successful cleanup is intentionally a no-op.
    if target_count = 0 then
        return;
    end if;
    if target_count <> 18 then
        raise exception 'ios_owner_target_count_mismatch:%', target_count;
    end if;

    select count(*)
      into admin_count
      from public.admin_users admin_user
     where admin_user.user_id in (
         select auth_user.id
           from auth.users auth_user
          where auth_user.email ~* '^ios-owner-[0-9a-f]{8}@example[.]com$'
     );
    if admin_count <> 0 then
        raise exception 'ios_owner_admin_target_detected:%', admin_count;
    end if;

    with targets as (
        select auth_user.id
          from auth.users auth_user
         where auth_user.email ~* '^ios-owner-[0-9a-f]{8}@example[.]com$'
    )
    select
        (select count(*) from public.applied_client_ops row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.personal_link_applied_client_ops row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.personal_link_enrichment_cache row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.personal_link_pending_write_actions row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.personal_link_sync_settings row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.personal_saved_link_tag_refs row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.personal_saved_link_tags row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.personal_saved_links row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.shared_content_reports row_record where row_record.reporter_user_id in (select id from targets) or row_record.reported_user_id in (select id from targets))
      + (select count(*) from public.shared_tag_group_invites row_record where row_record.created_by in (select id from targets) or row_record.claimed_by in (select id from targets))
      + (select count(*) from public.shared_tag_group_members row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.shared_tag_group_tags row_record where row_record.added_by in (select id from targets))
      + (select count(*) from public.shared_tag_groups row_record where row_record.created_by in (select id from targets))
      + (select count(*) from public.shared_tag_invites row_record where row_record.created_by in (select id from targets) or row_record.claimed_by in (select id from targets))
      + (select count(*) from public.shared_tag_members row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.shared_tag_urls row_record where row_record.added_by in (select id from targets))
      + (select count(*) from public.shared_tags row_record where row_record.created_by in (select id from targets))
      + (select count(*) from public.shared_user_profiles row_record where row_record.user_id in (select id from targets))
      + (select count(*) from public.user_blocks row_record where row_record.blocker_user_id in (select id from targets) or row_record.blocked_user_id in (select id from targets))
      + (select count(*) from private.rinbam_mcp_rate_limit_buckets row_record where row_record.user_id in (select id from targets))
      into related_usage_count;

    if related_usage_count <> 0 then
        raise exception 'ios_owner_related_usage_detected:%', related_usage_count;
    end if;

    delete from auth.users auth_user
     where auth_user.email ~* '^ios-owner-[0-9a-f]{8}@example[.]com$';
    get diagnostics deleted_count = row_count;
    if deleted_count <> 18 then
        raise exception 'ios_owner_deleted_count_mismatch:%', deleted_count;
    end if;

    if exists (
        select 1
          from auth.users auth_user
         where auth_user.email ~* '^ios-owner-[0-9a-f]{8}@example[.]com$'
    ) then
        raise exception 'ios_owner_delete_verification_failed';
    end if;
end
$$;

commit;
