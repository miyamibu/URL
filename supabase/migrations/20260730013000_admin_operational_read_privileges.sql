begin;

-- Admin Web reads these tables through its service-role server routes.
-- Client roles retain their existing RLS and privilege boundaries.
grant select on table public.admin_audit_logs to service_role;
grant select on table public.shared_content_reports to service_role;

commit;
