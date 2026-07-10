-- Tighten RPC execution privileges so default PUBLIC EXECUTE grants do not
-- leave security-definer endpoints reachable by anon unintentionally.

revoke all privileges on table public.shared_tags from public, anon;
revoke all privileges on table public.shared_tag_members from public, anon;
revoke all privileges on table public.shared_tag_urls from public, anon;
revoke all privileges on table public.shared_tag_invites from public, anon;
revoke all privileges on table public.applied_client_ops from public, anon;
revoke all privileges on table public.user_entitlement_grants from public, anon;
revoke execute on function public.normalize_shared_url(text) from public, anon, authenticated;
revoke execute on function public.apply_shared_tag_ops(jsonb) from public, anon, authenticated;
revoke execute on function public.pull_shared_tag_snapshot() from public, anon, authenticated;
revoke execute on function public.create_shared_tag_invite(uuid, text) from public, anon, authenticated;
revoke execute on function public.preview_shared_tag_invite(text) from public, anon, authenticated;
revoke execute on function public.accept_shared_tag_invite(text) from public, anon, authenticated;
revoke execute on function public.delete_my_account() from public, anon, authenticated;
revoke execute on function public.transfer_shared_tag_ownership(uuid, uuid) from public, anon, authenticated;
revoke execute on function public.get_my_entitlement_grants() from public, anon, authenticated;
revoke execute on function private.set_updated_at() from public, anon, authenticated;
revoke execute on function private.bump_shared_tag_version() from public, anon, authenticated;
revoke execute on function private.require_tag_role(uuid, uuid, text[]) from public, anon, authenticated;
revoke execute on function private.hash_shared_tag_invite_token(text) from public, anon, authenticated;
revoke execute on function private.require_single_active_shared_tag_owner() from public, anon, authenticated;
grant execute on function public.normalize_shared_url(text) to authenticated;
grant execute on function public.apply_shared_tag_ops(jsonb) to authenticated;
grant execute on function public.pull_shared_tag_snapshot() to authenticated;
grant execute on function public.create_shared_tag_invite(uuid, text) to authenticated;
grant execute on function public.preview_shared_tag_invite(text) to anon, authenticated;
grant execute on function public.accept_shared_tag_invite(text) to authenticated;
grant execute on function public.delete_my_account() to authenticated;
grant execute on function public.transfer_shared_tag_ownership(uuid, uuid) to authenticated;
grant execute on function public.get_my_entitlement_grants() to authenticated;
