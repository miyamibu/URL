revoke execute on function public.get_my_account_status() from anon;
revoke execute on function public.get_my_public_profile() from anon;
revoke execute on function public.upsert_my_profile(text, text) from anon;
grant execute on function public.get_my_account_status() to authenticated;
grant execute on function public.get_my_public_profile() to authenticated;
grant execute on function public.upsert_my_profile(text, text) to authenticated;
