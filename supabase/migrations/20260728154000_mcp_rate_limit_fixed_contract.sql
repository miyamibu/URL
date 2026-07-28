-- Keep the public RPC signature compatible with the already-deployed MCP
-- contract, but make the security boundary server-owned. Client-supplied
-- arguments are intentionally ignored and can never relax the bucket.

create or replace function public.consume_rinbam_mcp_rate_limit(
    p_window_seconds integer default 60,
    p_max_requests integer default 60
)
returns table(allowed boolean, retry_after_seconds integer, remaining integer)
language plpgsql
security definer
set search_path = public, private, extensions, pg_temp
as $$
declare
    caller uuid := auth.uid();
    window_seconds constant integer := 60;
    max_requests constant integer := 60;
    window_started timestamptz;
    current_request_count integer;
    now_time timestamptz := clock_timestamp();
    retry_seconds integer;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(caller::text, 0));
    select bucket.window_started_at, bucket.request_count
    into window_started, current_request_count
    from private.rinbam_mcp_rate_limit_buckets as bucket
    where bucket.user_id = caller
    for update;

    if not found or now_time >= window_started + make_interval(secs => window_seconds) then
        insert into private.rinbam_mcp_rate_limit_buckets (user_id, window_started_at, request_count)
        values (caller, now_time, 1)
        on conflict (user_id) do update
        set window_started_at = excluded.window_started_at,
            request_count = excluded.request_count;
        return query select true, 0, max_requests - 1;
        return;
    end if;

    if current_request_count >= max_requests then
        retry_seconds := greatest(
            1,
            ceil(extract(epoch from (window_started + make_interval(secs => window_seconds) - now_time)))::integer
        );
        return query select false, retry_seconds, 0;
        return;
    end if;

    update private.rinbam_mcp_rate_limit_buckets as bucket
    set request_count = bucket.request_count + 1
    where bucket.user_id = caller;
    return query select true, 0, max_requests - current_request_count - 1;
end;
$$;

revoke all on function public.consume_rinbam_mcp_rate_limit(integer, integer) from public, anon;
grant execute on function public.consume_rinbam_mcp_rate_limit(integer, integer) to authenticated;

comment on function public.consume_rinbam_mcp_rate_limit(integer, integer) is
    'MCP per-user fixed 60 requests per 60 seconds. Legacy arguments are ignored and cannot relax the limit.';

-- The web MCP resource server verifies the incoming bearer through Supabase
-- Auth, but never forwards it to PostgREST. Only the service role can call the
-- following functions, and every data path requires the verified user id.

create or replace function public.consume_rinbam_mcp_rate_limit_for_user(
    p_user_id uuid
)
returns table(allowed boolean, retry_after_seconds integer, remaining integer)
language plpgsql
security definer
set search_path = public, private, extensions, pg_temp
as $$
declare
    window_seconds constant integer := 60;
    max_requests constant integer := 60;
    window_started timestamptz;
    current_request_count integer;
    now_time timestamptz := clock_timestamp();
    retry_seconds integer;
begin
    if p_user_id is null then
        raise exception 'user_id_required';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(p_user_id::text, 0));
    select bucket.window_started_at, bucket.request_count
    into window_started, current_request_count
    from private.rinbam_mcp_rate_limit_buckets as bucket
    where bucket.user_id = p_user_id
    for update;

    if not found or now_time >= window_started + make_interval(secs => window_seconds) then
        insert into private.rinbam_mcp_rate_limit_buckets (user_id, window_started_at, request_count)
        values (p_user_id, now_time, 1)
        on conflict (user_id) do update
        set window_started_at = excluded.window_started_at,
            request_count = excluded.request_count;
        return query select true, 0, max_requests - 1;
        return;
    end if;

    if current_request_count >= max_requests then
        retry_seconds := greatest(
            1,
            ceil(extract(epoch from (window_started + make_interval(secs => window_seconds) - now_time)))::integer
        );
        return query select false, retry_seconds, 0;
        return;
    end if;

    update private.rinbam_mcp_rate_limit_buckets as bucket
    set request_count = bucket.request_count + 1
    where bucket.user_id = p_user_id;
    return query select true, 0, max_requests - current_request_count - 1;
end;
$$;

revoke all on function public.consume_rinbam_mcp_rate_limit_for_user(uuid)
    from public, anon, authenticated;
