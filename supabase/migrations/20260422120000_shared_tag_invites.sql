create table if not exists public.shared_tag_invites (
    token_hash text primary key,
    tag_id uuid not null references public.shared_tags(id) on delete cascade,
    role text not null check (role in ('editor', 'viewer')),
    created_by uuid not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    claimed_by uuid null,
    claimed_at timestamptz null,
    revoked_at timestamptz null
);

create index if not exists idx_shared_tag_invites_tag
    on public.shared_tag_invites (tag_id, created_at desc);

create index if not exists idx_shared_tag_invites_active
    on public.shared_tag_invites (expires_at)
    where revoked_at is null and claimed_at is null;

create or replace function private.hash_shared_tag_invite_token(raw_token text)
returns text
language sql
immutable
as $$
    select encode(digest(btrim(raw_token), 'sha256'), 'hex')
$$;

create or replace function public.create_shared_tag_invite(
    p_tag_id uuid,
    p_role text default 'editor'
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    invite_token text;
    invite_hash text;
    expires_at timestamptz := now() + interval '7 days';
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    if p_role not in ('editor', 'viewer') then
        raise exception 'invalid_invite_role';
    end if;

    perform private.require_tag_role(p_tag_id, caller, array['owner']);

    if not exists (
        select 1
        from public.shared_tags tag
        where tag.id = p_tag_id
          and tag.deleted_at is null
    ) then
        raise exception 'tag_not_found';
    end if;

    invite_token := encode(gen_random_bytes(24), 'hex');
    invite_hash := private.hash_shared_tag_invite_token(invite_token);

    insert into public.shared_tag_invites (
        token_hash,
        tag_id,
        role,
        created_by,
        expires_at
    )
    values (
        invite_hash,
        p_tag_id,
        p_role,
        caller,
        expires_at
    );

    return jsonb_build_object(
        'tag_id', p_tag_id,
        'invite_token', invite_token,
        'expires_at', expires_at,
        'role', p_role
    );
end;
$$;

create or replace function public.accept_shared_tag_invite(p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    invite_record public.shared_tag_invites%rowtype;
    tag_name text;
    applied_role text;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    select invite.*
    into invite_record
    from public.shared_tag_invites invite
    join public.shared_tags tag
      on tag.id = invite.tag_id
    where invite.token_hash = private.hash_shared_tag_invite_token(p_token)
      and invite.revoked_at is null
      and invite.expires_at > now()
      and (invite.claimed_by is null or invite.claimed_by = caller)
      and tag.deleted_at is null
    limit 1;

    if invite_record.token_hash is null then
        raise exception 'invalid_or_expired_invite';
    end if;

    insert into public.shared_tag_members (tag_id, user_id, role, status)
    values (invite_record.tag_id, caller, invite_record.role, 'active')
    on conflict (tag_id, user_id) do update
    set role = case
            when public.shared_tag_members.role = 'owner' then public.shared_tag_members.role
            else excluded.role
        end,
        status = 'active';

    update public.shared_tag_invites
    set claimed_by = caller,
        claimed_at = now()
    where token_hash = invite_record.token_hash
      and claimed_by is null;

    select tag.name into tag_name
    from public.shared_tags tag
    where tag.id = invite_record.tag_id;

    select member.role into applied_role
    from public.shared_tag_members member
    where member.tag_id = invite_record.tag_id
      and member.user_id = caller;

    return jsonb_build_object(
        'tag_id', invite_record.tag_id,
        'tag_name', tag_name,
        'role', applied_role,
        'status', 'active'
    );
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

                perform private.require_tag_role(v_tag_id, caller, array['owner']);

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

                perform private.require_tag_role(v_tag_id, caller, array['owner']);

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

create or replace function public.preview_shared_tag_invite(p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    invite_record public.shared_tag_invites%rowtype;
    tag_name text;
begin
    select invite.*
    into invite_record
    from public.shared_tag_invites invite
    join public.shared_tags tag
      on tag.id = invite.tag_id
    where invite.token_hash = private.hash_shared_tag_invite_token(p_token)
      and invite.revoked_at is null
      and invite.expires_at > now()
      and tag.deleted_at is null
    limit 1;

    if invite_record.token_hash is null then
        raise exception 'invalid_or_expired_invite';
    end if;

    select tag.name into tag_name
    from public.shared_tags tag
    where tag.id = invite_record.tag_id;

    return jsonb_build_object(
        'tag_name', tag_name
    );
end;
$$;

alter table public.shared_tag_invites enable row level security;

grant execute on function public.create_shared_tag_invite(uuid, text) to authenticated;
grant execute on function public.preview_shared_tag_invite(text) to anon, authenticated;
grant execute on function public.accept_shared_tag_invite(text) to authenticated;
