create extension if not exists pgcrypto;

create schema if not exists private;

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

create or replace function private.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create or replace function private.bump_shared_tag_version()
returns trigger
language plpgsql
as $$
declare
    affected_tag_id uuid;
begin
    affected_tag_id := coalesce(new.tag_id, old.tag_id);

    update public.shared_tags
    set updated_at = now(),
        version = version + 1
    where id = affected_tag_id;

    return coalesce(new, old);
end;
$$;

create table if not exists public.shared_tags (
    id uuid primary key,
    name text not null check (char_length(btrim(name)) between 1 and 50),
    created_by uuid not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz null,
    version bigint not null default 1
);

create table if not exists public.shared_tag_members (
    tag_id uuid not null references public.shared_tags(id) on delete cascade,
    user_id uuid not null,
    role text not null check (role in ('owner', 'editor', 'viewer')),
    status text not null check (status in ('active', 'invited', 'removed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (tag_id, user_id)
);

create table if not exists public.shared_tag_urls (
    id uuid primary key,
    tag_id uuid not null references public.shared_tags(id) on delete cascade,
    raw_url text not null,
    normalized_url text not null,
    normalization_version integer not null check (normalization_version > 0),
    added_by uuid not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz null,
    unique (tag_id, normalized_url)
);

create table if not exists public.applied_client_ops (
    op_id uuid primary key,
    user_id uuid not null,
    client_id uuid not null,
    applied_at timestamptz not null default now(),
    result jsonb null
);

create index if not exists idx_shared_tags_visible
    on public.shared_tags (updated_at desc)
    where deleted_at is null;

create index if not exists idx_shared_tag_members_user
    on public.shared_tag_members (user_id, status);

create index if not exists idx_shared_tag_urls_tag
    on public.shared_tag_urls (tag_id, updated_at desc);

create index if not exists idx_shared_tag_urls_visible
    on public.shared_tag_urls (normalized_url)
    where deleted_at is null;

drop trigger if exists trg_shared_tags_updated_at on public.shared_tags;
create trigger trg_shared_tags_updated_at
before update on public.shared_tags
for each row
execute function private.set_updated_at();

drop trigger if exists trg_shared_tag_members_updated_at on public.shared_tag_members;
create trigger trg_shared_tag_members_updated_at
before update on public.shared_tag_members
for each row
execute function private.set_updated_at();

drop trigger if exists trg_shared_tag_urls_updated_at on public.shared_tag_urls;
create trigger trg_shared_tag_urls_updated_at
before update on public.shared_tag_urls
for each row
execute function private.set_updated_at();

drop trigger if exists trg_shared_tag_members_bump_parent on public.shared_tag_members;
create trigger trg_shared_tag_members_bump_parent
after insert or update or delete on public.shared_tag_members
for each row
execute function private.bump_shared_tag_version();

drop trigger if exists trg_shared_tag_urls_bump_parent on public.shared_tag_urls;
create trigger trg_shared_tag_urls_bump_parent
after insert or update or delete on public.shared_tag_urls
for each row
execute function private.bump_shared_tag_version();

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

create or replace function public.apply_shared_tag_ops(payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    op jsonb;
    v_op_id uuid;
    v_client_id uuid;
    v_op_type text;
    v_tag_id uuid;
    v_url_id uuid;
    v_member_user_id uuid;
    v_tag_name text;
    v_normalized_url text;
    v_expected_normalized_url text;
    v_raw_url text;
    v_role_text text;
    stored_result jsonb;
    result_item jsonb;
    results jsonb := '[]'::jsonb;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    if jsonb_typeof(payload) <> 'array' then
        raise exception 'payload_must_be_array';
    end if;

    for op in
        select value
        from jsonb_array_elements(payload)
    loop
        v_op_id := (op ->> 'op_id')::uuid;
        v_client_id := (op ->> 'client_id')::uuid;
        v_op_type := op ->> 'type';

        select applied.result
        into stored_result
        from public.applied_client_ops applied
        where applied.op_id = v_op_id
          and applied.user_id = caller;

        if stored_result is not null then
            results := results || jsonb_build_array(stored_result);
            continue;
        end if;

        case v_op_type
            when 'create_tag' then
                v_tag_id := coalesce((op ->> 'tag_id')::uuid, gen_random_uuid());
                v_tag_name := btrim(op ->> 'name');

                if v_tag_name is null or v_tag_name = '' or char_length(v_tag_name) > 50 then
                    raise exception 'invalid_tag_name';
                end if;

                insert into public.shared_tags (id, name, created_by)
                values (v_tag_id, v_tag_name, caller)
                on conflict (id) do update
                set name = excluded.name;

                insert into public.shared_tag_members (tag_id, user_id, role, status)
                values (v_tag_id, caller, 'owner', 'active')
                on conflict (tag_id, user_id) do update
                set role = 'owner',
                    status = 'active';

                result_item := jsonb_build_object(
                    'op_id', v_op_id,
                    'status', 'applied',
                    'tag_id', v_tag_id
                );

            when 'rename_tag' then
                v_tag_id := (op ->> 'tag_id')::uuid;
                v_tag_name := btrim(op ->> 'name');

                perform private.require_tag_role(v_tag_id, caller, array['owner', 'editor']);

                if v_tag_name is null or v_tag_name = '' or char_length(v_tag_name) > 50 then
                    raise exception 'invalid_tag_name';
                end if;

                update public.shared_tags
                set name = v_tag_name
                where id = v_tag_id;

                result_item := jsonb_build_object(
                    'op_id', v_op_id,
                    'status', 'applied',
                    'tag_id', v_tag_id
                );

            when 'delete_tag' then
                v_tag_id := (op ->> 'tag_id')::uuid;

                perform private.require_tag_role(v_tag_id, caller, array['owner', 'editor']);

                update public.shared_tags
                set deleted_at = now()
                where id = v_tag_id
                  and deleted_at is null;

                result_item := jsonb_build_object(
                    'op_id', v_op_id,
                    'status', 'applied',
                    'tag_id', v_tag_id
                );

            when 'add_url_to_tag' then
                v_tag_id := (op ->> 'tag_id')::uuid;
                v_url_id := coalesce((op ->> 'url_id')::uuid, gen_random_uuid());
                v_raw_url := op ->> 'raw_url';
                v_normalized_url := op ->> 'normalized_url';

                perform private.require_tag_role(v_tag_id, caller, array['owner', 'editor']);

                v_expected_normalized_url := public.normalize_shared_url(v_raw_url);
                if v_expected_normalized_url is null or v_expected_normalized_url <> v_normalized_url then
                    raise exception 'normalized_url_mismatch';
                end if;

                insert into public.shared_tag_urls (
                    id,
                    tag_id,
                    raw_url,
                    normalized_url,
                    normalization_version,
                    added_by
                )
                values (
                    v_url_id,
                    v_tag_id,
                    v_raw_url,
                    v_normalized_url,
                    coalesce((op ->> 'normalization_version')::integer, 1),
                    caller
                )
                on conflict (tag_id, normalized_url) do update
                set raw_url = excluded.raw_url,
                    normalization_version = excluded.normalization_version,
                    deleted_at = null,
                    added_by = excluded.added_by
                returning public.shared_tag_urls.id into v_url_id;

                result_item := jsonb_build_object(
                    'op_id', v_op_id,
                    'status', 'applied',
                    'tag_id', v_tag_id,
                    'url_id', v_url_id,
                    'normalized_url', v_normalized_url
                );

            when 'remove_url_from_tag' then
                v_tag_id := (op ->> 'tag_id')::uuid;
                v_normalized_url := op ->> 'normalized_url';

                perform private.require_tag_role(v_tag_id, caller, array['owner', 'editor']);

                update public.shared_tag_urls
                set deleted_at = now()
                where shared_tag_urls.tag_id = v_tag_id
                  and shared_tag_urls.normalized_url = v_normalized_url
                  and deleted_at is null;

                result_item := jsonb_build_object(
                    'op_id', v_op_id,
                    'status', 'applied',
                    'tag_id', v_tag_id,
                    'normalized_url', v_normalized_url
                );

            when 'invite_member' then
                v_tag_id := (op ->> 'tag_id')::uuid;
                v_member_user_id := (op ->> 'user_id')::uuid;
                v_role_text := op ->> 'role';

                perform private.require_tag_role(v_tag_id, caller, array['owner']);

                insert into public.shared_tag_members (tag_id, user_id, role, status)
                values (v_tag_id, v_member_user_id, v_role_text, 'invited')
                on conflict (tag_id, user_id) do update
                set role = excluded.role,
                    status = 'invited';

                result_item := jsonb_build_object(
                    'op_id', v_op_id,
                    'status', 'applied',
                    'tag_id', v_tag_id,
                    'user_id', v_member_user_id
                );

            when 'change_member_role' then
                v_tag_id := (op ->> 'tag_id')::uuid;
                v_member_user_id := (op ->> 'user_id')::uuid;
                v_role_text := op ->> 'role';

                perform private.require_tag_role(v_tag_id, caller, array['owner']);

                update public.shared_tag_members
                set role = v_role_text
                where shared_tag_members.tag_id = v_tag_id
                  and shared_tag_members.user_id = v_member_user_id;

                result_item := jsonb_build_object(
                    'op_id', v_op_id,
                    'status', 'applied',
                    'tag_id', v_tag_id,
                    'user_id', v_member_user_id
                );

            when 'remove_member' then
                v_tag_id := (op ->> 'tag_id')::uuid;
                v_member_user_id := (op ->> 'user_id')::uuid;

                if v_member_user_id = caller then
                    if exists (
                        select 1
                        from public.shared_tag_members member
                        where member.tag_id = v_tag_id
                          and member.user_id = caller
                          and member.role = 'owner'
                          and member.status = 'active'
                    ) then
                        raise exception 'owner_cannot_leave';
                    end if;
                    perform private.require_tag_role(v_tag_id, caller, array['editor', 'viewer']);
                else
                    perform private.require_tag_role(v_tag_id, caller, array['owner']);
                end if;

                update public.shared_tag_members
                set status = 'removed'
                where shared_tag_members.tag_id = v_tag_id
                  and shared_tag_members.user_id = v_member_user_id;

                result_item := jsonb_build_object(
                    'op_id', v_op_id,
                    'status', 'applied',
                    'tag_id', v_tag_id,
                    'user_id', v_member_user_id
                );

            else
                raise exception 'unsupported_op_type';
        end case;

        insert into public.applied_client_ops (op_id, user_id, client_id, result)
        values (v_op_id, caller, v_client_id, result_item);

        results := results || jsonb_build_array(result_item);
    end loop;

    return jsonb_build_object('results', results);
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
        'normalization_version', 1,
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

alter table public.shared_tags enable row level security;
alter table public.shared_tag_members enable row level security;
alter table public.shared_tag_urls enable row level security;
alter table public.applied_client_ops enable row level security;

drop policy if exists shared_tags_select_active_member on public.shared_tags;
create policy shared_tags_select_active_member
on public.shared_tags
for select
to authenticated
using (
    deleted_at is null
    and exists (
        select 1
        from public.shared_tag_members member
        where member.tag_id = shared_tags.id
          and member.user_id = auth.uid()
          and member.status = 'active'
    )
);

drop policy if exists shared_tag_members_select_active_member on public.shared_tag_members;
create policy shared_tag_members_select_active_member
on public.shared_tag_members
for select
to authenticated
using (
    exists (
        select 1
        from public.shared_tag_members access_member
        where access_member.tag_id = shared_tag_members.tag_id
          and access_member.user_id = auth.uid()
          and access_member.status = 'active'
    )
);

drop policy if exists shared_tag_urls_select_active_member on public.shared_tag_urls;
create policy shared_tag_urls_select_active_member
on public.shared_tag_urls
for select
to authenticated
using (
    deleted_at is null
    and exists (
        select 1
        from public.shared_tag_members member
        where member.tag_id = shared_tag_urls.tag_id
          and member.user_id = auth.uid()
          and member.status = 'active'
    )
);

drop policy if exists applied_client_ops_select_owner on public.applied_client_ops;
create policy applied_client_ops_select_owner
on public.applied_client_ops
for select
to authenticated
using (user_id = auth.uid());

grant usage on schema public to authenticated;
grant select on public.shared_tags, public.shared_tag_members, public.shared_tag_urls, public.applied_client_ops to authenticated;
grant execute on function public.normalize_shared_url(text) to authenticated;
grant execute on function public.apply_shared_tag_ops(jsonb) to authenticated;
grant execute on function public.pull_shared_tag_snapshot() to authenticated;
