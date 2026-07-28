begin;

-- High-risk admin operations must be attributable to one operation and one
-- verified assurance snapshot. Historical rows remain readable, while every
-- new audit row is written through the service-role-only RPC below.
alter table public.admin_audit_logs
    add column if not exists operation_id uuid,
    add column if not exists phase text not null default 'completed',
    add column if not exists assurance jsonb not null default '{}'::jsonb;

do $$
begin
    if not exists (
        select 1
          from pg_constraint
         where conname = 'admin_audit_logs_phase_check'
           and conrelid = 'public.admin_audit_logs'::regclass
    ) then
        alter table public.admin_audit_logs
            add constraint admin_audit_logs_phase_check
            check (phase in ('started', 'completed', 'failed'));
    end if;
end
$$;

create index if not exists idx_admin_audit_logs_operation
    on public.admin_audit_logs (operation_id, created_at desc)
    where operation_id is not null;

create or replace function public.record_admin_audit(
    p_admin_user_id uuid,
    p_action text,
    p_reason text,
    p_target_user_id uuid default null,
    p_before_value jsonb default null,
    p_after_value jsonb default null,
    p_operation_id uuid default null,
    p_phase text default 'completed',
    p_assurance jsonb default '{}'::jsonb
)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    admin_record public.admin_users%rowtype;
    audit_id uuid;
    normalized_action text := nullif(left(btrim(coalesce(p_action, '')), 128), '');
    normalized_reason text := nullif(left(btrim(coalesce(p_reason, '')), 1000), '');
    normalized_phase text := lower(btrim(coalesce(p_phase, '')));
begin
    if p_admin_user_id is null then
        raise exception 'admin_required';
    end if;
    if normalized_action is null then
        raise exception 'admin_action_required';
    end if;
    if normalized_reason is null then
        raise exception 'admin_reason_required';
    end if;
    if normalized_phase not in ('started', 'completed', 'failed') then
        raise exception 'admin_audit_phase_invalid';
    end if;
    if jsonb_typeof(coalesce(p_assurance, '{}'::jsonb)) <> 'object' then
        raise exception 'admin_assurance_invalid';
    end if;

    select *
      into admin_record
      from public.admin_users
     where id = p_admin_user_id;

    if admin_record.id is null or admin_record.status <> 'active' then
        raise exception 'admin_not_active';
    end if;

    insert into public.admin_audit_logs (
        admin_user_id,
        target_user_id,
        action,
        reason,
        before_value,
        after_value,
        operation_id,
        phase,
        assurance
    )
    values (
        p_admin_user_id,
        p_target_user_id,
        normalized_action,
        normalized_reason,
        p_before_value,
        p_after_value,
        p_operation_id,
        normalized_phase,
        coalesce(p_assurance, '{}'::jsonb)
    )
    returning id into audit_id;

    return audit_id;
end;
$$;

