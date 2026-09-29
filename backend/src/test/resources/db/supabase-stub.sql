-- What a Supabase project already has and setup.sql relies on, for the tests.
create schema if not exists extensions;
create schema if not exists storage;
create table if not exists storage.buckets (
    id text primary key, name text, public boolean, file_size_limit bigint, allowed_mime_types text[]);
do $$ begin create role anon nologin; exception when duplicate_object then null; end $$;
do $$ begin create role authenticated nologin; exception when duplicate_object then null; end $$;
