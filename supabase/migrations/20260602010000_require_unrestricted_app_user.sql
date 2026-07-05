-- Guard function that rejects suspended/banned users from mutating RPCs.
-- Called at the top of purchase verification, shared tag ops, and invite RPCs.

create or replace function private.require_unrestricted_app_user(p_user_id uuid)
returns void
language plpgsql
stable
security definer
set search_path = private, public, pg_temp
as $$
declare
    v_status text;
begin
    select p.account_status
    into v_status
    from public.user_profiles p
    where p.user_id = require_unrestricted_app_user.p_user_id;

    if v_status in ('suspended', 'banned') then
        raise exception 'account_restricted';
    end if;
end;
$$;
revoke all on function private.require_unrestricted_app_user(uuid) from public, anon, authenticated;
-- Patch queue_store_purchase_verification to check account status.
create or replace function private.queue_store_purchase_verification(
    p_user_id uuid,
    p_store_platform text,
    p_store_product_id text,
    p_purchase_token text,
    p_store_transaction_id text default null
)
returns table (
    verification_id uuid,
    status text,
    plan text,
    billing_period text
)
language plpgsql
security definer
set search_path = private, public, pg_temp
as $$
declare
    product_row record;
    token_hash text;
    existing_owner uuid;
    verification_row private.store_purchase_verifications%rowtype;
begin
    if p_user_id is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(p_user_id);

    if length(trim(coalesce(p_purchase_token, ''))) < 16 then
        raise exception 'invalid_purchase_token';
    end if;

    select
        product.plan,
        product.billing_period
    into product_row
    from public.subscription_products product
    where product.store_platform = p_store_platform
      and product.store_product_id = p_store_product_id
      and product.is_active
    limit 1;

    if product_row is null then
        raise exception 'unknown_product';
    end if;

    token_hash := private.hash_purchase_token(p_purchase_token);

    select v.user_id
    into existing_owner
    from private.store_purchase_verifications v
    where v.store_platform = p_store_platform
      and v.purchase_token_hash = token_hash;

    if existing_owner is not null and existing_owner <> p_user_id then
        raise exception 'purchase_token_already_claimed';
    end if;

    insert into private.store_purchase_verifications (
        user_id,
        store_platform,
        store_product_id,
        plan,
        billing_period,
        store_transaction_id,
        purchase_token_hash,
        status
    )
    values (
        p_user_id,
        p_store_platform,
        p_store_product_id,
        product_row.plan,
        product_row.billing_period,
        nullif(trim(coalesce(p_store_transaction_id, '')), ''),
        token_hash,
        'pending'
    )
    on conflict (store_platform, purchase_token_hash)
    do update set
        store_product_id = excluded.store_product_id,
        plan = excluded.plan,
        billing_period = excluded.billing_period,
        store_transaction_id = coalesce(excluded.store_transaction_id, private.store_purchase_verifications.store_transaction_id),
        status = case
            when private.store_purchase_verifications.status = 'verified' then 'verified'
            else 'pending'
        end,
        rejection_reason = null
    where private.store_purchase_verifications.user_id = p_user_id
    returning * into verification_row;

    -- ON CONFLICT matched but WHERE excluded the row → different user won the race.
    if verification_row.id is null then
        raise exception 'purchase_token_already_claimed';
    end if;

    return query
    select
        verification_row.id,
        verification_row.status,
        verification_row.plan,
        verification_row.billing_period;
