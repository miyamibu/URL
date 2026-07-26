\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

-- Supabase local test databases provide auth.users, auth.uid(), anon, and
-- authenticated as platform-owned objects. Do not recreate or replace them:
-- the test runner role intentionally cannot modify the auth schema.

do $$
declare
    normal_owner uuid := '00000000-0000-0000-0000-000000000101';
    normal_tag uuid := '10000000-0000-0000-0000-000000000501';
    normal_group uuid;
    pro_owner uuid := '00000000-0000-0000-0000-000000000201';
    pro_tag uuid := '10000000-0000-0000-0000-000000000601';
    pro_group uuid;
    i integer;
begin
    insert into auth.users (id)
    values (normal_owner), (pro_owner)
    on conflict (id) do nothing;

    insert into public.user_entitlement_grants (user_id, plan, source, starts_at, status)
    values (pro_owner, 'pro', 'admin_grant', now() - interval '1 day', 'active');

    insert into public.shared_tags (id, name, created_by)
    values (normal_tag, 'Normal Limited Tag', normal_owner);
    insert into public.shared_tag_members (tag_id, user_id, role, status)
    values (normal_tag, normal_owner, 'owner', 'active');

    for i in 1..50 loop
        insert into public.shared_tag_urls (
            id,
            tag_id,
            raw_url,
            normalized_url,
            normalization_version,
            added_by
        )
        values (
            ('20000000-0000-0000-0000-00000000' || lpad((500 + i)::text, 4, '0'))::uuid,
            normal_tag,
            'https://normal.example/' || i,
            'https://normal.example/' || i,
            1,
            normal_owner
        );
    end loop;

    begin
        insert into public.shared_tag_urls (
            id,
            tag_id,
            raw_url,
            normalized_url,
            normalization_version,
            added_by
        )
        values (
            '20000000-0000-0000-0000-000000000999',
            normal_tag,
            'https://normal.example/overflow',
            'https://normal.example/overflow',
            1,
            normal_owner
        );
        raise exception 'normal shared tag url limit did not block 51st active url';
    exception
        when others then
            if position('shared_tag_url_limit_reached' in sqlerrm) = 0 then
                raise;
            end if;
    end;

    insert into public.shared_tag_groups (id, name, created_by)
    values
        ('60000000-0000-0000-0000-000000000501', 'Normal Group 1', normal_owner),
        ('60000000-0000-0000-0000-000000000502', 'Normal Group 2', normal_owner);

    begin
        insert into public.shared_tag_groups (id, name, created_by)
        values ('60000000-0000-0000-0000-000000000503', 'Normal Group 3', normal_owner);
        raise exception 'normal shared tag group limit did not block 3rd group';
    exception
        when others then
            if position('shared_tag_group_limit_reached' in sqlerrm) = 0 then
                raise;
            end if;
    end;

    normal_group := '60000000-0000-0000-0000-000000000501';
    insert into public.shared_tag_group_members (group_id, user_id, role, status)
    values (normal_group, normal_owner, 'owner', 'active');

    for i in 1..9 loop
        insert into auth.users (id)
        values (('00000000-0000-0000-0000-000000001' || lpad(i::text, 3, '0'))::uuid)
        on conflict (id) do nothing;

        insert into public.shared_tag_group_members (group_id, user_id, role, status)
        values (
            normal_group,
            ('00000000-0000-0000-0000-000000001' || lpad(i::text, 3, '0'))::uuid,
            'viewer',
            'active'
        );
    end loop;

    begin
        insert into auth.users (id)
        values ('00000000-0000-0000-0000-000000001999')
        on conflict (id) do nothing;

        insert into public.shared_tag_group_members (group_id, user_id, role, status)
        values (normal_group, '00000000-0000-0000-0000-000000001999', 'viewer', 'active');
        raise exception 'normal shared tag group member limit did not block 11th active member';
    exception
        when others then
            if position('shared_tag_group_member_limit_reached' in sqlerrm) = 0 then
                raise;
            end if;
    end;

    insert into public.shared_tags (id, name, created_by)
    values (pro_tag, 'Pro Limited Tag', pro_owner);
    insert into public.shared_tag_members (tag_id, user_id, role, status)
    values (pro_tag, pro_owner, 'owner', 'active');

    for i in 1..100 loop
        insert into public.shared_tag_urls (
            id,
            tag_id,
            raw_url,
            normalized_url,
            normalization_version,
            added_by
        )
        values (
            ('20000000-0000-0000-0000-00000001' || lpad(i::text, 4, '0'))::uuid,
            pro_tag,
            'https://pro.example/' || i,
            'https://pro.example/' || i,
            1,
            pro_owner
        );
    end loop;

    begin
        insert into public.shared_tag_urls (
            id,
            tag_id,
            raw_url,
            normalized_url,
            normalization_version,
            added_by
        )
        values (
            '20000000-0000-0000-0000-000000019999',
            pro_tag,
            'https://pro.example/overflow',
            'https://pro.example/overflow',
            1,
            pro_owner
        );
        raise exception 'pro shared tag url limit did not block 101st active url';
    exception
        when others then
            if position('shared_tag_url_limit_reached' in sqlerrm) = 0 then
                raise;
            end if;
    end;

    for i in 1..50 loop
        insert into public.shared_tag_groups (id, name, created_by)
        values (
            ('60000000-0000-0000-0000-00000001' || lpad(i::text, 4, '0'))::uuid,
            'Pro Group ' || i,
            pro_owner
        );
    end loop;

    begin
        insert into public.shared_tag_groups (id, name, created_by)
        values ('60000000-0000-0000-0000-000000019999', 'Pro Group 51', pro_owner);
        raise exception 'pro shared tag group limit did not block 51st group';
    exception
        when others then
            if position('shared_tag_group_limit_reached' in sqlerrm) = 0 then
                raise;
            end if;
    end;

    pro_group := '60000000-0000-0000-0000-000000010001';
    insert into public.shared_tag_group_members (group_id, user_id, role, status)
    values (pro_group, pro_owner, 'owner', 'active');

    for i in 1..49 loop
        insert into auth.users (id)
        values (('00000000-0000-0000-0000-000000002' || lpad(i::text, 3, '0'))::uuid)
        on conflict (id) do nothing;

        insert into public.shared_tag_group_members (group_id, user_id, role, status)
        values (
            pro_group,
            ('00000000-0000-0000-0000-000000002' || lpad(i::text, 3, '0'))::uuid,
            'viewer',
            'active'
        );
    end loop;

    begin
        insert into auth.users (id)
        values ('00000000-0000-0000-0000-000000002999')
        on conflict (id) do nothing;

        insert into public.shared_tag_group_members (group_id, user_id, role, status)
        values (pro_group, '00000000-0000-0000-0000-000000002999', 'viewer', 'active');
        raise exception 'pro shared tag group member limit did not block 51st active member';
    exception
        when others then
            if position('shared_tag_group_member_limit_reached' in sqlerrm) = 0 then
                raise;
            end if;
    end;
end
$$;

select extensions.pass('plan limit policy validation');
select * from extensions.finish();
