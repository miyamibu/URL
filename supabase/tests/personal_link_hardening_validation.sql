\set ON_ERROR_STOP on

select extensions.plan(20);

select extensions.ok(exists (
    select 1 from pg_constraint
    where conname = 'personal_link_sync_settings_user_id_fkey' and confdeltype = 'c'
), 'personal sync settings cascade with auth deletion');
select extensions.ok(exists (
    select 1 from pg_constraint
    where conname = 'personal_saved_links_user_id_fkey' and confdeltype = 'c'
), 'personal saved links cascade with auth deletion');
select extensions.ok(exists (
    select 1 from pg_constraint
    where conname = 'personal_saved_link_tags_user_id_fkey' and confdeltype = 'c'
), 'personal tags cascade with auth deletion');
select extensions.ok(exists (
    select 1 from pg_constraint
    where conname = 'personal_saved_link_tag_refs_user_id_fkey' and confdeltype = 'c'
), 'personal tag refs cascade with auth deletion');
select extensions.ok(exists (
    select 1 from pg_constraint
    where conname = 'personal_link_enrichment_cache_user_id_fkey' and confdeltype = 'c'
), 'personal enrichment cache cascade with auth deletion');
select extensions.ok(exists (
    select 1 from pg_constraint
    where conname = 'personal_link_applied_client_ops_user_id_fkey' and confdeltype = 'c'
), 'personal applied ops cascade with auth deletion');
select extensions.ok(exists (
    select 1 from pg_constraint
    where conname = 'personal_link_pending_write_actions_user_id_fkey' and confdeltype = 'c'
), 'personal pending actions cascade with auth deletion');
select extensions.ok(not has_function_privilege(
    'authenticated', 'public.set_personal_link_chatgpt_sync(boolean,boolean)', 'execute'
), 'legacy personal sync enable RPC is not callable');
select extensions.ok(not has_function_privilege(
    'authenticated', 'public.apply_personal_link_ops(jsonb)', 'execute'
), 'legacy personal sync apply RPC is not callable');
select extensions.ok(position('split_part(v_client_entry_id' in pg_get_functiondef(
    'public.apply_personal_link_ops(jsonb)'::regprocedure
)) = 0, 'legacy prefix-based deletion is absent');
select extensions.ok(position('personal_data_counts' in pg_get_functiondef(
    'public.delete_my_account()'::regprocedure
)) > 0, 'account deletion returns personal data counts');
select extensions.ok(position('delete_my_account_legacy' in pg_get_functiondef(
    'public.delete_my_account()'::regprocedure
)) > 0, 'account deletion delegates only after counting owned data');
select extensions.ok(position('set role = ''owner''' in pg_get_functiondef(
    'public.delete_my_account()'::regprocedure
)) > 0, 'account deletion transfers shared-tag ownership before legacy deletion');
select extensions.ok(position('set role = ''editor''' in pg_get_functiondef(
    'public.delete_my_account()'::regprocedure
)) > 0, 'account deletion demotes the deleted shared-tag owner before legacy deletion');
select extensions.ok(position('update public.shared_tag_group_members member' in pg_get_functiondef(
    'public.delete_my_account()'::regprocedure
)) > 0, 'account deletion transfers shared-tag-group ownership before legacy deletion');
select extensions.ok(position('shared_user_profiles' in pg_get_functiondef(
    'public.delete_my_account()'::regprocedure
)) > 0, 'account deletion removes shared profile data');
select extensions.ok(position('shared_content_reports' in pg_get_functiondef(
    'public.delete_my_account()'::regprocedure
)) > 0, 'account deletion scrubs moderation report identities');
select extensions.ok(position('moderation_actions' in pg_get_functiondef(
    'public.delete_my_account()'::regprocedure
)) > 0, 'account deletion scrubs moderation targets');
select extensions.ok(position('shared_tag_group_tags' in pg_get_functiondef(
    'public.delete_my_account()'::regprocedure
)) > 0, 'account deletion scrubs group tag attribution');
select extensions.ok(position('shared_tag_urls' in pg_get_functiondef(
    'public.delete_my_account()'::regprocedure
)) > 0, 'account deletion scrubs URL attribution');
