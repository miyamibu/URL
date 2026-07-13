\set ON_ERROR_STOP on

create extension if not exists pgtap with schema extensions;
select extensions.plan(5);

select extensions.ok(
    (
        select count(*) = 6
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'promo_invite_code_events'
          and column_name in (
              'code_id',
              'actor_user_id',
              'detail',
              'promo_invite_code_id',
              'user_id',
              'reason'
          )
    ),
    'promo invite event keeps both column families'
);

select extensions.ok(
    (
        select exists (
            select 1
            from pg_indexes
            where schemaname = 'public'
              and tablename = 'promo_invite_code_events'
              and indexname = 'idx_promo_invite_code_events_user'
              and indexdef like '%(user_id, created_at DESC)%'
        )
    ),
    'promo invite event has the user index'
);

select extensions.ok(
    not exists (
        select 1
        from public.promo_invite_code_events
        where code_id is not null
          and promo_invite_code_id is null
    ),
    'existing code_id values are backfilled to promo_invite_code_id'
);

select extensions.ok(
    not exists (
        select 1
        from public.promo_invite_code_events
        where actor_user_id is not null
          and user_id is null
    ),
    'existing actor_user_id values are backfilled to user_id'
);

select extensions.ok(
    not exists (
        select 1
        from public.promo_invite_code_events
        where detail ? 'reason'
          and reason is null
    ),
    'existing detail reasons are backfilled to reason'
);

select * from extensions.finish();
