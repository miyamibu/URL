\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

begin;

do $$
declare
    first_request_id text;
    duplicate_request_id text;
    first_request_row_id uuid;
    runtime_outbox_id uuid;
    runtime_outbox_payload jsonb;
    runtime_payload_hash text := repeat('a', 64);
    runtime_lease_token uuid;
    duplicate_already_accepted boolean;
    request_count integer;
    outbox_count integer;
    inbox_count integer;
    duplicate boolean;
    matched boolean;
    updated boolean;
    delivery_status text;
begin
    select request_id, request_row_id
    into first_request_id, first_request_row_id
    from public.enqueue_contact_support_request(
        '11111111-1111-4111-8111-111111111111',
        'mobile:android',
        'runtime-key-123456',
        'email-hash-runtime',
        null,
        'ip-hash-runtime',
        'android',
        'test',
        'debug',
        jsonb_build_object(
            'email', 'runtime@example.com',
            'name', 'Runtime User',
            'message', 'runtime test',
            'source', 'mobile:android',
            'platform', 'android',
            'appVersion', 'test',
            'buildType', 'debug',
            'isSignedIn', false
        ),
        runtime_payload_hash
    );

    select request_id, already_accepted
    into duplicate_request_id, duplicate_already_accepted
    from public.enqueue_contact_support_request(
        '22222222-2222-4222-8222-222222222222',
        'mobile:android',
        'runtime-key-123456',
        'email-hash-runtime',
        null,
        'ip-hash-runtime',
        'android',
        'test',
        'debug',
        jsonb_build_object(
            'email', 'runtime@example.com',
            'name', 'Runtime User',
            'message', 'runtime test',
            'source', 'mobile:android',
            'platform', 'android',
            'appVersion', 'test',
            'buildType', 'debug',
            'isSignedIn', false
        ),
        runtime_payload_hash
    );

    if duplicate_request_id <> first_request_id or not duplicate_already_accepted then
        raise exception 'idempotent retry did not return the original accepted request';
    end if;

    select count(*) into request_count
    from public.contact_support_requests
    where source = 'mobile:android'
      and idempotency_key = 'runtime-key-123456';
    if request_count <> 1 then
        raise exception 'idempotent retry inserted % request rows', request_count;
    end if;

    select count(*) into outbox_count
    from public.contact_support_outbox
    where source = 'mobile:android'
      and idempotency_key = 'runtime-key-123456';
    if outbox_count <> 1 then
        raise exception 'idempotent retry inserted % outbox rows', outbox_count;
    end if;

    select outbox_id, payload
    into runtime_outbox_id, runtime_outbox_payload
    from public.claim_contact_support_outbox_batch(10, 600)
    where request_id = first_request_id;
    select lease_token
    into runtime_lease_token
    from public.contact_support_outbox
    where id = runtime_outbox_id;
    if runtime_outbox_id is null
       or runtime_outbox_payload->>'algorithm' <> 'AES-256-GCM'
       or runtime_outbox_payload->>'version' <> '1' then
        raise exception 'outbox claim did not return an encrypted form payload';
    end if;
    if runtime_lease_token is null then
        raise exception 'outbox claim did not return a lease token';
    end if;

    perform public.complete_contact_support_outbox(
        runtime_outbox_id,
        first_request_id,
        'email-runtime-1',
        runtime_lease_token
    );

    if exists (
        select 1
        from public.contact_support_outbox
        where id = runtime_outbox_id
          and payload <> '{}'::jsonb
    ) then
        raise exception 'sent outbox payload was not scrubbed';
    end if;

    select * into duplicate, matched, updated
    from public.record_contact_support_delivery_event(
        'resend',
        'svix-runtime-1',
        'email-runtime-1',
        'email.sent',
        'sent',
        clock_timestamp() + interval '1 second',
        null
    );
    if duplicate or not matched or not updated then
        raise exception 'initial webhook event was not applied';
    end if;

    select * into duplicate, matched, updated
    from public.record_contact_support_delivery_event(
        'resend',
        'svix-runtime-1',
        'email-runtime-1',
        'email.delivered',
        'delivered',
        clock_timestamp() + interval '2 seconds',
        null
    );
    if not duplicate then
        raise exception 'duplicate svix event was inserted twice';
    end if;

    select * into duplicate, matched, updated
    from public.record_contact_support_delivery_event(
        'resend',
        'svix-runtime-2',
        'email-runtime-1',
        'email.delivered',
        'delivered',
        clock_timestamp() + interval '3 seconds',
        null
    );
    if duplicate or not matched or not updated then
        raise exception 'delivered webhook event was not applied';
    end if;

    select * into duplicate, matched, updated
    from public.record_contact_support_delivery_event(
        'resend',
        'svix-runtime-3',
        'email-runtime-1',
        'email.sent',
        'sent',
        clock_timestamp() + interval '4 seconds',
        null
    );
    if duplicate or not matched or updated then
        raise exception 'lower delivery status regressed the request';
    end if;

    select * into duplicate, matched, updated
    from public.record_contact_support_delivery_event(
        'resend',
        'svix-runtime-4',
        'email-runtime-1',
        'email.failed',
        'failed',
        clock_timestamp() + interval '5 seconds',
        'runtime failure'
    );
    if duplicate or not matched or not updated then
        raise exception 'terminal delivery status was not applied';
    end if;

    select * into duplicate, matched, updated
    from public.record_contact_support_delivery_event(
        'resend',
        'svix-runtime-5',
        'email-runtime-1',
        'email.delivered',
        'delivered',
        clock_timestamp() + interval '6 seconds',
        null
    );
    if duplicate or not matched or updated then
        raise exception 'late lower delivery status regressed a terminal status';
    end if;

    select r.delivery_status into delivery_status
    from public.contact_support_requests r
    where id = first_request_row_id;
    if delivery_status <> 'failed' then
        raise exception 'final delivery status is %, expected failed', delivery_status;
    end if;

    select count(*) into inbox_count
    from public.contact_support_delivery_event_inbox
    where provider = 'resend'
      and email_id = 'email-runtime-1';
    if inbox_count <> 5 then
        raise exception 'expected 5 unique webhook inbox events, got %', inbox_count;
    end if;
end;
$$;

rollback;

select extensions.pass('contact support idempotency and delivery runtime passed');
select * from extensions.finish();