end;
$$;
revoke all on function private.queue_store_purchase_verification(uuid, text, text, text, text) from public, anon, authenticated;
-- Patch apply_shared_tag_ops to check account status.
create or replace function public.apply_shared_tag_ops(payload jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    op jsonb;
    v_op_id uuid;
    v_client_id uuid;
    v_op_type text;
    v_tag_id uuid;
    v_url_id uuid;
    v_member_user_id uuid;
    v_tag_name text;
    v_normalized_url text;
    v_expected_normalized_url text;
    v_raw_url text;
    v_role_text text;
    stored_result jsonb;
    result_item jsonb;
    results jsonb := '[]'::jsonb;
    affected integer;
    member_record public.shared_tag_members%rowtype;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);

    if jsonb_typeof(payload) <> 'array' then
        raise exception 'payload_must_be_array';
    end if;

    for op in
        select value
        from jsonb_array_elements(payload)
    loop
        v_op_id := (op ->> 'op_id')::uuid;
        v_client_id := (op ->> 'client_id')::uuid;
        v_op_type := op ->> 'type';
        result_item := null;

        select applied.result
        into stored_result
        from public.applied_client_ops applied
        where applied.op_id = v_op_id
          and applied.user_id = caller;

        if stored_result is not null then
            results := results || jsonb_build_array(stored_result);
            continue;
        end if;

        begin
            case v_op_type
                when 'create_tag' then
                    v_tag_id := coalesce((op ->> 'tag_id')::uuid, gen_random_uuid());
                    v_tag_name := btrim(op ->> 'name');

                    if v_tag_name is null or v_tag_name = '' or char_length(v_tag_name) > 50 then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'invalid', 'reason', 'invalid_tag_name');
                    elsif exists (select 1 from public.shared_tags where id = v_tag_id) then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'conflict', 'reason', 'tag_id_conflict', 'tag_id', v_tag_id);
                    else
                        insert into public.shared_tags (id, name, created_by)
                        values (v_tag_id, v_tag_name, caller);

                        insert into public.shared_tag_members (tag_id, user_id, role, status)
                        values (v_tag_id, caller, 'owner', 'active');

                        result_item := jsonb_build_object(
                            'op_id', v_op_id,
                            'status', 'applied',
                            'tag_id', v_tag_id
                        );
                    end if;

                when 'rename_tag' then
                    v_tag_id := (op ->> 'tag_id')::uuid;
                    v_tag_name := btrim(op ->> 'name');

                    perform private.require_tag_role(v_tag_id, caller, array['owner', 'editor']);

                    if v_tag_name is null or v_tag_name = '' or char_length(v_tag_name) > 50 then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'invalid', 'reason', 'invalid_tag_name', 'tag_id', v_tag_id);
                    else
                        update public.shared_tags
                        set name = v_tag_name
                        where id = v_tag_id
                          and deleted_at is null;

                        get diagnostics affected = row_count;
                        if affected = 0 then
                            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'not_found', 'reason', 'tag_not_found', 'tag_id', v_tag_id);
                        else
                            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'applied', 'tag_id', v_tag_id);
                        end if;
                    end if;

                when 'delete_tag' then
                    v_tag_id := (op ->> 'tag_id')::uuid;

                    perform private.require_tag_role(v_tag_id, caller, array['owner']);

                    update public.shared_tags
                    set deleted_at = now()
                    where id = v_tag_id
                      and deleted_at is null;

                    get diagnostics affected = row_count;
                    if affected = 0 then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'no_op', 'reason', 'tag_already_deleted', 'tag_id', v_tag_id);
                    else
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'applied', 'tag_id', v_tag_id);
                    end if;

                when 'add_url_to_tag' then
                    v_tag_id := (op ->> 'tag_id')::uuid;
                    v_url_id := coalesce((op ->> 'url_id')::uuid, gen_random_uuid());
                    v_raw_url := op ->> 'raw_url';
                    v_normalized_url := op ->> 'normalized_url';

                    perform private.require_tag_role(v_tag_id, caller, array['owner', 'editor']);

                    v_expected_normalized_url := public.normalize_shared_url(v_raw_url);
                    if v_expected_normalized_url is null or v_expected_normalized_url <> v_normalized_url then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'invalid', 'reason', 'normalized_url_mismatch', 'tag_id', v_tag_id);
                    else
                        insert into public.shared_tag_urls (
                            id,
                            tag_id,
                            raw_url,
                            normalized_url,
                            normalization_version,
                            added_by
                        )
                        values (
                            v_url_id,
                            v_tag_id,
                            v_raw_url,
                            v_normalized_url,
                            coalesce((op ->> 'normalization_version')::integer, 1),
                            caller
                        )
                        on conflict (tag_id, normalized_url) do update
                        set raw_url = excluded.raw_url,
                            normalization_version = excluded.normalization_version,
                            deleted_at = null,
                            added_by = excluded.added_by
                        returning public.shared_tag_urls.id into v_url_id;

                        result_item := jsonb_build_object(
                            'op_id', v_op_id,
                            'status', 'applied',
                            'tag_id', v_tag_id,
                            'url_id', v_url_id,
                            'normalized_url', v_normalized_url
                        );
                    end if;

                when 'remove_url_from_tag' then
                    v_tag_id := (op ->> 'tag_id')::uuid;
                    v_normalized_url := op ->> 'normalized_url';

                    perform private.require_tag_role(v_tag_id, caller, array['owner', 'editor']);

                    update public.shared_tag_urls
                    set deleted_at = now()
                    where shared_tag_urls.tag_id = v_tag_id
                      and shared_tag_urls.normalized_url = v_normalized_url
                      and deleted_at is null;

                    get diagnostics affected = row_count;
                    if affected = 0 then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'no_op', 'tag_id', v_tag_id, 'normalized_url', v_normalized_url);
                    else
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'applied', 'tag_id', v_tag_id, 'normalized_url', v_normalized_url);
                    end if;

                when 'invite_member' then
                    v_tag_id := (op ->> 'tag_id')::uuid;
                    v_member_user_id := (op ->> 'user_id')::uuid;
                    v_role_text := op ->> 'role';

                    perform private.require_tag_role(v_tag_id, caller, array['owner']);

                    if v_role_text not in ('editor', 'viewer') then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'invalid', 'reason', 'invalid_role', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                    elsif v_member_user_id = caller then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'invalid', 'reason', 'cannot_invite_self', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                    else
                        select member.*
                        into member_record
                        from public.shared_tag_members member
                        where member.tag_id = v_tag_id
                          and member.user_id = v_member_user_id;

                        if member_record.status = 'active' then
                            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'no_op', 'reason', 'member_already_active', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                        else
                            insert into public.shared_tag_members (tag_id, user_id, role, status)
                            values (v_tag_id, v_member_user_id, v_role_text, 'invited')
                            on conflict (tag_id, user_id) do update
                            set role = excluded.role,
                                status = 'invited';

                            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'applied', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                        end if;
                    end if;

                when 'change_member_role' then
                    v_tag_id := (op ->> 'tag_id')::uuid;
                    v_member_user_id := (op ->> 'user_id')::uuid;
                    v_role_text := op ->> 'role';

                    perform private.require_tag_role(v_tag_id, caller, array['owner']);

                    select member.*
                    into member_record
                    from public.shared_tag_members member
                    where member.tag_id = v_tag_id
                      and member.user_id = v_member_user_id;

                    if v_role_text not in ('editor', 'viewer') then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'invalid', 'reason', 'invalid_role', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                    elsif member_record.user_id is null then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'not_found', 'reason', 'member_not_found', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                    elsif member_record.status <> 'active' then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'conflict', 'reason', 'member_not_active', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                    elsif member_record.role = 'owner' then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'conflict', 'reason', 'owner_transfer_required', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                    else
                        update public.shared_tag_members
                        set role = v_role_text
                        where shared_tag_members.tag_id = v_tag_id
                          and shared_tag_members.user_id = v_member_user_id
                          and shared_tag_members.status = 'active'
                          and shared_tag_members.role <> 'owner';

                        get diagnostics affected = row_count;
                        if affected = 0 then
                            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'conflict', 'reason', 'member_role_not_changed', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                        else
                            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'applied', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                        end if;
                    end if;

                when 'remove_member' then
                    v_tag_id := (op ->> 'tag_id')::uuid;
                    v_member_user_id := (op ->> 'user_id')::uuid;

                    select member.*
                    into member_record
                    from public.shared_tag_members member
                    where member.tag_id = v_tag_id
                      and member.user_id = v_member_user_id;

                    if member_record.user_id is null then
                        if v_member_user_id = caller then
                            perform private.require_tag_role(v_tag_id, caller, array['editor', 'viewer']);
                        else
                            perform private.require_tag_role(v_tag_id, caller, array['owner']);
                        end if;
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'not_found', 'reason', 'member_not_found', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                    elsif member_record.status = 'removed' then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'no_op', 'reason', 'member_already_removed', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                    elsif member_record.role = 'owner' then
                        result_item := jsonb_build_object('op_id', v_op_id, 'status', 'conflict', 'reason', 'owner_cannot_be_removed', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                    else
                        if v_member_user_id = caller then
                            perform private.require_tag_role(v_tag_id, caller, array['editor', 'viewer']);
                        else
                            perform private.require_tag_role(v_tag_id, caller, array['owner']);
                        end if;

                        update public.shared_tag_members
                        set status = 'removed'
                        where shared_tag_members.tag_id = v_tag_id
                          and shared_tag_members.user_id = v_member_user_id
                          and shared_tag_members.status <> 'removed';

                        get diagnostics affected = row_count;
                        if affected = 0 then
                            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'no_op', 'reason', 'member_already_removed', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                        else
                            result_item := jsonb_build_object('op_id', v_op_id, 'status', 'applied', 'tag_id', v_tag_id, 'user_id', v_member_user_id);
                        end if;
                    end if;

                else
                    result_item := jsonb_build_object('op_id', v_op_id, 'status', 'invalid', 'reason', 'unsupported_op_type');
            end case;
        exception
            when others then
                if position('forbidden' in sqlerrm) > 0 then
                    result_item := jsonb_build_object('op_id', v_op_id, 'status', 'forbidden', 'reason', 'forbidden');
                else
                    raise;
                end if;
        end;

        insert into public.applied_client_ops (op_id, user_id, client_id, result)
        values (v_op_id, caller, v_client_id, result_item);

        results := results || jsonb_build_array(result_item);
    end loop;

    return jsonb_build_object('results', results);
end;
$$;
-- Patch create_shared_tag_invite to check account status.
create or replace function public.create_shared_tag_invite(
    p_tag_id uuid,
    p_role text default 'editor'
)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    invite_token text;
    invite_hash text;
    expires_at timestamptz := now() + interval '7 days';
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);

    if p_role not in ('editor', 'viewer') then
        raise exception 'invalid_invite_role';
    end if;

    perform private.require_tag_role(p_tag_id, caller, array['owner']);

    if not exists (
        select 1
        from public.shared_tags tag
        where tag.id = p_tag_id
          and tag.deleted_at is null
    ) then
        raise exception 'tag_not_found';
    end if;

    invite_token := encode(extensions.gen_random_bytes(24), 'hex');
    invite_hash := private.hash_shared_tag_invite_token(invite_token);

    insert into public.shared_tag_invites (
        token_hash,
        tag_id,
        role,
        created_by,
        expires_at
    )
    values (
        invite_hash,
        p_tag_id,
        p_role,
        caller,
        expires_at
    );

    return jsonb_build_object(
        'tag_id', p_tag_id,
        'invite_token', invite_token,
        'expires_at', expires_at,
        'role', p_role
    );
