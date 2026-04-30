\set ON_ERROR_STOP on

create schema if not exists auth;

create table if not exists auth.users (
    id uuid primary key
);

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'authenticated') then
        create role authenticated;
    end if;
end
$$;

create or replace function auth.uid()
returns uuid
language sql
stable
as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;

\i supabase/migrations/20260420120000_shared_tag_sync.sql
\i supabase/migrations/20260422120000_shared_tag_invites.sql
\i supabase/migrations/20260423150000_account_deletion.sql

do $$
declare
    owner_id uuid := '00000000-0000-0000-0000-000000000001';
    editor_id uuid := '00000000-0000-0000-0000-000000000002';
    viewer_id uuid := '00000000-0000-0000-0000-000000000003';
    tag_uuid uuid := '10000000-0000-0000-0000-000000000001';
    url_uuid uuid := '20000000-0000-0000-0000-000000000001';
    first_pull jsonb;
    second_apply jsonb;
    invite_payload jsonb;
    accepted_invite jsonb;
begin
    insert into auth.users (id)
    values (owner_id), (editor_id), (viewer_id)
    on conflict (id) do nothing;

    if public.normalize_shared_url('HTTPS://Example.COM:443/path/?a=1#frag') <> 'https://example.com/path?a=1' then
        raise exception 'normalize_shared_url mismatch for https default port case';
    end if;

    if public.normalize_shared_url('http://example.com/path') is not null then
        raise exception 'normalize_shared_url accepted non-loopback http';
    end if;

    perform set_config('request.jwt.claim.sub', owner_id::text, true);

    perform public.apply_shared_tag_ops(
        jsonb_build_array(
            jsonb_build_object(
                'op_id', '30000000-0000-0000-0000-000000000001',
                'client_id', '40000000-0000-0000-0000-000000000001',
                'type', 'create_tag',
                'tag_id', tag_uuid,
                'name', 'Cloud Shared'
            ),
            jsonb_build_object(
                'op_id', '30000000-0000-0000-0000-000000000002',
                'client_id', '40000000-0000-0000-0000-000000000001',
                'type', 'add_url_to_tag',
                'tag_id', tag_uuid,
                'url_id', url_uuid,
                'raw_url', 'https://Example.com/path/?q=1#frag',
                'normalized_url', 'https://example.com/path?q=1',
                'normalization_version', 1
            ),
            jsonb_build_object(
                'op_id', '30000000-0000-0000-0000-000000000003',
                'client_id', '40000000-0000-0000-0000-000000000001',
                'type', 'invite_member',
                'tag_id', tag_uuid,
                'user_id', editor_id,
                'role', 'editor'
            )
        )
    );

    second_apply := public.apply_shared_tag_ops(
        jsonb_build_array(
            jsonb_build_object(
                'op_id', '30000000-0000-0000-0000-000000000001',
                'client_id', '40000000-0000-0000-0000-000000000001',
                'type', 'create_tag',
                'tag_id', tag_uuid,
                'name', 'Cloud Shared'
            )
        )
    );

    if (second_apply -> 'results' -> 0 ->> 'tag_id')::uuid <> tag_uuid then
        raise exception 'idempotent op replay did not return stored result';
    end if;

    if (
        select count(*)
        from public.shared_tag_urls url_row
        where url_row.tag_id = tag_uuid
          and url_row.normalized_url = 'https://example.com/path?q=1'
    ) <> 1 then
        raise exception 'unique(tag_id, normalized_url) not enforced as expected';
    end if;

    first_pull := public.pull_shared_tag_snapshot();
    if jsonb_array_length(first_pull -> 'tags') <> 1 then
        raise exception 'snapshot tags count mismatch';
    end if;
    if jsonb_array_length(first_pull -> 'urls') <> 1 then
        raise exception 'snapshot urls count mismatch';
    end if;

    perform set_config('request.jwt.claim.sub', editor_id::text, true);

    begin
        perform public.apply_shared_tag_ops(
            jsonb_build_array(
                jsonb_build_object(
                    'op_id', '30000000-0000-0000-0000-000000000004',
                    'client_id', '40000000-0000-0000-0000-000000000002',
                    'type', 'delete_tag',
                    'tag_id', tag_uuid
                )
            )
        );
        raise exception 'editor unexpectedly deleted tag';
    exception
        when others then
            if position('forbidden' in sqlerrm) = 0 then
                raise;
            end if;
    end;

    perform set_config('request.jwt.claim.sub', owner_id::text, true);
    invite_payload := public.create_shared_tag_invite(tag_uuid, 'editor');
    if coalesce(invite_payload ->> 'invite_token', '') = '' then
        raise exception 'invite token was not returned';
    end if;

    perform set_config('request.jwt.claim.sub', viewer_id::text, true);
    accepted_invite := public.accept_shared_tag_invite(invite_payload ->> 'invite_token');
    if (accepted_invite ->> 'tag_id')::uuid <> tag_uuid then
        raise exception 'accepted invite returned unexpected tag';
    end if;

    if (
        select status
        from public.shared_tag_members member
        where member.tag_id = tag_uuid
          and member.user_id = viewer_id
    ) <> 'active' then
        raise exception 'accept invite did not create active membership';
    end if;

    begin
        perform set_config('request.jwt.claim.sub', owner_id::text, true);
        perform public.delete_my_account();
        raise exception 'owner with active members unexpectedly deleted account';
    exception
        when others then
            if position('owner_transfer_required' in sqlerrm) = 0 then
                raise;
            end if;
    end;

    perform set_config('request.jwt.claim.sub', editor_id::text, true);
    perform public.delete_my_account();

    if exists (
        select 1
        from auth.users
        where id = editor_id
    ) then
        raise exception 'editor auth user was not deleted';
    end if;
end
$$;
