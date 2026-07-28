\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

do $$
declare
    rate_limit_definition text;
begin
    if not exists (
        select 1
        from pg_attribute
        where attrelid = 'public.personal_saved_links'::regclass
          and attname = 'public_safe_id'
          and not attisdropped
    ) then
        raise exception 'personal_saved_links.public_safe_id is missing';
    end if;

    if to_regprocedure('public.mcp_search_active_personal_saved_links(text,integer)') is null
       or to_regprocedure('public.mcp_list_active_personal_link_tags()') is null
       or to_regprocedure('public.consume_rinbam_mcp_rate_limit(integer,integer)') is null
       or to_regprocedure('public.mcp_search_active_personal_saved_links_for_user(uuid,text,integer)') is null
       or to_regprocedure('public.mcp_list_active_personal_link_tags_for_user(uuid)') is null
       or to_regprocedure('public.consume_rinbam_mcp_rate_limit_for_user(uuid)') is null then
        raise exception 'one or more MCP RPCs are missing';
    end if;

    if has_function_privilege(
        'anon',
        'public.consume_rinbam_mcp_rate_limit(integer,integer)',
        'EXECUTE'
    ) then
        raise exception 'anon can execute the MCP rate-limit RPC';
    end if;
    if not has_function_privilege(
        'authenticated',
        'public.consume_rinbam_mcp_rate_limit(integer,integer)',
        'EXECUTE'
    ) then
        raise exception 'authenticated cannot execute the MCP rate-limit RPC';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.consume_rinbam_mcp_rate_limit_for_user(uuid)',
        'EXECUTE'
    ) or has_function_privilege(
        'authenticated',
        'public.mcp_search_active_personal_saved_links_for_user(uuid,text,integer)',
        'EXECUTE'
    ) or has_function_privilege(
        'authenticated',
        'public.mcp_list_active_personal_link_tags_for_user(uuid)',
        'EXECUTE'
    ) then
        raise exception 'authenticated can execute a service-only MCP RPC';
    end if;
    if not has_function_privilege(
        'service_role',
        'public.consume_rinbam_mcp_rate_limit_for_user(uuid)',
        'EXECUTE'
    ) or not has_function_privilege(
        'service_role',
        'public.mcp_search_active_personal_saved_links_for_user(uuid,text,integer)',
        'EXECUTE'
    ) or not has_function_privilege(
        'service_role',
        'public.mcp_list_active_personal_link_tags_for_user(uuid)',
        'EXECUTE'
    ) then
        raise exception 'service_role cannot execute the user-bound MCP RPCs';
    end if;

    select pg_get_functiondef(
        'public.consume_rinbam_mcp_rate_limit(integer,integer)'::regprocedure
    )
    into rate_limit_definition;

    if position('window_seconds constant integer := 60' in rate_limit_definition) = 0
       or position('max_requests constant integer := 60' in rate_limit_definition) = 0 then
        raise exception 'MCP rate-limit boundary is not server-owned 60/60';
    end if;
    if position('coalesce(p_window_seconds' in rate_limit_definition) > 0
       or position('coalesce(p_max_requests' in rate_limit_definition) > 0 then
        raise exception 'MCP client arguments can still relax the rate limit';
    end if;

    if not exists (
        select 1
        from pg_indexes
        where schemaname = 'public'
          and indexname = 'idx_personal_saved_links_user_public_safe_id'
    ) then
        raise exception 'MCP opaque-id unique index is missing';
    end if;
end;
$$;

select extensions.pass('MCP ACTIVE-only and fixed rate-limit contract passed');
select * from extensions.finish();
