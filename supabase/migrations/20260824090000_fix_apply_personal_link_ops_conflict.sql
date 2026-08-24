-- apply_personal_link_ops の upsert_link は client_entry_id を conflict target にしていた。
-- Android は "entry-"+hash(normalizedUrl)、iOS は hash("ios", local rowid, normalizedURL) と実装が異なり、
-- 同一ユーザー・同一 normalized_url でも client_entry_id が変わる (再インストール、機種変更、両OS同期) ため、
-- on conflict (user_id, client_entry_id) が既存行に一致せず unique (user_id, normalized_url) 違反で RPC 全体が abort する。
-- 重複主キー不変条件 (normalizedUrl) に従い、conflict target を (user_id, normalized_url) へ変更する。
-- 競合時は client_entry_id も最新の送信元へ収束させ、同期後の古いスナップショット掃除を正しい名前空間で行う。

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
                on conflict (user_id, normalized_url) do update
                set client_entry_id = excluded.client_entry_id,
                    original_url = excluded.original_url,
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
            if position(':' in v_client_entry_id) > 0 then
                v_client_prefix := split_part(v_client_entry_id, ':', 1) || ':%';
            elsif position('-' in v_client_entry_id) > 0 then
                v_client_prefix := split_part(v_client_entry_id, '-', 1) || '-%';
            else
                continue;
            end if;
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
