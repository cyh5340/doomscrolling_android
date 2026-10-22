-- Doomscroll backend. Clients only report events (shared/contracts/events.schema.json);
-- daily totals, alerts, streaks and leaderboards are all derived here.

create extension if not exists pg_net with schema extensions;
create extension if not exists pgcrypto with schema extensions;

create schema private;
grant usage on schema private to authenticated;

-- Server-side settings (alert webhook URL + secret). Not reachable through the API.
create table private.config (key text primary key, value text not null);

-- ---------------------------------------------------------------- users

create table public.profiles (
  id uuid primary key references auth.users on delete cascade,
  display_name text not null default 'Anonymous' check (length(display_name) between 1 and 40),
  photo_url text,
  timezone text,
  created_at timestamptz not null default now()
);

-- Kept apart from profiles so group-mates can never read someone's token.
create table public.push_tokens (
  user_id uuid primary key default auth.uid() references auth.users on delete cascade,
  token text not null,
  updated_at timestamptz not null default now()
);

create function private.on_user_created() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, display_name)
  values (new.id, coalesce(nullif(new.raw_user_meta_data ->> 'display_name', ''), 'Anonymous'));
  return new;
end $$;

create trigger on_user_created after insert on auth.users
  for each row execute function private.on_user_created();

-- ---------------------------------------------------------------- groups

create table public.groups (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(name) between 1 and 60),
  owner_id uuid not null references auth.users on delete cascade,
  invite_code text not null unique default encode(extensions.gen_random_bytes(6), 'hex'),
  label text not null default 'social',
  daily_alert_minutes int not null default 60 check (daily_alert_minutes > 0 and daily_alert_minutes % 15 = 0),
  session_alert_minutes int check (session_alert_minutes > 0 and session_alert_minutes % 15 = 0),
  streak_days int not null default 3 check (streak_days > 0),
  ranking text not null default 'most' check (ranking in ('most', 'least')),
  created_at timestamptz not null default now()
);

create table public.group_members (
  group_id uuid not null references public.groups on delete cascade,
  user_id uuid not null references auth.users on delete cascade,
  joined_at timestamptz not null default now(),
  muted boolean not null default false,
  primary key (group_id, user_id)
);
create index on public.group_members (user_id);

create function private.is_member(gid uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.group_members where group_id = gid and user_id = auth.uid())
$$;

create function private.shares_group(uid uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.group_members a join public.group_members b using (group_id)
    where a.user_id = auth.uid() and b.user_id = uid
  )
$$;

create function public.create_group(
  name text,
  label text default 'social',
  daily_alert_minutes int default 60,
  session_alert_minutes int default 45,
  streak_days int default 3,
  ranking text default 'most'
) returns public.groups
language plpgsql security definer set search_path = '' as $$
declare g public.groups;
begin
  insert into public.groups (name, owner_id, label, daily_alert_minutes, session_alert_minutes, streak_days, ranking)
  values (name, auth.uid(), label, daily_alert_minutes, session_alert_minutes, streak_days, ranking)
  returning * into g;
  insert into public.group_members (group_id, user_id) values (g.id, auth.uid());
  return g;
end $$;

create function public.join_group(code text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare gid uuid;
begin
  select id into gid from public.groups where invite_code = code;
  if gid is null then
    raise exception 'invalid invite code' using errcode = 'P0002';
  end if;
  insert into public.group_members (group_id, user_id) values (gid, auth.uid()) on conflict do nothing;
  return gid;
end $$;

-- ---------------------------------------------------------------- events

create table public.events (
  event_id uuid primary key,
  user_id uuid not null references auth.users on delete cascade,
  platform text not null check (platform in ('android', 'ios')),
  label text not null,
  type text not null check (type in ('bucket_reached', 'session_threshold')),
  bucket_minutes int not null check (bucket_minutes > 0 and bucket_minutes % 15 = 0),
  local_date date not null,
  timezone text not null,
  occurred_at timestamptz not null,
  received_at timestamptz not null default now()
);

-- Highest bucket reached per user/label/day. This is what leaderboards and streaks read.
create table public.daily_usage (
  user_id uuid not null references auth.users on delete cascade,
  label text not null,
  local_date date not null,
  minutes int not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, label, local_date)
);

-- One row per user per rule per group per day: the unique key is the rate limit.
create table public.alerts (
  id bigint generated always as identity primary key,
  group_id uuid not null references public.groups on delete cascade,
  user_id uuid not null references auth.users on delete cascade,
  rule text not null check (rule in ('daily_limit', 'session_limit', 'streak')),
  local_date date not null,
  streak_days int,
  created_at timestamptz not null default now(),
  sent_at timestamptz,
  unique (group_id, user_id, rule, local_date)
);

-- Consecutive days ending on `day` with at least `lim` minutes.
create function private.streak(uid uuid, lbl text, lim int, day date) returns int
language sql stable security definer set search_path = '' as $$
  with recursive s(d) as (
    select day where exists (
      select 1 from public.daily_usage where user_id = uid and label = lbl and local_date = day and minutes >= lim)
    union all
    select s.d - 1 from s where exists (
      select 1 from public.daily_usage where user_id = uid and label = lbl and local_date = s.d - 1 and minutes >= lim)
  )
  select count(*)::int from s
$$;

create function private.on_event() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  g public.groups;
  days int;
