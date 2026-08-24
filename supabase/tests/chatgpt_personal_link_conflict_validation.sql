\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(15);

-- ---------------------------------------------------------------------------
-- Contract under test
-- ---------------------------------------------------------------------------
-- apply_personal_link_ops.upsert_link must treat normalized_url as the duplicate
-- primary key: the same user re-sending an identical normalized_url under a new
-- client_entry_id (reinstall, cross-device, Android vs iOS ID scheme) must update
-- the existing row instead of aborting the whole RPC on
-- unique (user_id, normalized_url).

do $$
begin
    if to_regprocedure('public.apply_personal_link_ops(jsonb)') is null then
        raise exception 'apply_personal_link_ops(jsonb) is missing';
    end if;

    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.personal_saved_links'::regclass
          and contype = 'u'
          and pg_get_constraintdef(oid) = 'UNIQUE (user_id, client_entry_id)'
    ) then
        raise exception 'unique (user_id, client_entry_id) constraint is missing';
    end if;

    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.personal_saved_links'::regclass
          and contype = 'u'
          and pg_get_constraintdef(oid) = 'UNIQUE (user_id, normalized_url)'
    ) then
        raise exception 'unique (user_id, normalized_url) constraint is missing';
    end if;
end;
$$;
select extensions.ok(true, 'structural contract holds');

-- ---------------------------------------------------------------------------
-- Fixture: enable sync for a synthetic user via the public RPC
-- ---------------------------------------------------------------------------
do $$
begin
    if to_regprocedure('public.set_personal_link_chatgpt_sync(boolean,boolean)') is null then
        raise exception 'set_personal_link_chatgpt_sync RPC is missing';
    end if;
end;
$$;
select extensions.ok(true, 'sync settings RPC exists');

begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '77777777-7777-7777-7777-777777777777', true);
drop table if exists sync_enable_result;
create temp table sync_enable_result as
select public.set_personal_link_chatgpt_sync(true, false) as g;
commit;
reset role;

select extensions.is(
    (select count(*) from public.personal_link_sync_settings
     where user_id = '77777777-7777-7777-7777-777777777777' and enabled),
    1::bigint,
    'chatgpt personal link sync enabled for test user'
);

-- ---------------------------------------------------------------------------
-- Scenario 1: different client_entry_id, same normalized_url -> single row upsert
-- ---------------------------------------------------------------------------
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '77777777-7777-7777-7777-777777777777', true);
drop table if exists ops_first_batch;
create temp table ops_first_batch as
select public.apply_personal_link_ops(
    '{
      "ops": [
        {
          "op_id": "88888888-0000-0000-0000-000000000001",
          "type": "upsert_link",
          "client_entry_id": "safe-ios-row10",
          "url": "https://Example.com/x",
          "normalized_url": "https://example.com/x",
          "title": "first snapshot",
          "tags": []
        },
        {
          "op_id": "88888888-0000-0000-0000-000000000002",
          "type": "upsert_link",
          "client_entry_id": "entry-android-hashx",
          "url": "https://example.com/x",
          "normalized_url": "https://example.com/x",
          "effective_title": "second snapshot",
          "memo": "from other device",
          "tags": []
        }
      ]
    }'::jsonb
) as result;
commit;

select extensions.is(
    (select count(*) from (
        select jsonb_array_elements((result -> 'results')) as item
        from ops_first_batch
     ) items
     where item ->> 'status' = 'ok'),
    2::bigint,
    'both conflicting-client-entry-id upserts succeed'
);

select extensions.is(
    (select (jsonb_array_elements((result -> 'results')) ->> 'link_id')
     from ops_first_batch limit 1),
    (select (jsonb_array_elements((result -> 'results')) ->> 'link_id')
     from ops_first_batch offset 1 limit 1),
    'conflicting client_entry_ids converge onto one existing link id'
);

select extensions.is(
    (select count(*) from public.personal_saved_links
     where user_id = '77777777-7777-7777-7777-777777777777'
       and normalized_url = 'https://example.com/x'),
    1::bigint,
    'same normalized_url keeps exactly one row per user'
);

select extensions.is(
    (select effective_title from public.personal_saved_links
     where user_id = '77777777-7777-7777-7777-777777777777'
       and normalized_url = 'https://example.com/x'),
    'second snapshot',
    'latest snapshot content wins (one-directional snapshot sync)'
);

select extensions.is(
    (select client_entry_id from public.personal_saved_links
     where user_id = '77777777-7777-7777-7777-777777777777'
       and normalized_url = 'https://example.com/x'),
    'entry-android-hashx',
    'normalized-url conflict records the latest client entry id'
);

