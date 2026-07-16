-- Existing databases may have created promo_invite_codes before the delivery
-- migrations added the updated_at column. Keep the timestamp trigger valid on
-- both fresh and already-migrated databases.
alter table public.promo_invite_codes
    add column if not exists updated_at timestamptz not null default now();

drop trigger if exists trg_promo_invite_codes_updated_at on public.promo_invite_codes;
create trigger trg_promo_invite_codes_updated_at
before update on public.promo_invite_codes
for each row
execute function private.set_updated_at();
