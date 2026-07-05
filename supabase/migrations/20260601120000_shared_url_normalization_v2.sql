create or replace function public.normalize_shared_url(raw_url text)
returns text
language plpgsql
immutable
as $$
declare
    parts text[];
    scheme text;
    host text;
    port_text text;
    path text;
    query text;
    normalized_authority text;
    segments text[];
    match_parts text[];
    video_id text;
begin
    if raw_url is null then
        return null;
    end if;

    parts := regexp_match(
        btrim(raw_url),
        '^(https?)://(?:[^/?#@]+@)?(\[[^]]+\]|[^:/?#]+)(?::([0-9]+))?([^?#]*)(?:\?([^#]*))?(?:#.*)?$',
        'i'
    );

    if parts is null then
        return null;
    end if;

    scheme := lower(parts[1]);
    host := lower(parts[2]);
    port_text := nullif(parts[3], '');
    path := coalesce(parts[4], '');
    query := nullif(parts[5], '');

    if host = '' then
        return null;
    end if;

    if scheme = 'http' and host not in ('127.0.0.1', 'localhost', '[::1]') then
        return null;
    end if;

    if path = '' then
        path := '/';
    elsif path <> '/' then
        path := regexp_replace(path, '/+$', '');
        if path = '' then
            path := '/';
        end if;
    end if;

    segments := regexp_split_to_array(btrim(path, '/'), '/');

    if host = 'youtu.be' or host like '%.youtu.be' then
        video_id := nullif(segments[1], '');
        if video_id ~ '^[A-Za-z0-9_-]+$' then
            return 'https://www.youtube.com/watch?v=' || video_id;
        end if;
    elsif host = 'youtube.com' or host like '%.youtube.com' then
        if lower(coalesce(segments[1], '')) = 'watch' then
            match_parts := regexp_match(coalesce(query, ''), '(^|&)v=([A-Za-z0-9_-]+)(&|$)');
            video_id := match_parts[2];
        elsif lower(coalesce(segments[1], '')) in ('shorts', 'live', 'embed') then
            video_id := nullif(segments[2], '');
        end if;
        if video_id ~ '^[A-Za-z0-9_-]+$' then
            return 'https://www.youtube.com/watch?v=' || video_id;
        end if;
    elsif host = 'x.com' or host like '%.x.com' or host = 'twitter.com' or host like '%.twitter.com' then
        match_parts := regexp_match(path, '^/[^/]+/status/([0-9]+)(?:/.*)?$', 'i');
        if match_parts is null then
            match_parts := regexp_match(path, '^/i/web/status/([0-9]+)(?:/.*)?$', 'i');
        end if;
        if match_parts is null then
            match_parts := regexp_match(path, '^/i/status/([0-9]+)(?:/.*)?$', 'i');
        end if;
        if match_parts is not null then
            return 'https://x.com/i/web/status/' || match_parts[1];
        end if;
    elsif host = 'instagram.com' or host like '%.instagram.com' then
        match_parts := regexp_match(path, '^/(p|reel|tv)/([A-Za-z0-9_-]+)(?:/.*)?$', 'i');
        if match_parts is not null then
            return 'https://www.instagram.com/' || lower(match_parts[1]) || '/' || match_parts[2];
        end if;
    elsif host = 'tiktok.com' or host like '%.tiktok.com' then
        match_parts := regexp_match(path, '^/(@[A-Za-z0-9_-]+)/(video|photo)/([0-9]+)(?:/.*)?$', 'i');
        if match_parts is not null then
            return 'https://www.tiktok.com/' || match_parts[1] || '/' || lower(match_parts[2]) || '/' || match_parts[3];
        end if;
    end if;

    normalized_authority := host;

    if port_text is not null then
        if not (
            (scheme = 'https' and port_text = '443')
            or (scheme = 'http' and port_text = '80')
        ) then
            normalized_authority := normalized_authority || ':' || port_text;
        end if;
    end if;

    return scheme || '://' || normalized_authority || path || coalesce('?' || query, '');
end;
$$;
create or replace function public.pull_shared_tag_snapshot()
returns jsonb
language sql
security definer
set search_path = public, private, pg_temp
as $$
    with visible_tags as (
        select tag.*
        from public.shared_tags tag
        join public.shared_tag_members member
          on member.tag_id = tag.id
         and member.user_id = auth.uid()
         and member.status = 'active'
        where tag.deleted_at is null
    )
    select jsonb_build_object(
        'pulled_at', now(),
        'normalization_version', 2,
        'tags',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'id', tag.id,
                        'name', tag.name,
                        'created_by', tag.created_by,
                        'created_at', tag.created_at,
                        'updated_at', tag.updated_at,
                        'deleted_at', tag.deleted_at,
                        'version', tag.version
                    )
                    order by tag.updated_at desc, tag.id
                )
                from visible_tags tag
            ),
            '[]'::jsonb
        ),
        'members',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'tag_id', member.tag_id,
                        'user_id', member.user_id,
                        'role', member.role,
                        'status', member.status,
                        'created_at', member.created_at,
                        'updated_at', member.updated_at
                    )
                    order by member.tag_id, member.user_id
                )
                from public.shared_tag_members member
                where member.tag_id in (select id from visible_tags)
            ),
            '[]'::jsonb
        ),
        'urls',
        coalesce(
            (
                select jsonb_agg(
                    jsonb_build_object(
                        'id', url.id,
                        'tag_id', url.tag_id,
                        'raw_url', url.raw_url,
                        'normalized_url', url.normalized_url,
                        'normalization_version', url.normalization_version,
                        'added_by', url.added_by,
                        'created_at', url.created_at,
                        'updated_at', url.updated_at,
                        'deleted_at', url.deleted_at
                    )
                    order by url.updated_at desc, url.id
                )
                from public.shared_tag_urls url
                where url.tag_id in (select id from visible_tags)
                  and url.deleted_at is null
            ),
            '[]'::jsonb
        )
    );
$$;
