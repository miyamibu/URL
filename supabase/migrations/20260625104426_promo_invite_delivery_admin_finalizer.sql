-- The earlier same-day migration installs the JSONB version of redeem_promo_code,
-- while an older compatibility migration later recreates the original table
-- return type. PostgreSQL cannot replace a function with a different return
-- type, so drop it before that compatibility migration runs.
drop function if exists public.redeem_promo_code(text);