grant execute on function public.consume_rinbam_mcp_rate_limit_for_user(uuid)
    to service_role;

create or replace function public.mcp_search_active_personal_saved_links_for_user(
    p_user_id uuid,
    p_query text,
    p_result_limit integer default 10
)
returns table(
    public_safe_id text,
    title text,
    url text,
    snippet text,
    metadata jsonb,
    tag_names text[]
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
with input as (
    select left(btrim(coalesce(p_query, '')), 500) as query_text,
           least(greatest(coalesce(p_result_limit, 10), 1), 20) as result_limit
)
select
    link.public_safe_id,
    coalesce(nullif(link.effective_title, ''), nullif(link.normalized_host, ''), '保存したリンク') as title,
    coalesce(nullif(link.open_url, ''), link.normalized_url) as url,
    left(nullif(concat_ws(' ', link.body_summary, link.description, link.memo), ''), 600) as snippet,
    jsonb_build_object(
        'bodyKind', link.fetched_body_kind,
        'author', link.fetched_author_name,
        'serviceType', link.service_type,
        'sourceCreatedAt', link.source_created_at,
        'sourceUpdatedAt', link.source_updated_at
    ) as metadata,
    coalesce(array_agg(distinct tag.name) filter (where tag.id is not null), array[]::text[]) as tag_names
from public.personal_saved_links as link
cross join input
left join public.personal_saved_link_tag_refs as ref
    on ref.link_id = link.id
   and ref.user_id = p_user_id
   and ref.deleted_at is null
left join public.personal_saved_link_tags as tag
    on tag.id = ref.tag_id
   and tag.user_id = p_user_id
   and tag.deleted_at is null
where p_user_id is not null
  and link.user_id = p_user_id
  and link.deleted_at is null
  and link.disabled_at is null
  and link.record_state = 'ACTIVE'
  and link.record_state <> 'PENDING_DELETE'
  and (
      input.query_text = ''
      or to_tsvector(
          'simple',
          coalesce(link.effective_title, '') || ' ' ||
          coalesce(link.memo, '') || ' ' ||
          coalesce(link.body_summary, '') || ' ' ||
          coalesce(link.description, '') || ' ' ||
          coalesce(link.normalized_host, '') || ' ' ||
          coalesce(link.collection_name, '') || ' ' ||
          coalesce(link.fetched_author_name, '')
      ) @@ plainto_tsquery('simple', input.query_text)
      or exists (
          select 1
          from public.personal_saved_link_tag_refs as tag_ref
          join public.personal_saved_link_tags as search_tag
            on search_tag.id = tag_ref.tag_id
          where tag_ref.link_id = link.id
            and tag_ref.user_id = p_user_id
            and tag_ref.deleted_at is null
            and search_tag.user_id = p_user_id
            and search_tag.deleted_at is null
            and search_tag.name ilike '%' || input.query_text || '%'
      )
  )
group by link.id, input.query_text, input.result_limit
order by link.source_updated_at desc, link.id asc
limit (select result_limit from input);
$$;

revoke all on function public.mcp_search_active_personal_saved_links_for_user(uuid, text, integer)
    from public, anon, authenticated;
grant execute on function public.mcp_search_active_personal_saved_links_for_user(uuid, text, integer)
    to service_role;

create or replace function public.mcp_list_active_personal_link_tags_for_user(
    p_user_id uuid
)
returns table(tag_id uuid, name text)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
select distinct tag.id, tag.name
from public.personal_saved_link_tags as tag
join public.personal_saved_link_tag_refs as ref
    on ref.tag_id = tag.id
   and ref.user_id = p_user_id
   and ref.deleted_at is null
join public.personal_saved_links as link
    on link.id = ref.link_id
   and link.user_id = p_user_id
   and link.deleted_at is null
   and link.disabled_at is null
   and link.record_state = 'ACTIVE'
   and link.record_state <> 'PENDING_DELETE'
where p_user_id is not null
  and tag.user_id = p_user_id
  and tag.deleted_at is null
order by tag.name asc, tag.id asc;
$$;

revoke all on function public.mcp_list_active_personal_link_tags_for_user(uuid)
    from public, anon, authenticated;
grant execute on function public.mcp_list_active_personal_link_tags_for_user(uuid)
    to service_role;