end;
$$;
-- Patch accept_shared_tag_invite to check account status.
create or replace function public.accept_shared_tag_invite(p_token text)
returns jsonb
language plpgsql
security definer
set search_path = public, private, pg_temp
as $$
declare
    caller uuid := auth.uid();
    invite_record public.shared_tag_invites%rowtype;
    existing_invite public.shared_tag_invites%rowtype;
    existing_member public.shared_tag_members%rowtype;
    tag_name text;
    applied_role text;
begin
    if caller is null then
        raise exception 'auth_required';
    end if;

    perform private.require_unrestricted_app_user(caller);

    update public.shared_tag_invites invite
    set claimed_by = caller,
        claimed_at = coalesce(invite.claimed_at, now())
    from public.shared_tags tag
    where invite.token_hash = private.hash_shared_tag_invite_token(p_token)
      and invite.tag_id = tag.id
      and invite.claimed_by is null
      and invite.revoked_at is null
      and invite.expires_at > now()
      and tag.deleted_at is null
    returning invite.* into invite_record;

    if invite_record.token_hash is null then
        select invite.*
        into existing_invite
        from public.shared_tag_invites invite
        join public.shared_tags tag
          on tag.id = invite.tag_id
        where invite.token_hash = private.hash_shared_tag_invite_token(p_token)
          and invite.revoked_at is null
          and invite.expires_at > now()
          and tag.deleted_at is null
        limit 1;

        if existing_invite.token_hash is null then
            raise exception 'invalid_or_expired_invite';
        end if;

        if existing_invite.claimed_by <> caller then
            raise exception 'invite_already_claimed';
        end if;

        select member.*
        into existing_member
        from public.shared_tag_members member
        where member.tag_id = existing_invite.tag_id
          and member.user_id = caller;

        if existing_member.status = 'active' then
            select tag.name into tag_name
            from public.shared_tags tag
            where tag.id = existing_invite.tag_id;

            return jsonb_build_object(
                'tag_id', existing_invite.tag_id,
                'tag_name', tag_name,
                'role', existing_member.role,
                'status', 'active'
            );
        end if;

        if existing_member.status = 'removed' then
            raise exception 'member_removed';
        end if;

        raise exception 'invite_already_claimed';
    end if;

    insert into public.shared_tag_members (tag_id, user_id, role, status)
    values (invite_record.tag_id, caller, invite_record.role, 'active')
    on conflict (tag_id, user_id) do update
    set role = case
            when public.shared_tag_members.role = 'owner' then public.shared_tag_members.role
            when public.shared_tag_members.status = 'removed' then public.shared_tag_members.role
            else excluded.role
        end,
        status = case
            when public.shared_tag_members.status = 'removed' then public.shared_tag_members.status
            else 'active'
        end;

    select member.*
    into existing_member
    from public.shared_tag_members member
    where member.tag_id = invite_record.tag_id
      and member.user_id = caller;

    if existing_member.status = 'removed' then
        raise exception 'member_removed';
    end if;

    select tag.name into tag_name
    from public.shared_tags tag
    where tag.id = invite_record.tag_id;

    select member.role into applied_role
    from public.shared_tag_members member
    where member.tag_id = invite_record.tag_id
      and member.user_id = caller;

    return jsonb_build_object(
        'tag_id', invite_record.tag_id,
        'tag_name', tag_name,
        'role', applied_role,
        'status', 'active'
    );
end;
$$;
