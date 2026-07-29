begin;

-- Qualify admin_users columns because RETURNS TABLE output parameters share
-- names with those columns. Without qualification, PL/pgSQL can reject the
-- bootstrap lookup as ambiguous at runtime.
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
declare
    existing_admin public.admin_users%rowtype;
    normalized_email text := nullif(lower(btrim(coalesce(p_email, ''))), '');
begin
    if p_user_id is null or normalized_email is null then
        raise exception 'admin_bootstrap_input_invalid';
    end if;

    perform pg_advisory_xact_lock(hashtextextended('rinbam-admin-bootstrap', 0));

    select candidate.*
      into existing_admin
      from public.admin_users as candidate
     where candidate.user_id = p_user_id
        or lower(candidate.email) = normalized_email
     order by candidate.created_at
     limit 1;

    if existing_admin.id is not null then
        if existing_admin.user_id <> p_user_id then
            raise exception 'admin_bootstrap_closed';
        end if;
        return query
        select existing_admin.id,
               existing_admin.user_id,
               existing_admin.email,
               existing_admin.role,
               existing_admin.status;
        return;
    end if;

    if exists (select 1 from public.admin_users) then
        raise exception 'admin_bootstrap_closed';
    end if;

    insert into public.admin_users (user_id, email, role, status)
    values (p_user_id, normalized_email, 'owner', 'active')
    returning public.admin_users.id,
              public.admin_users.user_id,
              public.admin_users.email,
              public.admin_users.role,
              public.admin_users.status
         into id, user_id, email, role, status;

    return next;
end;
$$;

revoke all on function public.bootstrap_first_admin(uuid, text) from public, anon, authenticated;
grant execute on function public.bootstrap_first_admin(uuid, text) to service_role;

commit;
