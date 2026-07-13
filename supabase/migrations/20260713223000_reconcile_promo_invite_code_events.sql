-- Reconcile the legacy promo event columns with the newer shared delivery shape.
-- This is a forward repair for databases that already recorded the overlapping
-- migrations before the compatibility block in 20260625120000 was added.

alter table public.promo_invite_code_events
    add column if not exists promo_invite_code_id uuid null references public.promo_invite_codes(id) on delete set null,
    add column if not exists user_id uuid null references auth.users(id) on delete set null,
    add column if not exists reason text null;

do $$
begin
    if exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'promo_invite_code_events'
          and column_name = 'code_id'
    ) then
        update public.promo_invite_code_events
           set promo_invite_code_id = code_id
         where promo_invite_code_id is null
           and code_id is not null;
    end if;

    if exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'promo_invite_code_events'
          and column_name = 'actor_user_id'
    ) then
        update public.promo_invite_code_events
           set user_id = actor_user_id
         where user_id is null
           and actor_user_id is not null;
    end if;
end
$$;

create index if not exists idx_promo_invite_code_events_user
    on public.promo_invite_code_events (user_id, created_at desc);