begin
  if new.type = 'bucket_reached' then
    insert into public.daily_usage (user_id, label, local_date, minutes)
    values (new.user_id, new.label, new.local_date, new.bucket_minutes)
    on conflict (user_id, label, local_date)
      do update set minutes = greatest(public.daily_usage.minutes, excluded.minutes), updated_at = now();
  end if;

  for g in
    select gr.* from public.groups gr join public.group_members m on m.group_id = gr.id
    where m.user_id = new.user_id and gr.label = new.label
  loop
    if new.type = 'bucket_reached' and new.bucket_minutes >= g.daily_alert_minutes then
      insert into public.alerts (group_id, user_id, rule, local_date)
      values (g.id, new.user_id, 'daily_limit', new.local_date) on conflict do nothing;
      days := private.streak(new.user_id, new.label, g.daily_alert_minutes, new.local_date);
      if days = g.streak_days then
        insert into public.alerts (group_id, user_id, rule, local_date, streak_days)
        values (g.id, new.user_id, 'streak', new.local_date, days) on conflict do nothing;
      end if;
    elsif new.type = 'session_threshold' and new.bucket_minutes >= g.session_alert_minutes then
      insert into public.alerts (group_id, user_id, rule, local_date)
      values (g.id, new.user_id, 'session_limit', new.local_date) on conflict do nothing;
    end if;
  end loop;
  return null;
end $$;

create trigger on_event after insert on public.events
  for each row execute function private.on_event();

-- Hands each new alert to the send-alert edge function, if configured.
create function private.on_alert() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  url text := (select value from private.config where key = 'alerts_url');
begin
  if url is not null then
    perform net.http_post(
      url := url,
      body := to_jsonb(new),
      headers := jsonb_build_object(
        'Content-Type', 'application/json',
        'x-alert-secret', (select value from private.config where key = 'alerts_secret'))
    );
  end if;
  return null;
end $$;

create trigger on_alert after insert on public.alerts
  for each row execute function private.on_alert();

-- ---------------------------------------------------------------- leaderboard

-- Computed on read: always consistent with events, nothing to keep in sync.
-- ponytail: fine for friend-sized groups; materialize per week if groups get large.
create function public.leaderboard(gid uuid, from_date date, to_date date)
returns table (user_id uuid, display_name text, photo_url text, minutes int, streak int)
language sql stable security definer set search_path = '' as $$
  select
    m.user_id,
    p.display_name,
    p.photo_url,
    coalesce((
      select sum(d.minutes) from public.daily_usage d
      where d.user_id = m.user_id and d.label = g.label and d.local_date between from_date and to_date
    ), 0)::int,
    -- Today only extends the streak once it's over the limit.
    greatest(
      private.streak(m.user_id, g.label, g.daily_alert_minutes, to_date),
      private.streak(m.user_id, g.label, g.daily_alert_minutes, to_date - 1))
  from public.group_members m
  join public.groups g on g.id = m.group_id
  join public.profiles p on p.id = m.user_id
  where m.group_id = gid and private.is_member(gid)
  order by 4 desc, 2
$$;

-- ---------------------------------------------------------------- account deletion

create function public.delete_account() returns void
language sql security definer set search_path = '' as $$
  delete from auth.users where id = auth.uid()
$$;

-- ---------------------------------------------------------------- access

alter table public.profiles enable row level security;
alter table public.push_tokens enable row level security;
alter table public.groups enable row level security;
alter table public.group_members enable row level security;
alter table public.events enable row level security;
alter table public.daily_usage enable row level security;
alter table public.alerts enable row level security;

create policy "own or group-mate profile" on public.profiles for select to authenticated
  using (id = auth.uid() or private.shares_group(id));
create policy "update own profile" on public.profiles for update to authenticated
  using (id = auth.uid());

create policy "own push token" on public.push_tokens for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy "members read group" on public.groups for select to authenticated
  using (private.is_member(id));
create policy "owner updates group" on public.groups for update to authenticated
  using (owner_id = auth.uid());
create policy "owner deletes group" on public.groups for delete to authenticated
  using (owner_id = auth.uid());

create policy "members read members" on public.group_members for select to authenticated
  using (private.is_member(group_id));
create policy "mute own membership" on public.group_members for update to authenticated
  using (user_id = auth.uid());
create policy "leave group" on public.group_members for delete to authenticated
  using (user_id = auth.uid());

create policy "write own events" on public.events for insert to authenticated
  with check (user_id = auth.uid());
create policy "read own events" on public.events for select to authenticated
  using (user_id = auth.uid());

create policy "own or group-mate usage" on public.daily_usage for select to authenticated
  using (user_id = auth.uid() or private.shares_group(user_id));

-- alerts: no policies, server only.

-- Column-level limits on top of RLS.
revoke all on public.profiles, public.push_tokens, public.groups, public.group_members,
  public.events, public.daily_usage, public.alerts from anon, authenticated;
grant select, update (display_name, photo_url, timezone) on public.profiles to authenticated;
grant select, insert, update, delete on public.push_tokens to authenticated;
grant select, update (name, daily_alert_minutes, session_alert_minutes, streak_days, ranking), delete
  on public.groups to authenticated;
grant select, update (muted), delete on public.group_members to authenticated;
grant select, insert on public.events to authenticated;
grant select on public.daily_usage to authenticated;

revoke execute on all functions in schema public, private from public, anon;
grant execute on function public.create_group, public.join_group, public.leaderboard, public.delete_account
  to authenticated;
grant execute on function private.is_member, private.shares_group to authenticated;

alter publication supabase_realtime add table public.daily_usage;
