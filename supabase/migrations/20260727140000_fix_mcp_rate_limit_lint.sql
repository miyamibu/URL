-- Qualify the bucket column in the rate-limit update. Without the table
-- qualifier PostgreSQL treats request_count as ambiguous against the
-- PL/pgSQL output variable and rejects the function during schema lint.

create or replace function public.consume_rinbam_mcp_rate_limit(
    p_window_seconds integer default 60,
    p_max_requests integer default 60
)
returns table(allowed boolean, retry_after_seconds integer, remaining integer)
language plpgsql
security definer
set search_path = public, private, extensions, pg_temp
as $$
declare
    caller uuid := auth.uid();
    window_seconds integer := greatest(1, least(coalesce(p_window_seconds, 60), 3600));
    max_requests integer := greatest(1, least(coalesce(p_max_requests, 60), 1000));
    window_started timestamptz;
    request_count integer;
    now_time timestamptz := clock_timestamp();
    retry_seconds integer;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(caller::text, 0));
    select bucket.window_started_at, bucket.request_count
    into window_started, request_count
    from private.rinbam_mcp_rate_limit_buckets bucket
    where bucket.user_id = caller
    for update;

    if not found or now_time >= window_started + make_interval(secs => window_seconds) then
        insert into private.rinbam_mcp_rate_limit_buckets (user_id, window_started_at, request_count)
        values (caller, now_time, 1)
        on conflict (user_id) do update
        set window_started_at = excluded.window_started_at,
            request_count = excluded.request_count;
        return query select true, 0, max_requests - 1;
        return;
    end if;

    if request_count >= max_requests then
        retry_seconds := greatest(
            1,
            ceil(extract(epoch from (window_started + make_interval(secs => window_seconds) - now_time)))::integer
        );
        return query select false, retry_seconds, 0;
        return;
    end if;

    update private.rinbam_mcp_rate_limit_buckets as bucket
    set request_count = bucket.request_count + 1
    where bucket.user_id = caller;
    return query select true, 0, max_requests - request_count - 1;
end;
$$;

revoke all on function public.consume_rinbam_mcp_rate_limit(integer, integer) from public, anon;
grant execute on function public.consume_rinbam_mcp_rate_limit(integer, integer) to authenticated;
