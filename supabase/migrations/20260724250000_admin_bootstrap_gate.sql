-- Admin bootstrap may happen only once, while the admin table is empty.
-- The advisory lock closes the race between two first-login requests.

create or replace function public.bootstrap_first_admin(
    p_user_id uuid,
    p_email text
)
returns table (
    id uuid,
    user_id uuid,
    email text,
    role text,
    status text
)
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if p_user_id is null or nullif(trim(coalesce(p_email, '')), '') is null then
        raise exception 'invalid_admin_bootstrap';
    end if;

    perform pg_advisory_xact_lock(hashtextextended('urlsaver:admin-bootstrap', 0));

    if exists (select 1 from public.admin_users) then
        return;
    end if;

    return query
    insert into public.admin_users (user_id, email, role, status)
    values (p_user_id, lower(trim(p_email)), 'owner', 'active')
    on conflict (user_id) do nothing
    returning public.admin_users.id,
              public.admin_users.user_id,
              public.admin_users.email,
              public.admin_users.role,
              public.admin_users.status;
end;
$$;

revoke all on function public.bootstrap_first_admin(uuid, text) from public, anon, authenticated;
grant execute on function public.bootstrap_first_admin(uuid, text) to service_role;
