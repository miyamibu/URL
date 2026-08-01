begin;

-- The earlier *_shared_url_normalization_v2 migration introduced service-specific
-- host/path rewriting. Android and iOS have always emitted the v1 contract:
-- preserve the normalized URL shape and query string, including YouTube t and X s.
-- Keep the old migration immutable and restore the shared contract forward.
create or replace function public.normalize_shared_url(raw_url text)
returns text
language plpgsql
immutable
as $$
declare
    parts text[];
    scheme text;
    userinfo text;
    host text;
    port_text text;
    path text;
    query text;
    normalized_authority text;
begin
    if raw_url is null then
        return null;
    end if;

    parts := regexp_match(
        btrim(raw_url),
        '^(https?)://((?:[^/?#@]+@)?)(\[[^]]+\]|[^:/?#]+)(?::([0-9]+))?([^?#]*)(?:\?([^#]*))?(?:#.*)?$',
        'i'
    );

    if parts is null then
        return null;
    end if;

    scheme := lower(parts[1]);
    userinfo := nullif(parts[2], '');
    host := lower(parts[3]);
    port_text := nullif(parts[4], '');
    path := coalesce(parts[5], '');
    query := nullif(parts[6], '');

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

    normalized_authority := coalesce(userinfo, '') || host;

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

-- Fail closed before changing any existing row. A null result means the stored
-- raw URL cannot participate in the v1 contract. A duplicate would violate the
-- existing unique(tag_id, normalized_url) key after backfill.
do $$
declare
    invalid_url_id uuid;
    collision_tag_id uuid;
    collision_normalized_url text;
begin
    select url.id
    into invalid_url_id
    from public.shared_tag_urls url
    where public.normalize_shared_url(url.raw_url) is null
    limit 1;

    if invalid_url_id is not null then
        raise exception 'shared_url_normalization_v1_invalid_raw_url:%', invalid_url_id;
    end if;

    select candidate.tag_id, candidate.normalized_url
    into collision_tag_id, collision_normalized_url
    from (
        select
            url.tag_id,
            public.normalize_shared_url(url.raw_url) as normalized_url
        from public.shared_tag_urls url
    ) candidate
    group by candidate.tag_id, candidate.normalized_url
    having count(*) > 1
    limit 1;

    if collision_tag_id is not null then
        raise exception 'shared_url_normalization_v1_collision:tag_id=%:normalized_url=%',
            collision_tag_id,
            collision_normalized_url;
    end if;
end;
$$;

update public.shared_tag_urls
set normalized_url = public.normalize_shared_url(raw_url),
    normalization_version = 1
where normalized_url is distinct from public.normalize_shared_url(raw_url)
   or normalization_version is distinct from 1;

create or replace function private.enforce_shared_tag_url_normalization_v1()
returns trigger
language plpgsql
as $$
declare
    expected_normalized_url text;
begin
    if new.normalization_version is distinct from 1 then
        raise exception 'unsupported_normalization_version';
    end if;

    expected_normalized_url := public.normalize_shared_url(new.raw_url);
    if expected_normalized_url is null
       or expected_normalized_url is distinct from new.normalized_url then
        raise exception 'normalized_url_mismatch';
    end if;

    return new;
end;
$$;

drop trigger if exists trg_shared_tag_urls_normalization_v1 on public.shared_tag_urls;
create trigger trg_shared_tag_urls_normalization_v1
before insert or update of raw_url, normalized_url, normalization_version
on public.shared_tag_urls
for each row
execute function private.enforce_shared_tag_url_normalization_v1();

commit;
