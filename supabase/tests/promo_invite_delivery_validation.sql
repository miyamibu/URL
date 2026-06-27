\set ON_ERROR_STOP on

create extension if not exists pgtap with schema extensions;
select extensions.plan(1);

do $$
declare
    return_type text;
begin
    select typname
      into return_type
      from pg_proc
      join pg_type on pg_type.oid = pg_proc.prorettype
     where pronamespace = 'public'::regnamespace
       and proname = 'redeem_promo_code'
     limit 1;

    if return_type <> 'jsonb' then
        raise exception 'redeem_promo_code final return type mismatch: %', return_type;
    end if;
end
$$;

select extensions.pass('promo invite delivery validation');
select * from extensions.finish();

insert into auth.users (id, email)
values
    ('00000000-0000-0000-0000-000000000021', 'owner@example.com'),
    ('00000000-0000-0000-0000-000000000022', 'reader@example.com'),
    ('00000000-0000-0000-0000-000000000023', 'other@example.com')
on conflict (id) do update set email = excluded.email;

insert into public.admin_users (id, user_id, email, role, status)
values (
    '60000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000021',
    'owner@example.com',
    'owner',
    'active'
)
on conflict (user_id) do update
set email = excluded.email,
    role = excluded.role,
    status = excluded.status;

insert into public.promo_invite_codes (
    id,
    code_hash,
    target_email,
    created_by,
    expires_at,
    delivery_status,
    sent_at,
    delivery_provider
)
values
    (
        '61000000-0000-0000-0000-000000000001',
        private.hash_promo_invite_code('RNBM VALID CODE 0001'),
        'reader@example.com',
        '60000000-0000-0000-0000-000000000001',
        now() + interval '7 days',
        'sent',
        now(),
        'resend'
    ),
    (
        '61000000-0000-0000-0000-000000000002',
        private.hash_promo_invite_code('RNBM PENDING CODE 0002'),
        'reader@example.com',
        '60000000-0000-0000-0000-000000000001',
        now() + interval '7 days',
        'pending',
        null,
        null
    ),
    (
        '61000000-0000-0000-0000-000000000003',
        private.hash_promo_invite_code('RNBM OTHER CODE 0003'),
        'other@example.com',
        '60000000-0000-0000-0000-000000000001',
        now() + interval '7 days',
        'sent',
        now(),
        'resend'
    ),
    (
        '61000000-0000-0000-0000-000000000004',
        private.hash_promo_invite_code('RNBM REVOKED CODE 0004'),
        'reader@example.com',
        '60000000-0000-0000-0000-000000000001',
        now() + interval '7 days',
        'revoked',
        now(),
        'resend'
    ),
    (
        '61000000-0000-0000-0000-000000000005',
        private.hash_promo_invite_code('RNBM EXPIRED CODE 0005'),
        'reader@example.com',
        '60000000-0000-0000-0000-000000000001',
        now() - interval '1 day',
        'sent',
        now() - interval '8 days',
        'resend'
    )
on conflict (code_hash) do nothing;

update public.promo_invite_codes
   set revoked_at = now(),
       revoked_by = '60000000-0000-0000-0000-000000000001',
       revoked_reason = 'test'
 where id = '61000000-0000-0000-0000-000000000004';

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000022', false);

do $$
declare
    first_redeem jsonb;
    second_redeem jsonb;
begin
    begin
        perform count(*) from public.promo_invite_codes;
        raise exception 'authenticated client unexpectedly selected promo invite codes';
    exception
        when insufficient_privilege then
            null;
    end;

    first_redeem := public.redeem_promo_code('rnbm valid code 0001');

    if first_redeem->>'plan' <> 'promo_pro' then
        raise exception 'valid sent promo code did not return promo_pro';
    end if;

    if first_redeem->>'source' <> 'admin_grant' then
        raise exception 'valid sent promo code did not return admin_grant source';
    end if;

    second_redeem := public.redeem_promo_code('RNBMVALIDCODE0001');

    if second_redeem->>'grant_id' <> first_redeem->>'grant_id' then
        raise exception 'second redeem did not reuse active promo grant';
    end if;

    begin
        perform public.redeem_promo_code('RNBM PENDING CODE 0002');
        raise exception 'pending promo code unexpectedly redeemed';
    exception
        when others then
            if sqlerrm not like '%promo_code_not_sent%' then
                raise exception 'pending promo code failed with unexpected error: %', sqlerrm;
            end if;
    end;

    begin
        perform public.redeem_promo_code('RNBM OTHER CODE 0003');
        raise exception 'mismatched email promo code unexpectedly redeemed';
    exception
        when others then
            if sqlerrm not like '%promo_code_email_mismatch%' then
                raise exception 'mismatched email failed with unexpected error: %', sqlerrm;
            end if;
    end;

    begin
        perform public.redeem_promo_code('RNBM REVOKED CODE 0004');
        raise exception 'revoked promo code unexpectedly redeemed';
    exception
        when others then
            if sqlerrm not like '%promo_code_not_sent%' and sqlerrm not like '%promo_code_revoked%' then
                raise exception 'revoked promo code failed with unexpected error: %', sqlerrm;
            end if;
    end;

    begin
        perform public.redeem_promo_code('RNBM EXPIRED CODE 0005');
        raise exception 'expired promo code unexpectedly redeemed';
    exception
        when others then
            if sqlerrm not like '%promo_code_expired%' then
                raise exception 'expired promo code failed with unexpected error: %', sqlerrm;
            end if;
    end;

end
$$;

reset role;

do $$
begin
    if (
        select count(*)
        from public.promo_invite_code_events
        where code_id = '61000000-0000-0000-0000-000000000001'
          and event = 'redeemed'
    ) <> 1 then
        raise exception 'redeem event count mismatch';
    end if;
end
$$;
