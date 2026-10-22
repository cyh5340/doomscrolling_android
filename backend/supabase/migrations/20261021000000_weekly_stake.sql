-- Weekly stake: whoever doomscrolls the most in a group over a week (Monday–Sunday, each member's local dates)
-- owes the group's stake. After the app's processing fee, it's split evenly among the other members.
-- A week counts the people who were in the group when it started (Monday 00:00 UTC): someone joining mid-week
-- starts next Monday, and leaving mid-week doesn't get anyone out of that week.
-- This is a ledger only: collecting and paying out money needs a payment provider (see TODO.md).

create extension if not exists pg_cron;

-- Processing fee in basis points (1000 = 10%). Server-wide, so it lives in private.config.
insert into private.config (key, value) values ('stake_fee_bps', '1000') on conflict do nothing;

-- Existing groups start at $0: their members never agreed to a stake. New groups default to $20.
alter table public.groups
  add column stake_cents int not null default 0 check (stake_cents between 0 and 50000 and stake_cents % 100 = 0);
alter table public.groups alter column stake_cents set default 2000;

-- Debts outlive the group: deleting it mustn't wipe what someone owes.
create table public.settlements (
  id bigint generated always as identity primary key,
  group_id uuid references public.groups on delete set null,
  group_name text not null,
  week_start date not null check (extract(isodow from week_start) = 1),
  loser_id uuid references auth.users on delete set null,
  loser_minutes int not null,
  stake_cents int not null,
  fee_cents int not null,
  share_cents int not null,
  paid_at timestamptz, -- set by the payment provider's webhook once collected
  created_at timestamptz not null default now(),
  unique (group_id, week_start)
);

create table public.settlement_shares (
  settlement_id bigint not null references public.settlements on delete cascade,
  user_id uuid not null references auth.users on delete cascade,
  amount_cents int not null,
  primary key (settlement_id, user_id)
);

-- Who left which group, so a week still counts them. No foreign keys: rows are written while a group or user is
-- being deleted. week_members() skips deleted users; settle_week() prunes old rows.
create table private.departures (
  group_id uuid not null,
  user_id uuid not null,
  joined_at timestamptz not null,
  left_at timestamptz not null default now()
);
create index on private.departures (group_id);

create function private.on_member_deleted() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into private.departures (group_id, user_id, joined_at) values (old.group_id, old.user_id, old.joined_at);
  return null;
end $$;
create trigger on_member_deleted after delete on public.group_members
  for each row execute function private.on_member_deleted();

-- Weeks already settled (or found to have nobody to settle), so a result is final and a missed run can catch up.
create table private.settled_weeks (week_start date primary key, settled_at timestamptz not null default now());

alter table public.alerts drop constraint alerts_rule_check;
alter table public.alerts add constraint alerts_rule_check
  check (rule in ('daily_limit', 'session_limit', 'streak', 'weekly_stake'));

-- Fee and per-person share for a stake split among `others` people. Rounding leftovers go to the fee,
-- so fee + share * others = stake exactly.
create function private.split(stake int, others int, out fee_cents int, out share_cents int)
language sql stable security definer set search_path = '' as $$
  with s as (
    select (stake - stake * least(greatest(coalesce(
             (select value::int from private.config where key = 'stake_fee_bps'), 0), 0), 10000) / 10000)
           / greatest(others, 1) as share
  )
  select stake - share * others, share from s
$$;

-- Who's in the pot for the week starting `week_start`: in the group since before it began, including anyone who
-- left after it began.
create function private.week_members(gid uuid, week_start date) returns setof uuid
language sql stable security definer set search_path = '' as $$
  select user_id from public.group_members where group_id = gid and joined_at < week_start::timestamptz
  union
  select d.user_id from private.departures d
  where d.group_id = gid and d.joined_at < week_start::timestamptz and d.left_at >= week_start::timestamptz
    and exists (select 1 from auth.users u where u.id = d.user_id)
$$;

-- The member on the hook for the week starting `week_start`: most minutes, strictly ahead of second place.
-- Null when nobody scrolled or the top is tied (nobody pays on a tie).
create function private.week_loser(gid uuid, week_start date, out user_id uuid, out minutes int)
language sql stable security definer set search_path = '' as $$
  with totals as (
    select m.user_id, coalesce(sum(d.minutes), 0)::int as minutes
    from private.week_members(gid, week_start) m(user_id)
    join public.groups g on g.id = gid
    left join public.daily_usage d
      on d.user_id = m.user_id and d.label = g.label and d.local_date between week_start and week_start + 6
    group by m.user_id
  ), ranked as (
    select t.*, lead(t.minutes) over (order by t.minutes desc) as runner_up from totals t order by t.minutes desc limit 1
  )
  select r.user_id, r.minutes from ranked r where r.minutes > 0 and r.minutes > coalesce(r.runner_up, 0)
$$;

-- What the pot looks like for a group that week. Members only. `members` counts the people in that week's pot;
-- `you_in` is false for someone who joined after it started.
create function public.pot(gid uuid, week_start date)
returns table (stake_cents int, fee_cents int, share_cents int, members int, leader_id uuid, leader_name text,
  you_in boolean)
language sql stable security definer set search_path = '' as $$
  with w as (select private.week_members(gid, week_start) as user_id), g as (
    select gr.stake_cents, (select count(*)::int from w) as members,
           exists (select 1 from w where w.user_id = auth.uid()) as you_in
    from public.groups gr where gr.id = gid and private.is_member(gid)
  )
  select g.stake_cents, s.fee_cents, s.share_cents, g.members, l.user_id, p.display_name, g.you_in
  from g
  cross join private.split(g.stake_cents, g.members - 1) s
  left join private.week_loser(gid, week_start) l on true
  left join public.profiles p on p.id = l.user_id
$$;

-- Settles every staked group for the week starting `week_start`. Runs once per week: later calls return 0, so
-- late uploads or membership changes never reopen a result.
create function private.settle_week(week_start date) returns int
language plpgsql security definer set search_path = '' as $$
declare
  g public.groups;
  loser record;
  others int;
  s record;
  sid bigint;
  settled int := 0;
begin
  insert into private.settled_weeks values (week_start) on conflict do nothing;
  if not found then
    return 0;
  end if;
  for g in select * from public.groups where stake_cents > 0 loop
    select count(*) - 1 into others from private.week_members(g.id, week_start);
    continue when others < 1;
    select * into loser from private.week_loser(g.id, week_start);
    continue when loser.user_id is null;
    select * into s from private.split(g.stake_cents, others);
    continue when s.share_cents < 1; -- e.g. a $1 stake across 90+ people: nothing to hand out

    insert into public.settlements
      (group_id, group_name, week_start, loser_id, loser_minutes, stake_cents, fee_cents, share_cents)
    values (g.id, g.name, week_start, loser.user_id, loser.minutes, g.stake_cents, s.fee_cents, s.share_cents)
    on conflict do nothing
    returning id into sid;
    continue when sid is null;

    insert into public.settlement_shares (settlement_id, user_id, amount_cents)
    select sid, m.user_id, s.share_cents from private.week_members(g.id, week_start) m(user_id)
    where m.user_id <> loser.user_id;

    insert into public.alerts (group_id, user_id, rule, local_date)
    values (g.id, loser.user_id, 'weekly_stake', week_start) on conflict do nothing;
    settled := settled + 1;
  end loop;
  -- Older than the catch-up window below: no week can need these any more.
  delete from private.departures where left_at < now() - interval '6 weeks';
  return settled;
end $$;

-- Daily at 12:00 UTC, settling any of the last 4 finished weeks not settled yet. Normally that's last week, on
-- Monday, when Sunday is over in every time zone; the rest catches up after a paused project or a failed run.
select cron.schedule('settle-weekly-stakes', '0 12 * * *', $$
  select private.settle_week((date_trunc('week', current_date) - make_interval(weeks => w))::date)
  from generate_series(4, 1, -1) w
$$);

-- ---------------------------------------------------------------- joining needs consent to the stake

-- Lets someone holding an invite see what they'd join before joining.
create function public.group_preview(code text)
returns table (id uuid, name text, stake_cents int, members int)
language sql stable security definer set search_path = '' as $$
  select g.id, g.name, g.stake_cents, (select count(*)::int from public.group_members where group_id = g.id)
  from public.groups g where g.invite_code = code
$$;

drop function public.join_group(text);
create function public.join_group(code text, accept_stake boolean default false) returns uuid
language plpgsql security definer set search_path = '' as $$
declare g public.groups;
begin
  select * into g from public.groups where invite_code = code;
  if g.id is null then
    raise exception 'invalid invite code' using errcode = 'P0002';
  end if;
  if g.stake_cents > 0 and not accept_stake then
    raise exception 'joining requires accepting the weekly stake' using errcode = 'P0001';
  end if;
  insert into public.group_members (group_id, user_id) values (g.id, auth.uid()) on conflict do nothing;
  return g.id;
end $$;

drop function public.create_group(text, text, int, int, int, text);
create function public.create_group(
  name text,
  label text default 'social',
  daily_alert_minutes int default 60,
  session_alert_minutes int default 45,
  streak_days int default 3,
  ranking text default 'most',
  stake_cents int default 2000
) returns public.groups
language plpgsql security definer set search_path = '' as $$
declare g public.groups;
begin
  insert into public.groups (name, owner_id, label, daily_alert_minutes, session_alert_minutes, streak_days, ranking, stake_cents)
  values (name, auth.uid(), label, daily_alert_minutes, session_alert_minutes, streak_days, ranking, stake_cents)
  returning * into g;
  insert into public.group_members (group_id, user_id) values (g.id, auth.uid());
  return g;
end $$;

-- ---------------------------------------------------------------- access

alter table public.settlements enable row level security;
alter table public.settlement_shares enable row level security;

-- Group members, plus whoever owes or is owed, even after leaving. A definer function, since the two tables'
-- policies would otherwise reference each other.
create function private.sees_settlement(sid bigint) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.settlements s where s.id = sid and (
      private.is_member(s.group_id) or s.loser_id = auth.uid()
      or exists (select 1 from public.settlement_shares x where x.settlement_id = s.id and x.user_id = auth.uid())
    )
  )
$$;

create policy "involved read settlements" on public.settlements for select to authenticated
  using (private.sees_settlement(id));
create policy "involved read shares" on public.settlement_shares for select to authenticated
  using (private.sees_settlement(settlement_id));

-- Read-only for clients. stake_cents is deliberately not updatable: members agreed to the amount when joining.
revoke all on public.settlements, public.settlement_shares from anon, authenticated;
grant select on public.settlements, public.settlement_shares to authenticated;

revoke execute on all functions in schema public, private from public, anon;
grant execute on function public.create_group, public.join_group, public.group_preview, public.pot to authenticated;
grant execute on function private.sees_settlement to authenticated;