create or replace function public.admin_update_support_request(
    p_request_id uuid,
    p_admin_id uuid,
    p_support_status text,
    p_admin_note text,
    p_reason text,
    p_operation_id uuid,
    p_assurance jsonb default '{}'::jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    admin_record public.admin_users%rowtype;
    before_record public.contact_support_requests%rowtype;
    after_record public.contact_support_requests%rowtype;
    normalized_reason text := nullif(left(btrim(coalesce(p_reason, '')), 1000), '');
    normalized_status text := lower(btrim(coalesce(p_support_status, '')));
begin
    if p_request_id is null or p_operation_id is null then
        raise exception 'admin_operation_required';
    end if;
    if normalized_reason is null then
        raise exception 'admin_reason_required';
    end if;
    if normalized_status not in ('open', 'in_progress', 'resolved', 'closed') then
        raise exception 'support_status_invalid';
    end if;

    select *
      into admin_record
      from public.admin_users
     where id = p_admin_id;
    if admin_record.id is null or admin_record.status <> 'active'
       or admin_record.role not in ('owner', 'moderator') then
        raise exception 'admin_capability_denied';
    end if;

    select *
      into before_record
      from public.contact_support_requests
     where id = p_request_id
     for update;
    if before_record.id is null then
        raise exception 'support_request_not_found';
    end if;

    update public.contact_support_requests
       set support_status = normalized_status,
           assigned_admin_id = p_admin_id,
           admin_note = left(p_admin_note, 2000)
     where id = p_request_id
     returning * into after_record;

    insert into public.admin_audit_logs (
        admin_user_id,
        action,
        reason,
        before_value,
        after_value,
        operation_id,
        phase,
        assurance
    )
    values (
        p_admin_id,
        'support_request_status_updated',
        normalized_reason,
        jsonb_build_object(
            'id', before_record.id,
            'support_status', before_record.support_status,
            'assigned_admin_id', before_record.assigned_admin_id,
            'admin_note', before_record.admin_note
        ),
        jsonb_build_object(
            'id', after_record.id,
            'support_status', after_record.support_status,
            'assigned_admin_id', after_record.assigned_admin_id,
            'admin_note', after_record.admin_note,
            'updated_at', after_record.updated_at
        ),
        p_operation_id,
        'completed',
        coalesce(p_assurance, '{}'::jsonb)
    );

    return jsonb_build_object(
        'request', jsonb_build_object(
            'id', after_record.id,
            'support_status', after_record.support_status,
            'assigned_admin_id', after_record.assigned_admin_id,
            'admin_note', after_record.admin_note,
            'updated_at', after_record.updated_at
        )
    );
end;
$$;

create or replace function public.admin_apply_moderation_action(
    p_report_id uuid,
    p_admin_id uuid,
    p_action text,
    p_reason text,
    p_operation_id uuid,
    p_assurance jsonb default '{}'::jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    admin_record public.admin_users%rowtype;
    before_record public.shared_content_reports%rowtype;
    after_record public.shared_content_reports%rowtype;
    normalized_action text := lower(btrim(coalesce(p_action, '')));
    normalized_reason text := nullif(left(btrim(coalesce(p_reason, '')), 1000), '');
    next_status text;
begin
    if p_report_id is null or p_operation_id is null then
        raise exception 'admin_operation_required';
    end if;
    if normalized_reason is null then
        raise exception 'admin_reason_required';
    end if;
    if normalized_action not in ('review', 'warn', 'hide_content', 'suspend_user', 'reject', 'close') then
        raise exception 'moderation_action_invalid';
    end if;

    next_status := case normalized_action
        when 'review' then 'reviewing'
        when 'reject' then 'rejected'
        when 'close' then 'closed'
        else 'actioned'
    end;

    select *
      into admin_record
      from public.admin_users
     where id = p_admin_id;
    if admin_record.id is null or admin_record.status <> 'active'
       or admin_record.role not in ('owner', 'moderator') then
        raise exception 'admin_capability_denied';
    end if;

    select *
      into before_record
      from public.shared_content_reports
     where id = p_report_id
     for update;
    if before_record.id is null then
        raise exception 'moderation_report_not_found';
    end if;

    update public.shared_content_reports
       set status = next_status
     where id = p_report_id
     returning * into after_record;

    insert into public.moderation_actions (
        report_id,
        admin_user_id,
        target_user_id,
        action,
        reason
    )
    values (
        p_report_id,
        p_admin_id,
        before_record.reported_user_id,
        normalized_action,
        normalized_reason
    );

    if normalized_action = 'suspend_user' and before_record.reported_user_id is not null then
        update public.user_profiles
           set account_status = 'suspended'
         where user_id = before_record.reported_user_id;
    end if;

    insert into public.admin_audit_logs (
        admin_user_id,
        target_user_id,
        action,
        reason,
        before_value,
        after_value,
        operation_id,
        phase,
        assurance
    )
    values (
        p_admin_id,
        before_record.reported_user_id,
        'moderation_' || normalized_action,
        normalized_reason,
        jsonb_build_object(
            'id', before_record.id,
            'reported_user_id', before_record.reported_user_id,
            'status', before_record.status,
            'category', before_record.category
        ),
        jsonb_build_object(
            'id', after_record.id,
            'reported_user_id', after_record.reported_user_id,
            'status', after_record.status,
            'category', after_record.category,
            'updated_at', after_record.updated_at
        ),
        p_operation_id,
        'completed',
        coalesce(p_assurance, '{}'::jsonb)
    );

    return jsonb_build_object(
        'report', jsonb_build_object(
            'id', after_record.id,
            'reported_user_id', after_record.reported_user_id,
            'status', after_record.status,
            'category', after_record.category,
            'updated_at', after_record.updated_at
        )
    );
end;
$$;

create or replace function public.admin_revoke_promo_invite_code_audited(
    p_code_id uuid,
    p_admin_id uuid,
    p_actor_user_id uuid,
    p_reason text,
    p_operation_id uuid,
    p_assurance jsonb default '{}'::jsonb,
    p_event_at timestamptz default now()
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    admin_record public.admin_users%rowtype;
    before_record public.promo_invite_codes%rowtype;
    result jsonb;
    normalized_reason text := nullif(left(btrim(coalesce(p_reason, '')), 1000), '');
begin
    if p_code_id is null or p_operation_id is null then
        raise exception 'admin_operation_required';
    end if;
    if normalized_reason is null then
        raise exception 'admin_reason_required';
    end if;

    select *
      into admin_record
      from public.admin_users
     where id = p_admin_id;
    if admin_record.id is null or admin_record.status <> 'active'
       or admin_record.role not in ('owner', 'billing') then
        raise exception 'admin_capability_denied';
    end if;

    select *
      into before_record
      from public.promo_invite_codes
     where id = p_code_id
     for update;
    if before_record.id is null then
        raise exception 'promo_code_not_found';
    end if;

    select public.admin_revoke_promo_invite_code(
        p_code_id,
        p_admin_id,
        p_actor_user_id,
        normalized_reason,
        coalesce(p_event_at, now())
    ) into result;

    insert into public.admin_audit_logs (
        admin_user_id,
        action,
        reason,
        before_value,
        after_value,
        operation_id,
        phase,
        assurance
    )
    values (
        p_admin_id,
        'promo_code_revoked',
        normalized_reason,
        jsonb_build_object(
            'code_id', before_record.id,
            'delivery_status', before_record.delivery_status,
            'revoked_at', before_record.revoked_at,
            'claimed_at', before_record.claimed_at
        ),
        jsonb_build_object(
            'code_id', p_code_id,
            'delivery_status', 'revoked',
            'revoked_at', coalesce(p_event_at, now())
        ),
        p_operation_id,
        'completed',
        coalesce(p_assurance, '{}'::jsonb)
    );

    return result;
end;
$$;

create or replace function public.admin_record_promo_email_sent_audited(
    p_code_id uuid,
    p_admin_id uuid,
    p_actor_user_id uuid,
    p_message_id text,
    p_reason text,
    p_operation_id uuid,
    p_assurance jsonb default '{}'::jsonb,
    p_event_at timestamptz default now()
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    admin_record public.admin_users%rowtype;
    before_record public.promo_invite_codes%rowtype;
    result jsonb;
    normalized_reason text := nullif(left(btrim(coalesce(p_reason, '')), 1000), '');
begin
    if p_code_id is null or p_operation_id is null then
        raise exception 'admin_operation_required';
    end if;
    if normalized_reason is null then
        raise exception 'admin_reason_required';
    end if;

    select *
      into admin_record
      from public.admin_users
     where id = p_admin_id;
    if admin_record.id is null or admin_record.status <> 'active'
       or admin_record.role not in ('owner', 'billing') then
        raise exception 'admin_capability_denied';
    end if;

    select *
      into before_record
      from public.promo_invite_codes
     where id = p_code_id
     for update;
    if before_record.id is null then
        raise exception 'promo_code_not_found';
    end if;

    select public.admin_record_promo_email_sent(
        p_code_id,
        p_actor_user_id,
        p_message_id,
        coalesce(p_event_at, now())
    ) into result;

    insert into public.admin_audit_logs (
        admin_user_id,
        action,
        reason,
        before_value,
        after_value,
        operation_id,
        phase,
        assurance
    )
    values (
        p_admin_id,
        'promo_code_issued_and_sent',
        normalized_reason,
        jsonb_build_object(
            'code_id', before_record.id,
            'delivery_status', before_record.delivery_status,
            'delivery_message_id_present', before_record.delivery_message_id is not null
        ),
        jsonb_build_object(
            'code_id', p_code_id,
            'delivery_status', 'sent',
            'delivery_message_id_present', nullif(btrim(coalesce(p_message_id, '')), '') is not null
        ),
        p_operation_id,
        'completed',
        coalesce(p_assurance, '{}'::jsonb)
    );

    return result;
end;
$$;

revoke all on function public.record_admin_audit(uuid, text, text, uuid, jsonb, jsonb, uuid, text, jsonb) from public, anon, authenticated;
revoke all on function public.admin_update_support_request(uuid, uuid, text, text, text, uuid, jsonb) from public, anon, authenticated;
revoke all on function public.admin_apply_moderation_action(uuid, uuid, text, text, uuid, jsonb) from public, anon, authenticated;
revoke all on function public.admin_revoke_promo_invite_code_audited(uuid, uuid, uuid, text, uuid, jsonb, timestamptz) from public, anon, authenticated;
revoke all on function public.admin_record_promo_email_sent_audited(uuid, uuid, uuid, text, text, uuid, jsonb, timestamptz) from public, anon, authenticated;

grant execute on function public.record_admin_audit(uuid, text, text, uuid, jsonb, jsonb, uuid, text, jsonb) to service_role;
grant execute on function public.admin_update_support_request(uuid, uuid, text, text, text, uuid, jsonb) to service_role;
grant execute on function public.admin_apply_moderation_action(uuid, uuid, text, text, uuid, jsonb) to service_role;
grant execute on function public.admin_revoke_promo_invite_code_audited(uuid, uuid, uuid, text, uuid, jsonb, timestamptz) to service_role;
grant execute on function public.admin_record_promo_email_sent_audited(uuid, uuid, uuid, text, text, uuid, jsonb, timestamptz) to service_role;

commit;
