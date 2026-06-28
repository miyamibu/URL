-- Postgres grants EXECUTE on new functions to PUBLIC by default.
-- Keep anonymous preview RPCs available, but close authenticated/admin/internal
-- SECURITY DEFINER functions from anon/public execution.

revoke all on function private.active_entitlement_plan_for_user(uuid) from public;
revoke all on function private.apply_promo_delivery_event(uuid, text, text, text, text, text, timestamptz, text, jsonb) from public;
revoke all on function private.enforce_shared_tag_group_limit() from public;
revoke all on function private.enforce_shared_tag_group_member_limit() from public;
revoke all on function private.enforce_shared_tag_url_limit() from public;
revoke all on function private.require_direct_tag_owner(uuid, uuid) from public;
revoke all on function private.require_group_role(uuid, uuid, text[]) from public;
revoke all on function private.require_shared_tag_group_role(uuid, uuid, text[]) from public;
revoke all on function private.shared_tag_group_limit_for_user(uuid) from public;
revoke all on function private.shared_tag_group_member_limit_for_group(uuid) from public;
revoke all on function private.shared_tag_url_limit_for_tag(uuid) from public;

grant execute on function private.active_entitlement_plan_for_user(uuid) to service_role;
grant execute on function private.apply_promo_delivery_event(uuid, text, text, text, text, text, timestamptz, text, jsonb) to service_role;
grant execute on function private.enforce_shared_tag_group_limit() to service_role;
grant execute on function private.enforce_shared_tag_group_member_limit() to service_role;
grant execute on function private.enforce_shared_tag_url_limit() to service_role;
grant execute on function private.require_direct_tag_owner(uuid, uuid) to service_role;
grant execute on function private.require_group_role(uuid, uuid, text[]) to service_role;
grant execute on function private.require_shared_tag_group_role(uuid, uuid, text[]) to service_role;
grant execute on function private.shared_tag_group_limit_for_user(uuid) to service_role;
grant execute on function private.shared_tag_group_member_limit_for_group(uuid) to service_role;
grant execute on function private.shared_tag_url_limit_for_tag(uuid) to service_role;

revoke all on function public.accept_shared_invite(text) from public;
revoke all on function public.add_shared_tag_to_group(uuid, uuid) from public;
revoke all on function public.change_shared_tag_group_member_role(uuid, uuid, text) from public;
revoke all on function public.delete_shared_tag_group(uuid) from public;
revoke all on function public.remove_shared_tag_from_group(uuid, uuid) from public;
revoke all on function public.remove_shared_tag_group_member(uuid, uuid) from public;
revoke all on function public.rename_shared_tag_group(uuid, text) from public;
revoke all on function public.rls_auto_enable() from public;
revoke all on function public.transfer_shared_tag_group_ownership(uuid, uuid) from public;
revoke all on function public.upsert_my_shared_profile(text) from public;

grant execute on function public.accept_shared_invite(text) to authenticated;
grant execute on function public.add_shared_tag_to_group(uuid, uuid) to authenticated;
grant execute on function public.change_shared_tag_group_member_role(uuid, uuid, text) to authenticated;
grant execute on function public.delete_shared_tag_group(uuid) to authenticated;
grant execute on function public.remove_shared_tag_from_group(uuid, uuid) to authenticated;
grant execute on function public.remove_shared_tag_group_member(uuid, uuid) to authenticated;
grant execute on function public.rename_shared_tag_group(uuid, text) to authenticated;
grant execute on function public.transfer_shared_tag_group_ownership(uuid, uuid) to authenticated;
grant execute on function public.upsert_my_shared_profile(text) to authenticated;

revoke all on function public.preview_shared_invite(text) from public;
revoke all on function public.preview_shared_tag_group_invite(text) from public;
revoke all on function public.preview_shared_tag_invite(text) from public;
grant execute on function public.preview_shared_invite(text) to anon, authenticated;
grant execute on function public.preview_shared_tag_group_invite(text) to anon, authenticated;
grant execute on function public.preview_shared_tag_invite(text) to anon, authenticated;
