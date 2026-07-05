-- Add original_transaction_id to store_purchase_verifications.
-- App Store subscriptions use originalTransactionId as the stable identifier
-- across renewals. This is needed to match ASNS V2 notifications to grants.

alter table private.store_purchase_verifications
add column if not exists original_transaction_id text null;
create index if not exists idx_store_purchase_verifications_original_txn
    on private.store_purchase_verifications (store_platform, original_transaction_id)
    where original_transaction_id is not null;
