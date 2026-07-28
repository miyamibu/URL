begin;

-- Replace the worker schedule created by the earlier migration so cron sends
-- only the dedicated worker credential. The Edge Function uses its runtime
-- service-role key internally and must not receive a second copy from Vault.
do $schedule$
declare
  existing_job_id bigint;
begin
  for existing_job_id in
    select jobid
    from cron.job
    where jobname = 'contact-support-outbox-worker'
  loop
    perform cron.unschedule(existing_job_id);
  end loop;

  perform cron.schedule(
    'contact-support-outbox-worker',
    '* * * * *',
    $$
      select net.http_post(
        url := 'https://xocumgxbylmpoobfqows.supabase.co/functions/v1/contact-support-outbox',
        headers := jsonb_build_object(
          'Content-Type', 'application/json',
          'x-contact-support-worker-secret', coalesce((select decrypted_secret from vault.decrypted_secrets where name = 'CONTACT_SUPPORT_WORKER_SECRET' limit 1), '')
        ),
        body := '{}'::jsonb
      );
    $$
  );
end;
$schedule$;

commit;
