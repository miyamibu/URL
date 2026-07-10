create extension if not exists pgcrypto;
create schema if not exists private;
create or replace function private.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;
create or replace function private.normalize_personal_link_tag_name(raw_name text)
returns text
language sql
immutable
as $$
    select nullif(lower(regexp_replace(btrim(coalesce(raw_name, '')), '\s+', ' ', 'g')), '')
$$;
create table if not exists public.personal_link_sync_settings (
    user_id uuid primary key,
    enabled boolean not null default false,
    content_fetch_enabled boolean not null default false,
    disabled_at timestamptz null,
    purge_after timestamptz null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create table if not exists public.personal_saved_links (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    client_entry_id text not null,
    original_url text not null,
    normalized_url text not null,
    display_url text not null,
    open_url text not null,
    effective_title text not null,
    user_title text null,
    fetched_title text null,
    fetched_author_name text null,
    fetched_body_kind text null,
    fetched_body text null,
    body_summary text null,
    description text null,
    memo text not null default '',
    thumbnail_url text null,
    badge_image_url text null,
    canonical_id text null,
    normalized_host text not null,
    raw_source_host text not null,
    service_type text not null,
    content_context text not null,
    record_state text not null check (record_state in ('ACTIVE', 'ARCHIVED')),
    collection_name text null,
    metadata_state text null,
    metadata_error text null,
    source_created_at timestamptz not null,
    source_updated_at timestamptz not null,
    archived_at timestamptz null,
    content_fetch_allowed boolean not null default false,
    disabled_at timestamptz null,
    deleted_at timestamptz null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, client_entry_id),
    unique (user_id, normalized_url)
);
create table if not exists public.personal_saved_link_tags (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    name text not null check (char_length(btrim(name)) between 1 and 50),
    normalized_name text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz null,
    unique (user_id, normalized_name)
);
create table if not exists public.personal_saved_link_tag_refs (
    link_id uuid not null references public.personal_saved_links(id) on delete cascade,
    tag_id uuid not null references public.personal_saved_link_tags(id) on delete cascade,
    user_id uuid not null,
    created_at timestamptz not null default now(),
    deleted_at timestamptz null,
    primary key (link_id, tag_id)
);
create table if not exists public.personal_link_enrichment_cache (
    link_id uuid primary key references public.personal_saved_links(id) on delete cascade,
    user_id uuid not null,
    fetch_state text not null check (fetch_state in ('PENDING', 'READY', 'UNAVAILABLE', 'FAILED')),
    fetched_text text null,
    fetched_text_summary text null,
    failure_reason text null,
    fetched_at timestamptz null,
    next_retry_at timestamptz null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create table if not exists public.personal_link_applied_client_ops (
    op_id uuid primary key,
    user_id uuid not null,
    applied_at timestamptz not null default now(),
    result jsonb null
);
create table if not exists public.personal_link_pending_write_actions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    action_type text not null check (action_type in ('save_link', 'create_tag', 'rename_tag', 'assign_tags', 'remove_tags')),
    summary text not null check (char_length(btrim(summary)) between 1 and 500),
    payload jsonb not null,
    confirmation_token_hash text not null,
    expires_at timestamptz not null,
    committed_at timestamptz null,
    canceled_at timestamptz null,
    result jsonb null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists idx_personal_saved_links_user_active
    on public.personal_saved_links (user_id, source_updated_at desc)
    where deleted_at is null and disabled_at is null;
create index if not exists idx_personal_saved_links_search
    on public.personal_saved_links
    using gin (
        to_tsvector(
            'simple',
            coalesce(effective_title, '') || ' ' ||
            coalesce(memo, '') || ' ' ||
            coalesce(body_summary, '') || ' ' ||
            coalesce(description, '') || ' ' ||
            coalesce(fetched_body, '') || ' ' ||
            coalesce(normalized_host, '') || ' ' ||
            coalesce(collection_name, '')
        )
    )
    where deleted_at is null and disabled_at is null;
create index if not exists idx_personal_link_tag_refs_user
    on public.personal_saved_link_tag_refs (user_id, tag_id)
    where deleted_at is null;
create index if not exists idx_personal_link_pending_write_actions_user_active
    on public.personal_link_pending_write_actions (user_id, expires_at desc)
    where committed_at is null and canceled_at is null;
drop trigger if exists trg_personal_link_sync_settings_updated_at on public.personal_link_sync_settings;
create trigger trg_personal_link_sync_settings_updated_at
before update on public.personal_link_sync_settings
for each row execute function private.set_updated_at();
drop trigger if exists trg_personal_saved_links_updated_at on public.personal_saved_links;
create trigger trg_personal_saved_links_updated_at
before update on public.personal_saved_links
for each row execute function private.set_updated_at();
drop trigger if exists trg_personal_saved_link_tags_updated_at on public.personal_saved_link_tags;
create trigger trg_personal_saved_link_tags_updated_at
before update on public.personal_saved_link_tags
for each row execute function private.set_updated_at();
drop trigger if exists trg_personal_link_enrichment_cache_updated_at on public.personal_link_enrichment_cache;
create trigger trg_personal_link_enrichment_cache_updated_at
before update on public.personal_link_enrichment_cache
for each row execute function private.set_updated_at();
drop trigger if exists trg_personal_link_pending_write_actions_updated_at on public.personal_link_pending_write_actions;
create trigger trg_personal_link_pending_write_actions_updated_at
before update on public.personal_link_pending_write_actions
for each row execute function private.set_updated_at();
alter table public.personal_link_sync_settings enable row level security;
alter table public.personal_saved_links enable row level security;
alter table public.personal_saved_link_tags enable row level security;
alter table public.personal_saved_link_tag_refs enable row level security;
alter table public.personal_link_enrichment_cache enable row level security;
alter table public.personal_link_applied_client_ops enable row level security;
alter table public.personal_link_pending_write_actions enable row level security;
drop policy if exists personal_link_sync_settings_own_select on public.personal_link_sync_settings;
create policy personal_link_sync_settings_own_select
on public.personal_link_sync_settings for select
to authenticated
using (user_id = auth.uid());
drop policy if exists personal_saved_links_own_select on public.personal_saved_links;
create policy personal_saved_links_own_select
on public.personal_saved_links for select
to authenticated
using (user_id = auth.uid());
drop policy if exists personal_saved_link_tags_own_select on public.personal_saved_link_tags;
create policy personal_saved_link_tags_own_select
on public.personal_saved_link_tags for select
to authenticated
using (user_id = auth.uid());
drop policy if exists personal_saved_link_tag_refs_own_select on public.personal_saved_link_tag_refs;
create policy personal_saved_link_tag_refs_own_select
on public.personal_saved_link_tag_refs for select
to authenticated
using (user_id = auth.uid());
drop policy if exists personal_link_enrichment_cache_own_select on public.personal_link_enrichment_cache;
create policy personal_link_enrichment_cache_own_select
on public.personal_link_enrichment_cache for select
to authenticated
using (user_id = auth.uid());
drop policy if exists personal_link_applied_client_ops_own_select on public.personal_link_applied_client_ops;
create policy personal_link_applied_client_ops_own_select
on public.personal_link_applied_client_ops for select
to authenticated
using (user_id = auth.uid());
drop policy if exists personal_link_pending_write_actions_own_select on public.personal_link_pending_write_actions;
create policy personal_link_pending_write_actions_own_select
on public.personal_link_pending_write_actions for select
to authenticated
using (user_id = auth.uid());
revoke all on table public.personal_link_sync_settings from public, anon, authenticated;
revoke all on table public.personal_saved_links from public, anon, authenticated;
revoke all on table public.personal_saved_link_tags from public, anon, authenticated;
revoke all on table public.personal_saved_link_tag_refs from public, anon, authenticated;
revoke all on table public.personal_link_enrichment_cache from public, anon, authenticated;
revoke all on table public.personal_link_applied_client_ops from public, anon, authenticated;
revoke all on table public.personal_link_pending_write_actions from public, anon, authenticated;
grant select on table public.personal_link_sync_settings to authenticated;
grant select on table public.personal_saved_links to authenticated;
grant select on table public.personal_saved_link_tags to authenticated;
grant select on table public.personal_saved_link_tag_refs to authenticated;
grant select on table public.personal_link_enrichment_cache to authenticated;
grant select on table public.personal_link_applied_client_ops to authenticated;
grant select on table public.personal_link_pending_write_actions to authenticated;
create or replace function public.set_personal_link_chatgpt_sync(
    p_enabled boolean,
    p_content_fetch_enabled boolean
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, extensions, pg_temp
as $$
declare
    caller uuid := auth.uid();
    disabled_time timestamptz := case when p_enabled then null else now() end;
    purge_time timestamptz := case when p_enabled then null else now() + interval '30 days' end;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    insert into public.personal_link_sync_settings (
        user_id,
        enabled,
        content_fetch_enabled,
        disabled_at,
        purge_after
    )
    values (
        caller,
        p_enabled,
        p_enabled and p_content_fetch_enabled,
        disabled_time,
        purge_time
    )
    on conflict (user_id) do update
    set enabled = excluded.enabled,
        content_fetch_enabled = excluded.content_fetch_enabled,
        disabled_at = excluded.disabled_at,
        purge_after = excluded.purge_after;

    if not p_enabled then
        update public.personal_saved_links
        set disabled_at = now()
        where user_id = caller
          and disabled_at is null
          and deleted_at is null;
    else
        update public.personal_saved_links
        set disabled_at = null
        where user_id = caller
          and deleted_at is null;
    end if;

    return jsonb_build_object(
        'status', 'ok',
        'enabled', p_enabled,
        'content_fetch_enabled', p_enabled and p_content_fetch_enabled,
        'purge_after', purge_time
    );
end;
$$;
create or replace function public.apply_personal_link_ops(payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, private, extensions, pg_temp
as $$
declare
    caller uuid := auth.uid();
    sync_enabled boolean;
    op jsonb;
    v_op_id uuid;
    v_type text;
    v_link_id uuid;
    v_tag_id uuid;
    v_tag_name text;
    v_tag_key text;
    stored_result jsonb;
    result_item jsonb;
    results jsonb := '[]'::jsonb;
    seen_client_entry_ids text[] := array[]::text[];
    v_client_entry_id text;
    v_client_prefix text;
    tag_item jsonb;
    seen_tag_ids uuid[];
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    select coalesce(settings.enabled, false)
    into sync_enabled
    from public.personal_link_sync_settings settings
    where settings.user_id = caller;

    if not coalesce(sync_enabled, false) then
        raise exception 'chatgpt_sync_disabled';
    end if;

    if jsonb_typeof(payload) = 'object' and payload ? 'ops' then
        payload := payload -> 'ops';
    end if;

    if jsonb_typeof(payload) <> 'array' then
        raise exception 'payload_must_be_array';
    end if;

    for op in select value from jsonb_array_elements(payload)
    loop
        v_op_id := (op ->> 'op_id')::uuid;
        v_type := coalesce(op ->> 'type', op ->> 'operation');

        select applied.result
        into stored_result
        from public.personal_link_applied_client_ops applied
        where applied.op_id = v_op_id
          and applied.user_id = caller;

        if stored_result is not null then
            results := results || stored_result;
            continue;
        end if;

        if v_type = 'upsert_link' then
            if coalesce(op ->> 'record_state', 'ACTIVE') not in ('ACTIVE', 'ARCHIVED') then
                result_item := jsonb_build_object('op_id', v_op_id, 'status', 'skipped', 'reason', 'record_state_excluded');
            else
                v_link_id := coalesce(nullif(op ->> 'link_id', '')::uuid, gen_random_uuid());
                v_client_entry_id := coalesce(nullif(op ->> 'client_entry_id', ''), v_link_id::text);
                seen_client_entry_ids := array_append(seen_client_entry_ids, v_client_entry_id);
                insert into public.personal_saved_links (
                    id,
                    user_id,
                    client_entry_id,
                    original_url,
                    normalized_url,
                    display_url,
                    open_url,
                    effective_title,
                    user_title,
                    fetched_title,
                    fetched_author_name,
                    fetched_body_kind,
                    fetched_body,
                    body_summary,
                    description,
                    memo,
                    thumbnail_url,
                    badge_image_url,
                    canonical_id,
                    normalized_host,
                    raw_source_host,
                    service_type,
                    content_context,
                    record_state,
                    collection_name,
                    metadata_state,
                    metadata_error,
                    source_created_at,
                    source_updated_at,
                    archived_at,
                    content_fetch_allowed,
                    disabled_at,
                    deleted_at
                )
                values (
                    v_link_id,
                    caller,
                    v_client_entry_id,
                    coalesce(op ->> 'original_url', op ->> 'url'),
                    coalesce(op ->> 'normalized_url', op ->> 'url'),
                    coalesce(op ->> 'display_url', op ->> 'normalized_url', op ->> 'url'),
                    coalesce(op ->> 'open_url', op ->> 'normalized_url', op ->> 'url'),
                    coalesce(nullif(op ->> 'effective_title', ''), nullif(op ->> 'title', ''), op ->> 'normalized_host', '保存したリンク'),
                    nullif(op ->> 'user_title', ''),
                    coalesce(nullif(op ->> 'fetched_title', ''), nullif(op ->> 'title', '')),
                    coalesce(nullif(op ->> 'fetched_author_name', ''), nullif(op #>> '{metadata,author_name}', '')),
                    nullif(op ->> 'fetched_body_kind', ''),
                    coalesce(nullif(op ->> 'fetched_body', ''), nullif(op ->> 'extracted_text', '')),
                    coalesce(nullif(op ->> 'body_summary', ''), nullif(op #>> '{metadata,body_summary}', '')),
                    coalesce(nullif(op ->> 'description', ''), nullif(op #>> '{metadata,description}', '')),
                    coalesce(op ->> 'memo', ''),
                    coalesce(nullif(op ->> 'thumbnail_url', ''), nullif(op #>> '{metadata,thumbnail_url}', '')),
                    coalesce(nullif(op ->> 'badge_image_url', ''), nullif(op #>> '{metadata,badge_image_url}', '')),
                    coalesce(nullif(op ->> 'canonical_id', ''), nullif(op #>> '{metadata,canonical_id}', '')),
                    coalesce(nullif(op ->> 'normalized_host', ''), nullif(op #>> '{metadata,normalized_host}', ''), ''),
                    coalesce(nullif(op ->> 'raw_source_host', ''), nullif(op #>> '{metadata,raw_source_host}', ''), ''),
                    coalesce(nullif(op ->> 'service_type', ''), nullif(upper(op #>> '{metadata,service_type}'), ''), 'WEB'),
                    coalesce(nullif(op ->> 'content_context', ''), 'WEB_PAGE'),
                    coalesce(op ->> 'record_state', case when coalesce((op ->> 'is_archived')::boolean, false) then 'ARCHIVED' else 'ACTIVE' end),
                    coalesce(nullif(op ->> 'collection_name', ''), nullif(op ->> 'collection', '')),
                    nullif(op ->> 'metadata_state', ''),
                    nullif(op ->> 'metadata_error', ''),
                    coalesce(nullif(op ->> 'source_created_at', '')::timestamptz, now()),
                    coalesce(nullif(op ->> 'source_updated_at', '')::timestamptz, nullif(op ->> 'updated_at', '')::timestamptz, now()),
                    nullif(op ->> 'archived_at', '')::timestamptz,
                    coalesce((op ->> 'content_fetch_allowed')::boolean, nullif(op ->> 'extracted_text', '') is not null, false),
                    null,
                    null
                )
                on conflict (user_id, client_entry_id) do update
                set original_url = excluded.original_url,
                    normalized_url = excluded.normalized_url,
                    display_url = excluded.display_url,
                    open_url = excluded.open_url,
                    effective_title = excluded.effective_title,
                    user_title = excluded.user_title,
                    fetched_title = excluded.fetched_title,
                    fetched_author_name = excluded.fetched_author_name,
                    fetched_body_kind = excluded.fetched_body_kind,
                    fetched_body = excluded.fetched_body,
                    body_summary = excluded.body_summary,
                    description = excluded.description,
                    memo = excluded.memo,
                    thumbnail_url = excluded.thumbnail_url,
                    badge_image_url = excluded.badge_image_url,
                    canonical_id = excluded.canonical_id,
                    normalized_host = excluded.normalized_host,
                    raw_source_host = excluded.raw_source_host,
                    service_type = excluded.service_type,
                    content_context = excluded.content_context,
                    record_state = excluded.record_state,
                    collection_name = excluded.collection_name,
                    metadata_state = excluded.metadata_state,
                    metadata_error = excluded.metadata_error,
                    source_created_at = excluded.source_created_at,
                    source_updated_at = excluded.source_updated_at,
                    archived_at = excluded.archived_at,
                    content_fetch_allowed = excluded.content_fetch_allowed,
                    disabled_at = null,
                    deleted_at = null
                returning id into v_link_id;

                if jsonb_typeof(op -> 'tags') = 'array' then
                    seen_tag_ids := array[]::uuid[];
                    for tag_item in select value from jsonb_array_elements(op -> 'tags')
                    loop
                        v_tag_name := btrim(tag_item #>> '{}');
                        v_tag_key := private.normalize_personal_link_tag_name(v_tag_name);
                        if v_tag_key is not null then
                            insert into public.personal_saved_link_tags (id, user_id, name, normalized_name, deleted_at)
                            values (gen_random_uuid(), caller, v_tag_name, v_tag_key, null)
                            on conflict (user_id, normalized_name) do update
                            set name = excluded.name,
                                deleted_at = null
                            returning id into v_tag_id;
                            seen_tag_ids := array_append(seen_tag_ids, v_tag_id);
                            insert into public.personal_saved_link_tag_refs (link_id, tag_id, user_id, deleted_at)
                            values (v_link_id, v_tag_id, caller, null)
                            on conflict (link_id, tag_id) do update
                            set deleted_at = null;
                        end if;
                    end loop;

                    update public.personal_saved_link_tag_refs
                    set deleted_at = now()
                    where link_id = v_link_id
                      and user_id = caller
                      and deleted_at is null
                      and not (tag_id = any(coalesce(seen_tag_ids, array[]::uuid[])));
                end if;

                result_item := jsonb_build_object('op_id', v_op_id, 'status', 'ok', 'link_id', v_link_id);
            end if;
        elsif v_type = 'upsert_tag' then
            v_tag_name := btrim(op ->> 'name');
            v_tag_key := private.normalize_personal_link_tag_name(v_tag_name);
            if v_tag_key is null then
                raise exception 'invalid_tag_name';
            end if;
            v_tag_id := coalesce(nullif(op ->> 'tag_id', '')::uuid, gen_random_uuid());
            insert into public.personal_saved_link_tags (id, user_id, name, normalized_name, deleted_at)
            values (v_tag_id, caller, v_tag_name, v_tag_key, null)
            on conflict (user_id, normalized_name) do update
            set name = excluded.name,
                deleted_at = null
            returning id into v_tag_id;
            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'ok', 'tag_id', v_tag_id);
        elsif v_type = 'rename_tag' then
            v_tag_name := btrim(op ->> 'name');
            v_tag_key := private.normalize_personal_link_tag_name(v_tag_name);
            if v_tag_key is null then
                raise exception 'invalid_tag_name';
            end if;
            update public.personal_saved_link_tags
            set name = v_tag_name,
                normalized_name = v_tag_key
            where id = (op ->> 'tag_id')::uuid
              and user_id = caller
              and deleted_at is null
            returning id into v_tag_id;
            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'ok', 'tag_id', v_tag_id);
        elsif v_type = 'assign_tag' then
            v_link_id := (op ->> 'link_id')::uuid;
            v_tag_id := (op ->> 'tag_id')::uuid;
            if not exists (select 1 from public.personal_saved_links where id = v_link_id and user_id = caller and deleted_at is null) then
                raise exception 'link_not_found';
            end if;
            if not exists (select 1 from public.personal_saved_link_tags where id = v_tag_id and user_id = caller and deleted_at is null) then
                raise exception 'tag_not_found';
            end if;
            insert into public.personal_saved_link_tag_refs (link_id, tag_id, user_id, deleted_at)
            values (v_link_id, v_tag_id, caller, null)
            on conflict (link_id, tag_id) do update
            set deleted_at = null;
            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'ok', 'link_id', v_link_id, 'tag_id', v_tag_id);
        elsif v_type = 'remove_tag' then
            update public.personal_saved_link_tag_refs
            set deleted_at = now()
            where link_id = (op ->> 'link_id')::uuid
              and tag_id = (op ->> 'tag_id')::uuid
              and user_id = caller;
            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'ok', 'link_id', op ->> 'link_id', 'tag_id', op ->> 'tag_id');
        else
            raise exception 'unsupported_personal_link_op:%', v_type;
        end if;

        insert into public.personal_link_applied_client_ops (op_id, user_id, result)
        values (v_op_id, caller, result_item);
        results := results || result_item;
    end loop;

    if array_length(seen_client_entry_ids, 1) is not null then
        foreach v_client_entry_id in array seen_client_entry_ids
        loop
            v_client_prefix := split_part(v_client_entry_id, ':', 1) || ':%';
            update public.personal_saved_links
            set deleted_at = now()
            where user_id = caller
              and client_entry_id like v_client_prefix
              and deleted_at is null
              and not (client_entry_id = any(seen_client_entry_ids));
        end loop;
    end if;

    return jsonb_build_object('results', results);
end;
$$;
create or replace function public.search_personal_saved_links(
    query text,
    result_limit integer default 10
)
returns table (
    id uuid,
    title text,
    url text,
    snippet text,
    metadata jsonb
)
language sql
stable
security definer
set search_path = public, private, pg_temp
as $$
    with caller as (
        select auth.uid() as user_id
    ),
    q as (
        select
            nullif(websearch_to_tsquery('simple', coalesce(query, ''))::text, '') as tsq_text,
            websearch_to_tsquery('simple', coalesce(query, '')) as tsq
    )
    select
        link.id,
        link.effective_title as title,
        link.open_url as url,
        left(
            concat_ws(
                ' ',
                nullif(link.memo, ''),
                nullif(link.body_summary, ''),
                nullif(link.description, ''),
                nullif(link.fetched_body, ''),
                link.normalized_host
            ),
            500
        ) as snippet,
        jsonb_build_object(
            'service_type', link.service_type,
            'record_state', link.record_state,
            'collection', link.collection_name,
            'tags', coalesce(
                (
                    select jsonb_agg(tag.name order by tag.name)
                    from public.personal_saved_link_tag_refs ref
                    join public.personal_saved_link_tags tag on tag.id = ref.tag_id
                    where ref.link_id = link.id
                      and ref.user_id = link.user_id
                      and ref.deleted_at is null
                      and tag.deleted_at is null
                ),
                '[]'::jsonb
            ),
            'metadata_state', link.metadata_state,
            'source_updated_at', link.source_updated_at
        ) as metadata
    from public.personal_saved_links link
    cross join caller
    cross join q
    where caller.user_id is not null
      and link.user_id = caller.user_id
      and link.deleted_at is null
      and link.disabled_at is null
      and (
          coalesce(query, '') = ''
          or to_tsvector(
              'simple',
              coalesce(link.effective_title, '') || ' ' ||
              coalesce(link.memo, '') || ' ' ||
              coalesce(link.body_summary, '') || ' ' ||
              coalesce(link.description, '') || ' ' ||
              coalesce(link.fetched_body, '') || ' ' ||
              coalesce(link.normalized_host, '') || ' ' ||
              coalesce(link.collection_name, '')
          ) @@ q.tsq
          or link.normalized_url ilike '%' || query || '%'
          or link.effective_title ilike '%' || query || '%'
          or link.memo ilike '%' || query || '%'
          or link.body_summary ilike '%' || query || '%'
          or link.description ilike '%' || query || '%'
          or link.fetched_body ilike '%' || query || '%'
          or link.collection_name ilike '%' || query || '%'
          or exists (
              select 1
              from public.personal_saved_link_tag_refs ref
              join public.personal_saved_link_tags tag on tag.id = ref.tag_id
              where ref.link_id = link.id
                and ref.user_id = link.user_id
                and ref.deleted_at is null
                and tag.deleted_at is null
                and tag.name ilike '%' || query || '%'
          )
      )
    order by
        link.source_updated_at desc,
        link.created_at desc
    limit least(greatest(coalesce(result_limit, 10), 1), 20)
$$;
create or replace function public.prepare_personal_link_write_action(
    p_action_type text,
    p_summary text,
    p_payload jsonb,
    p_expires_in_minutes integer default 15
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, extensions, pg_temp
as $$
declare
    caller uuid := auth.uid();
    sync_enabled boolean;
    v_action_id uuid := gen_random_uuid();
    v_token text := upper(replace(gen_random_uuid()::text, '-', ''));
    v_expires_at timestamptz;
    v_op jsonb;
    v_op_type text;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    select coalesce(settings.enabled, false)
    into sync_enabled
    from public.personal_link_sync_settings settings
    where settings.user_id = caller;

    if not coalesce(sync_enabled, false) then
        raise exception 'chatgpt_sync_disabled';
    end if;

    if p_action_type not in ('save_link', 'create_tag', 'rename_tag', 'assign_tags', 'remove_tags') then
        raise exception 'unsupported_write_action:%', p_action_type;
    end if;

    if nullif(btrim(coalesce(p_summary, '')), '') is null or char_length(btrim(p_summary)) > 500 then
        raise exception 'invalid_write_summary';
    end if;

    if jsonb_typeof(p_payload) <> 'array' or jsonb_array_length(p_payload) = 0 then
        raise exception 'payload_must_be_nonempty_array';
    end if;

    for v_op in select value from jsonb_array_elements(p_payload)
    loop
        v_op_type := v_op ->> 'type';
        if v_op_type not in ('upsert_link', 'upsert_tag', 'rename_tag', 'assign_tag', 'remove_tag') then
            raise exception 'unsupported_personal_link_op:%', v_op_type;
        end if;
    end loop;

    v_expires_at := now() + make_interval(mins => least(greatest(coalesce(p_expires_in_minutes, 15), 1), 60));

    insert into public.personal_link_pending_write_actions (
        id,
        user_id,
        action_type,
        summary,
        payload,
        confirmation_token_hash,
        expires_at
    )
    values (
        v_action_id,
        caller,
        p_action_type,
        btrim(p_summary),
        p_payload,
        encode(digest(v_token, 'sha256'), 'hex'),
        v_expires_at
    );

    return jsonb_build_object(
        'status', 'confirmation_required',
        'action_id', v_action_id,
        'action_type', p_action_type,
        'summary', btrim(p_summary),
        'expires_at', v_expires_at,
        'confirmation_token', v_token
    );
end;
$$;
create or replace function public.commit_personal_link_write_action(
    p_action_id uuid,
    p_confirmation_token text
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, extensions, pg_temp
as $$
declare
    caller uuid := auth.uid();
    pending record;
    v_result jsonb;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    select *
    into pending
    from public.personal_link_pending_write_actions action
    where action.id = p_action_id
      and action.user_id = caller
    for update;

    if not found then
        raise exception 'pending_write_not_found';
    end if;

    if pending.committed_at is not null then
        return jsonb_build_object(
            'status', 'committed',
            'action_id', pending.id,
            'action_type', pending.action_type,
            'result', pending.result
        );
    end if;

    if pending.canceled_at is not null then
        raise exception 'pending_write_canceled';
    end if;

    if pending.expires_at <= now() then
        raise exception 'pending_write_expired';
    end if;

    if encode(digest(coalesce(p_confirmation_token, ''), 'sha256'), 'hex') <> pending.confirmation_token_hash then
        raise exception 'invalid_confirmation_token';
    end if;

    v_result := public.apply_personal_link_ops(pending.payload);

    update public.personal_link_pending_write_actions
    set committed_at = now(),
        result = v_result
    where id = pending.id
      and user_id = caller;

    return jsonb_build_object(
        'status', 'committed',
        'action_id', pending.id,
        'action_type', pending.action_type,
        'result', v_result
    );
end;
$$;
create or replace function public.fetch_personal_saved_link(p_link_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = public, private, pg_temp
as $$
    select jsonb_build_object(
        'id', link.id,
        'title', link.effective_title,
        'url', link.open_url,
        'text', concat_ws(
            E'\n',
            '# ' || link.effective_title,
            'URL: ' || link.open_url,
            'Service: ' || link.service_type,
            'State: ' || link.record_state,
            'Collection: ' || coalesce(link.collection_name, ''),
            'Tags: ' || coalesce((
                select string_agg(tag.name, ', ' order by tag.name)
                from public.personal_saved_link_tag_refs ref
                join public.personal_saved_link_tags tag on tag.id = ref.tag_id
                where ref.link_id = link.id
                  and ref.user_id = link.user_id
                  and ref.deleted_at is null
                  and tag.deleted_at is null
            ), 'none'),
            case when btrim(link.memo) <> '' then 'Memo: ' || link.memo end,
            case when link.body_summary is not null then 'Summary: ' || link.body_summary end,
            case when link.description is not null then 'Description: ' || link.description end,
            case when link.fetched_body is not null then E'\n## Body\n' || link.fetched_body end
        ),
        'metadata', jsonb_build_object(
            'record_state', link.record_state,
            'metadata_state', link.metadata_state,
            'metadata_error', link.metadata_error,
            'normalized_host', link.normalized_host,
            'raw_source_host', link.raw_source_host,
            'source_created_at', link.source_created_at,
            'source_updated_at', link.source_updated_at,
            'archived_at', link.archived_at,
            'content_fetch_allowed', link.content_fetch_allowed
        )
    )
    from public.personal_saved_links link
    where auth.uid() is not null
      and link.user_id = auth.uid()
      and link.id = p_link_id
      and link.deleted_at is null
      and link.disabled_at is null
$$;
create or replace function public.purge_disabled_personal_link_sync()
returns integer
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    purged_count integer;
begin
    delete from public.personal_saved_link_tag_refs ref
    using public.personal_link_sync_settings settings
    where settings.user_id = ref.user_id
      and settings.enabled = false
      and settings.purge_after is not null
      and settings.purge_after <= now();

    delete from public.personal_saved_links link
    using public.personal_link_sync_settings settings
    where settings.user_id = link.user_id
      and settings.enabled = false
      and settings.purge_after is not null
      and settings.purge_after <= now();

    get diagnostics purged_count = row_count;

    delete from public.personal_saved_link_tags tag
    using public.personal_link_sync_settings settings
    where settings.user_id = tag.user_id
      and settings.enabled = false
      and settings.purge_after is not null
      and settings.purge_after <= now();

    delete from public.personal_link_pending_write_actions action
    using public.personal_link_sync_settings settings
    where settings.user_id = action.user_id
      and settings.enabled = false
      and settings.purge_after is not null
      and settings.purge_after <= now();

    delete from public.personal_link_applied_client_ops applied
    using public.personal_link_sync_settings settings
    where settings.user_id = applied.user_id
      and settings.enabled = false
      and settings.purge_after is not null
      and settings.purge_after <= now();

    delete from public.personal_link_sync_settings settings
    where settings.enabled = false
      and settings.purge_after is not null
      and settings.purge_after <= now();

    return purged_count;
end;
$$;
revoke execute on function public.set_personal_link_chatgpt_sync(boolean, boolean) from public, anon, authenticated;
revoke execute on function public.apply_personal_link_ops(jsonb) from public, anon, authenticated;
revoke execute on function public.prepare_personal_link_write_action(text, text, jsonb, integer) from public, anon, authenticated;
revoke execute on function public.commit_personal_link_write_action(uuid, text) from public, anon, authenticated;
revoke execute on function public.search_personal_saved_links(text, integer) from public, anon, authenticated;
revoke execute on function public.fetch_personal_saved_link(uuid) from public, anon, authenticated;
revoke execute on function public.purge_disabled_personal_link_sync() from public, anon, authenticated;
grant execute on function public.set_personal_link_chatgpt_sync(boolean, boolean) to authenticated;
grant execute on function public.apply_personal_link_ops(jsonb) to authenticated;
grant execute on function public.prepare_personal_link_write_action(text, text, jsonb, integer) to authenticated;
grant execute on function public.commit_personal_link_write_action(uuid, text) to authenticated;
grant execute on function public.search_personal_saved_links(text, integer) to authenticated;
grant execute on function public.fetch_personal_saved_link(uuid) to authenticated;
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'service_role') then
        grant execute on function public.purge_disabled_personal_link_sync() to service_role;
    end if;
end
$$;
