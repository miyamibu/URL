-- Seed subscription_products with the full catalog: Google Play + App Store.
-- Product IDs follow the existing urlsaver.<plan>.<period> convention.

insert into public.subscription_products (id, plan, billing_period, store_platform, store_product_id)
values
    ('standard_monthly_google_play', 'standard', 'monthly', 'google_play', 'urlsaver.standard.monthly'),
    ('standard_yearly_google_play',  'standard', 'yearly',  'google_play', 'urlsaver.standard.yearly'),
    ('pro_monthly_google_play',      'pro',      'monthly', 'google_play', 'urlsaver.pro.monthly'),
    ('pro_yearly_google_play',       'pro',      'yearly',  'google_play', 'urlsaver.pro.yearly'),
    ('standard_monthly_app_store',   'standard', 'monthly', 'app_store',   'urlsaver.standard.monthly'),
    ('standard_yearly_app_store',    'standard', 'yearly',  'app_store',   'urlsaver.standard.yearly'),
    ('pro_monthly_app_store',        'pro',      'monthly', 'app_store',   'urlsaver.pro.monthly'),
    ('pro_yearly_app_store',         'pro',      'yearly',  'app_store',   'urlsaver.pro.yearly')
on conflict (id) do nothing;
