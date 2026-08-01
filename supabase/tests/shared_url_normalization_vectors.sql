\set ON_ERROR_STOP on

create schema if not exists extensions;
create extension if not exists pgtap with schema extensions;
select extensions.plan(16);

create temporary table shared_url_normalization_vectors (
    input text not null,
    expected_normalized_url text
);

insert into shared_url_normalization_vectors (input, expected_normalized_url)
values
    ('HTTPS://Example.COM:443/path/?a=1#frag', 'https://example.com/path?a=1'),
    ('HTTPS://Example.com:443/?q=1', 'https://example.com/?q=1'),
    ('https://example.com/path/', 'https://example.com/path'),
    ('https://example.com/path///', 'https://example.com/path'),
    ('https://example.com/path?x=1&y=2#section', 'https://example.com/path?x=1&y=2'),
    ('http://127.0.0.1:80/path', 'http://127.0.0.1/path'),
    ('http://localhost/demo/', 'http://localhost/demo'),
    ('https://www.youtube.com/watch?v=abc123&t=9', 'https://www.youtube.com/watch?v=abc123&t=9'),
    ('https://youtu.be/abc123?t=9', 'https://youtu.be/abc123?t=9'),
    ('https://x.com/openai/status/111222333?s=20', 'https://x.com/openai/status/111222333?s=20'),
    ('https://twitter.com/openai/status/111222333?s=20', 'https://twitter.com/openai/status/111222333?s=20'),
    ('example.com/path', null),
    ('http://example.com/path', null),
    ('ftp://example.com/path', null),
    ('https:///broken', null);

select extensions.is(
    public.normalize_shared_url(vector.input),
    vector.expected_normalized_url,
    'normalizes ' || vector.input
)
from shared_url_normalization_vectors vector;

select extensions.ok(
    not exists (
        select 1
        from public.shared_tag_urls url
        where url.normalization_version is distinct from 1
           or url.normalized_url is distinct from public.normalize_shared_url(url.raw_url)
    ),
    'all stored shared URLs satisfy normalization contract v1'
);

select * from extensions.finish();