-- ---------------------------------------------------------------------------
-- Scenario 2: op_id replay stays idempotent and never duplicates rows
-- ---------------------------------------------------------------------------
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '77777777-7777-7777-7777-777777777777', true);
drop table if exists ops_replay;
create temp table ops_replay as
select public.apply_personal_link_ops(
    '{
      "ops": [
        {
          "op_id": "88888888-0000-0000-0000-000000000001",
          "type": "upsert_link",
          "client_entry_id": "safe-ios-row10",
          "url": "https://example.com/x",
          "normalized_url": "https://example.com/x",
          "title": "replayed snapshot",
          "tags": []
        }
      ]
    }'::jsonb
) as result;
commit;

select extensions.is(
    (select count(*) from public.personal_saved_links
     where user_id = '77777777-7777-7777-7777-777777777777'
       and normalized_url = 'https://example.com/x'),
    1::bigint,
    'replayed op does not duplicate the link row'
);

select extensions.is(
    (select effective_title from public.personal_saved_links
     where user_id = '77777777-7777-7777-7777-777777777777'
       and normalized_url = 'https://example.com/x'),
    'second snapshot',
    'replayed op returns the stored first-run result instead of reapplying'
);

-- ---------------------------------------------------------------------------
-- Scenario 3: distinct normalized_urls still produce distinct rows
-- ---------------------------------------------------------------------------
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '77777777-7777-7777-7777-777777777777', true);
drop table if exists ops_second_url;
create temp table ops_second_url as
select public.apply_personal_link_ops(
    '{
      "ops": [
        {
          "op_id": "88888888-0000-0000-0000-000000000003",
          "type": "upsert_link",
          "client_entry_id": "safe-ios-row11",
          "url": "https://example.com/y",
          "normalized_url": "https://example.com/y",
          "title": "other page",
          "tags": []
        }
      ]
    }'::jsonb
) as result;
commit;

select extensions.is(
    (select count(*) from public.personal_saved_links
     where user_id = '77777777-7777-7777-7777-777777777777'),
    2::bigint,
    'distinct normalized_urls remain separate links'
);

-- ---------------------------------------------------------------------------
-- Scenario 4: a complete snapshot prunes stale rows only in its own namespace
-- ---------------------------------------------------------------------------
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '77777777-7777-7777-7777-777777777777', true);
drop table if exists ops_android_seed;
create temp table ops_android_seed as
select public.apply_personal_link_ops(
    '{
      "ops": [
        {
          "op_id": "88888888-0000-0000-0000-000000000004",
          "type": "upsert_link",
          "client_entry_id": "entry-android-keep",
          "url": "https://example.com/android-keep",
          "normalized_url": "https://example.com/android-keep",
          "title": "keep",
          "tags": []
        },
        {
          "op_id": "88888888-0000-0000-0000-000000000005",
          "type": "upsert_link",
          "client_entry_id": "entry-android-stale",
          "url": "https://example.com/android-stale",
          "normalized_url": "https://example.com/android-stale",
          "title": "stale",
          "tags": []
        }
      ]
    }'::jsonb
) as result;
commit;

begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '77777777-7777-7777-7777-777777777777', true);
drop table if exists ops_android_next_snapshot;
create temp table ops_android_next_snapshot as
select public.apply_personal_link_ops(
    '{
      "ops": [
        {
          "op_id": "88888888-0000-0000-0000-000000000006",
          "type": "upsert_link",
          "client_entry_id": "entry-android-keep",
          "url": "https://example.com/android-keep",
          "normalized_url": "https://example.com/android-keep",
          "title": "keep next snapshot",
          "tags": []
        }
      ]
    }'::jsonb
) as result;
commit;

select extensions.ok(
    (select deleted_at is null from public.personal_saved_links
     where user_id = '77777777-7777-7777-7777-777777777777'
       and client_entry_id = 'entry-android-keep'),
    'current entry in the same namespace remains active'
);

select extensions.ok(
    (select deleted_at is not null from public.personal_saved_links
     where user_id = '77777777-7777-7777-7777-777777777777'
       and client_entry_id = 'entry-android-stale'),
    'missing entry in the same namespace is soft-deleted'
);

select extensions.ok(
    (select deleted_at is null from public.personal_saved_links
     where user_id = '77777777-7777-7777-7777-777777777777'
       and client_entry_id = 'safe-ios-row11'),
    'a snapshot does not prune a different client namespace'
);

select extensions.ok(true, 'all conflict-contract scenarios completed');
