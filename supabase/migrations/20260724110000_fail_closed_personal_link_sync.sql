-- The current mobile clients only implement legacy per-row operations.
-- That protocol cannot express an empty snapshot or reliable deletion of rows
-- absent from a later snapshot, so keep every personal-link RPC unavailable to
-- end users until an explicit snapshot protocol is deployed.
revoke execute on function public.set_personal_link_chatgpt_sync(boolean, boolean) from public, anon, authenticated;
revoke execute on function public.apply_personal_link_ops(jsonb) from public, anon, authenticated;
revoke execute on function public.prepare_personal_link_write_action(text, text, jsonb, integer) from public, anon, authenticated;
revoke execute on function public.commit_personal_link_write_action(uuid, text) from public, anon, authenticated;
revoke execute on function public.search_personal_saved_links(text, integer) from public, anon, authenticated;
revoke execute on function public.fetch_personal_saved_link(uuid) from public, anon, authenticated;
