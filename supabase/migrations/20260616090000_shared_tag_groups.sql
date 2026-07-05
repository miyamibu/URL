create table if not exists public.shared_tag_groups (
    id uuid primary key default gen_random_uuid(),
    name text not null check (char_length(btrim(name)) between 1 and 50),
    created_by uuid not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz null
);
create table if not exists public.shared_tag_group_members (
    group_id uuid not null references public.shared_tag_groups(id) on delete cascade,
    user_id uuid not null,
    role text not null check (role in ('owner', 'editor', 'viewer')),
    status text not null check (status in ('active', 'invited', 'removed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (group_id, user_id)
);
create table if not exists public.shared_tag_group_invites (
    token_hash text primary key,
    group_id uuid not null references public.shared_tag_groups(id) on delete cascade,
    role text not null check (role in ('editor', 'viewer')),
    created_by uuid not null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    claimed_by uuid null,
    claimed_at timestamptz null,
    revoked_at timestamptz null
);
alter table public.shared_tags
    add column if not exists group_id uuid null references public.shared_tag_groups(id) on delete set null;
create index if not exists idx_shared_tag_groups_visible
    on public.shared_tag_groups (deleted_at, name);
create index if not exists idx_shared_tag_group_members_user
    on public.shared_tag_group_members (user_id, status);
create index if not exists idx_shared_tag_group_invites_group
    on public.shared_tag_group_invites (group_id, created_at desc);
create index if not exists idx_shared_tag_group_invites_active
    on public.shared_tag_group_invites (expires_at)
    where revoked_at is null and claimed_at is null;
create index if not exists idx_shared_tags_group
    on public.shared_tags (group_id)
    where group_id is not null;
drop trigger if exists shared_tag_groups_updated_at on public.shared_tag_groups;
create trigger shared_tag_groups_updated_at
before update on public.shared_tag_groups
for each row
execute function private.set_updated_at();
drop trigger if exists shared_tag_group_members_updated_at on public.shared_tag_group_members;
create trigger shared_tag_group_members_updated_at
before update on public.shared_tag_group_members
for each row
execute function private.set_updated_at();
create or replace function private.require_shared_tag_group_role(
    p_group_id uuid,
    p_user_id uuid,
    allowed_roles text[]
)
returns void
language plpgsql
stable
set search_path = public, private, pg_temp
as $$
begin
    if not exists (
        select 1
        from public.shared_tag_group_members member
        join public.shared_tag_groups group_record
          on group_record.id = member.group_id
        where member.group_id = p_group_id
          and member.user_id = p_user_id
          and member.status = 'active'
          and member.role = any(allowed_roles)
          and group_record.deleted_at is null
    ) then
        raise exception 'group_permission_denied';
    end if;
end;
$$;
create or replace function private.hash_shared_tag_group_invite_token(raw_token text)
returns text
language sql
immutable
set search_path = public, private, pg_temp
as $$
    select encode(extensions.digest(btrim(raw_token), 'sha256'), 'hex')
$$;
create or replace function public.create_shared_tag_group(p_name text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    group_id uuid := gen_random_uuid();
    group_name text := btrim(p_name);
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    if group_name is null or group_name = '' or char_length(group_name) > 50 then
        raise exception 'invalid_group_name';
    end if;

    insert into public.shared_tag_groups (id, name, created_by)
    values (group_id, group_name, caller);

    insert into public.shared_tag_group_members (group_id, user_id, role, status)
    values (group_id, caller, 'owner', 'active');

    return jsonb_build_object(
        'group_id', group_id,
        'name', group_name,
        'role', 'owner',
        'status', 'active'
    );
end;
$$;
create or replace function public.list_shared_tag_groups()
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    return coalesce(
        (
            select jsonb_agg(
                jsonb_build_object(
                    'group_id', group_record.id,
                    'name', group_record.name,
                    'role', member.role,
                    'member_count', (
                        select count(*)
                        from public.shared_tag_group_members count_member
                        where count_member.group_id = group_record.id
                          and count_member.status = 'active'
                    ),
                    'tag_count', (
                        select count(*)
                        from public.shared_tags tag
                        where tag.group_id = group_record.id
                          and tag.deleted_at is null
                    )
                )
                order by group_record.name
            )
            from public.shared_tag_groups group_record
            join public.shared_tag_group_members member
              on member.group_id = group_record.id
            where member.user_id = caller
              and member.status = 'active'
              and group_record.deleted_at is null
        ),
        '[]'::jsonb
    );
end;
$$;
create or replace function public.create_shared_tag_group_invite(
    p_group_id uuid,
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
        raise exception 'invalid_group_invite_role';
    end if;

    perform private.require_shared_tag_group_role(p_group_id, caller, array['owner', 'editor']);

    invite_token := encode(extensions.gen_random_bytes(24), 'hex');
    invite_hash := private.hash_shared_tag_group_invite_token(invite_token);

    insert into public.shared_tag_group_invites (
        token_hash,
        group_id,
        role,
        created_by,
        expires_at
    )
    values (
        invite_hash,
        p_group_id,
        p_role,
        caller,
        expires_at
    );

    return jsonb_build_object(
        'group_id', p_group_id,
        'invite_token', invite_token,
        'expires_at', expires_at,
        'role', p_role
    );
end;
$$;
create or replace function public.create_shared_tag_in_group(
    p_group_id uuid,
    p_name text
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    v_tag_id uuid := gen_random_uuid();
    tag_name text := btrim(p_name);
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    if tag_name is null or tag_name = '' or char_length(tag_name) > 50 then
        raise exception 'invalid_tag_name';
    end if;

    perform private.require_shared_tag_group_role(p_group_id, caller, array['owner', 'editor']);

    insert into public.shared_tags (id, name, created_by, group_id)
    values (v_tag_id, tag_name, caller, p_group_id);

    insert into public.shared_tag_members (tag_id, user_id, role, status)
    select v_tag_id,
           member.user_id,
           case
               when member.user_id = caller then 'owner'
               when member.role in ('owner', 'editor') then 'editor'
               else 'viewer'
           end,
           'active'
    from public.shared_tag_group_members member
    where member.group_id = p_group_id
      and member.status = 'active'
    on conflict (tag_id, user_id) do update
    set role = case
            when public.shared_tag_members.role = 'owner' then public.shared_tag_members.role
            else excluded.role
        end,
        status = 'active';

    return jsonb_build_object(
        'tag_id', v_tag_id,
        'group_id', p_group_id,
        'name', tag_name,
        'status', 'active'
    );
end;
$$;
create or replace function public.accept_shared_tag_group_invite(p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    invite_record public.shared_tag_group_invites%rowtype;
    group_name text;
    applied_role text;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    select invite.*
    into invite_record
    from public.shared_tag_group_invites invite
    join public.shared_tag_groups group_record
      on group_record.id = invite.group_id
    where invite.token_hash = private.hash_shared_tag_group_invite_token(p_token)
      and invite.revoked_at is null
      and invite.expires_at > now()
      and (invite.claimed_by is null or invite.claimed_by = caller)
      and group_record.deleted_at is null
    limit 1;

    if invite_record.token_hash is null then
        raise exception 'invalid_or_expired_group_invite';
    end if;

    insert into public.shared_tag_group_members (group_id, user_id, role, status)
    values (invite_record.group_id, caller, invite_record.role, 'active')
    on conflict (group_id, user_id) do update
    set role = case
            when public.shared_tag_group_members.role = 'owner' then public.shared_tag_group_members.role
            else excluded.role
        end,
        status = 'active';

    update public.shared_tag_group_invites
    set claimed_by = caller,
        claimed_at = now()
    where token_hash = invite_record.token_hash
      and claimed_by is null;

    insert into public.shared_tag_members (tag_id, user_id, role, status)
    select tag.id,
           caller,
           invite_record.role,
           'active'
    from public.shared_tags tag
    where tag.group_id = invite_record.group_id
      and tag.deleted_at is null
    on conflict (tag_id, user_id) do update
    set role = case
            when public.shared_tag_members.role = 'owner' then public.shared_tag_members.role
            else excluded.role
        end,
        status = 'active';

    select group_record.name into group_name
    from public.shared_tag_groups group_record
    where group_record.id = invite_record.group_id;

    select member.role into applied_role
    from public.shared_tag_group_members member
    where member.group_id = invite_record.group_id
      and member.user_id = caller;

    return jsonb_build_object(
        'group_id', invite_record.group_id,
        'group_name', group_name,
        'role', applied_role,
        'status', 'active'
    );
end;
$$;
create or replace function public.preview_shared_tag_group_invite(p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    invite_record public.shared_tag_group_invites%rowtype;
    group_name text;
begin
    select invite.*
    into invite_record
    from public.shared_tag_group_invites invite
    join public.shared_tag_groups group_record
      on group_record.id = invite.group_id
    where invite.token_hash = private.hash_shared_tag_group_invite_token(p_token)
      and invite.revoked_at is null
      and invite.expires_at > now()
      and invite.claimed_by is null
      and group_record.deleted_at is null
    limit 1;

    if invite_record.token_hash is null then
        raise exception 'invalid_or_expired_group_invite';
    end if;

    select group_record.name into group_name
    from public.shared_tag_groups group_record
    where group_record.id = invite_record.group_id;

    return jsonb_build_object(
        'group_id', invite_record.group_id,
        'group_name', group_name,
        'role', invite_record.role
    );
end;
$$;
alter table public.shared_tag_groups enable row level security;
alter table public.shared_tag_group_members enable row level security;
alter table public.shared_tag_group_invites enable row level security;
drop policy if exists shared_tag_groups_select_active_member on public.shared_tag_groups;
create policy shared_tag_groups_select_active_member
on public.shared_tag_groups
for select
to authenticated
using (
    deleted_at is null
    and exists (
        select 1
        from public.shared_tag_group_members member
        where member.group_id = shared_tag_groups.id
          and member.user_id = auth.uid()
          and member.status = 'active'
    )
);
drop policy if exists shared_tag_group_members_select_active_member on public.shared_tag_group_members;
create policy shared_tag_group_members_select_active_member
on public.shared_tag_group_members
for select
to authenticated
using (
    exists (
        select 1
        from public.shared_tag_group_members access_member
        where access_member.group_id = shared_tag_group_members.group_id
          and access_member.user_id = auth.uid()
          and access_member.status = 'active'
    )
);
drop policy if exists shared_tag_group_invites_select_creator on public.shared_tag_group_invites;
create policy shared_tag_group_invites_select_creator
on public.shared_tag_group_invites
for select
to authenticated
using (created_by = auth.uid());
grant select on public.shared_tag_groups, public.shared_tag_group_members, public.shared_tag_group_invites to authenticated;
grant execute on function public.create_shared_tag_group(text) to authenticated;
grant execute on function public.list_shared_tag_groups() to authenticated;
grant execute on function public.create_shared_tag_group_invite(uuid, text) to authenticated;
grant execute on function public.create_shared_tag_in_group(uuid, text) to authenticated;
grant execute on function public.accept_shared_tag_group_invite(text) to authenticated;
grant execute on function public.preview_shared_tag_group_invite(text) to anon, authenticated;
