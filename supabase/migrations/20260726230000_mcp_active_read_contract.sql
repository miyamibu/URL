create extension if not exists pgcrypto;

alter table public.personal_saved_links
    add column if not exists public_safe_id text;

update public.personal_saved_links
set public_safe_id = encode(extensions.gen_random_bytes(16), 'hex')
where public_safe_id is null or btrim(public_safe_id) = '';

alter table public.personal_saved_links
    alter column public_safe_id set default encode(extensions.gen_random_bytes(16), 'hex');
alter table public.personal_saved_links
    alter column public_safe_id set not null;

create unique index if not exists idx_personal_saved_links_user_public_safe_id
    on public.personal_saved_links (user_id, public_safe_id);

create index if not exists idx_personal_saved_links_mcp_active_search
    on public.personal_saved_links
    using gin (
        to_tsvector(
            'simple',
            coalesce(effective_title, '') || ' ' ||
            coalesce(memo, '') || ' ' ||
            coalesce(body_summary, '') || ' ' ||
            coalesce(description, '') || ' ' ||
            coalesce(normalized_host, '') || ' ' ||
            coalesce(collection_name, '') || ' ' ||
            coalesce(fetched_author_name, '')
        )
    )
    where deleted_at is null and disabled_at is null and record_state = 'ACTIVE';

create table if not exists private.rinbam_mcp_rate_limit_buckets (
    user_id uuid primary key,
    window_started_at timestamptz not null,
    request_count integer not null check (request_count >= 0)
);

revoke all on table private.rinbam_mcp_rate_limit_buckets from public, anon, authenticated;

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
    window_seconds integer := greatest(1, least(coalesce(p_window_seconds, 60), 3600));
    max_requests integer := greatest(1, least(coalesce(p_max_requests, 60), 1000));
    window_started timestamptz;
    request_count integer;
    now_time timestamptz := clock_timestamp();
    retry_seconds integer;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(caller::text, 0));
    select bucket.window_started_at, bucket.request_count
    into window_started, request_count
    from private.rinbam_mcp_rate_limit_buckets bucket
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

    if request_count >= max_requests then
        retry_seconds := greatest(
            1,
            ceil(extract(epoch from (window_started + make_interval(secs => window_seconds) - now_time)))::integer
        );
        return query select false, retry_seconds, 0;
        return;
    end if;

    update private.rinbam_mcp_rate_limit_buckets
    set request_count = request_count + 1
    where user_id = caller;
    return query select true, 0, max_requests - request_count - 1;
end;
$$;

revoke all on function public.consume_rinbam_mcp_rate_limit(integer, integer) from public, anon;
grant execute on function public.consume_rinbam_mcp_rate_limit(integer, integer) to authenticated;

create or replace function public.mcp_search_active_personal_saved_links(
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
from public.personal_saved_links link
cross join input
left join public.personal_saved_link_tag_refs ref
    on ref.link_id = link.id
   and ref.user_id = auth.uid()
   and ref.deleted_at is null
left join public.personal_saved_link_tags tag
    on tag.id = ref.tag_id
   and tag.user_id = auth.uid()
   and tag.deleted_at is null
where link.user_id = auth.uid()
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
          from public.personal_saved_link_tag_refs tag_ref
          join public.personal_saved_link_tags search_tag on search_tag.id = tag_ref.tag_id
          where tag_ref.link_id = link.id
            and tag_ref.user_id = auth.uid()
            and tag_ref.deleted_at is null
            and search_tag.user_id = auth.uid()
            and search_tag.deleted_at is null
            and search_tag.name ilike '%' || input.query_text || '%'
      )
  )
group by link.id, input.query_text, input.result_limit
order by link.source_updated_at desc, link.id asc
limit (select result_limit from input);
$$;

revoke all on function public.mcp_search_active_personal_saved_links(text, integer) from public, anon;
grant execute on function public.mcp_search_active_personal_saved_links(text, integer) to authenticated;

create or replace function public.mcp_list_active_personal_link_tags()
returns table(tag_id uuid, name text)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
select distinct tag.id, tag.name
from public.personal_saved_link_tags tag
join public.personal_saved_link_tag_refs ref
    on ref.tag_id = tag.id
   and ref.user_id = auth.uid()
   and ref.deleted_at is null
join public.personal_saved_links link
    on link.id = ref.link_id
   and link.user_id = auth.uid()
   and link.deleted_at is null
   and link.disabled_at is null
   and link.record_state = 'ACTIVE'
   and link.record_state <> 'PENDING_DELETE'
where tag.user_id = auth.uid()
  and tag.deleted_at is null
order by tag.name asc, tag.id asc;
$$;

revoke all on function public.mcp_list_active_personal_link_tags() from public, anon;
grant execute on function public.mcp_list_active_personal_link_tags() to authenticated;
