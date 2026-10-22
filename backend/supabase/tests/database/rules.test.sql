begin;
select plan(25);

-- A owns a group with B in it. C is an outsider.
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test', '{"display_name":"Ana"}'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test', '{"display_name":"Ben"}'),
  ('00000000-0000-0000-0000-00000000000c', 'c@test', '{"display_name":"Cat"}');

insert into public.groups (id, name, owner_id, invite_code, daily_alert_minutes, session_alert_minutes, streak_days)
values ('00000000-0000-0000-0000-0000000000f1', 'Crew', '00000000-0000-0000-0000-00000000000a', 'crewcode', 60, 45, 2);
insert into public.group_members (group_id, user_id) values
  ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000000a'),
  ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000000b');
insert into public.push_tokens (user_id, token) values ('00000000-0000-0000-0000-00000000000a', 'tok-a');

create schema tests;
grant usage on schema tests to authenticated;

create function tests.as_user(uid text) returns void language sql as $$
  select set_config('role', 'authenticated', true),
         set_config('request.jwt.claims', json_build_object('sub', uid, 'role', 'authenticated')::text, true);
$$;

create function tests.event(id int, uid text, type text, minutes int, day date) returns void language sql as $$
  insert into public.events (event_id, user_id, platform, label, type, bucket_minutes, local_date, timezone, occurred_at)
  values (('00000000-0000-0000-0000-' || lpad(id::text, 12, '0'))::uuid, uid::uuid, 'android', 'social', type, minutes,
          day, 'UTC', now())
$$;

select is((select display_name from public.profiles where id = '00000000-0000-0000-0000-00000000000a'), 'Ana',
  'profile created from sign-up metadata');

-- ---- outsider C
select tests.as_user('00000000-0000-0000-0000-00000000000c');
select is((select count(*)::int from public.groups), 0, 'outsider cannot see the group');
select is((select count(*)::int from public.group_members), 0, 'outsider cannot see members');
select is((select count(*)::int from public.profiles), 1, 'outsider sees only own profile');
select is((select count(*)::int from public.leaderboard('00000000-0000-0000-0000-0000000000f1', '2026-01-01', '2026-12-31')), 0,
  'outsider gets an empty leaderboard');
select throws_ok($$ select tests.event(1, '00000000-0000-0000-0000-00000000000a', 'bucket_reached', 15, '2026-10-20') $$,
  '42501', null, 'cannot write events as someone else');
select throws_ok($$ select public.join_group('nope') $$, 'P0002', 'invalid invite code', 'bad invite code is rejected');
select throws_ok($$ insert into public.group_members (group_id, user_id) values ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-00000000000c') $$,
  '42501', null, 'cannot add yourself to a group directly');

-- ---- B, a member
select tests.as_user('00000000-0000-0000-0000-00000000000b');
select is((select count(*)::int from public.groups), 1, 'member sees the group');
select is((select count(*)::int from public.profiles), 2, 'member sees group-mate profiles');
select is((select count(*)::int from public.push_tokens), 0, 'member cannot read others push tokens');
select throws_ok($$ update public.groups set owner_id = auth.uid() $$, '42501', null, 'cannot change columns without a grant');
update public.groups set name = 'Hacked';
select is((select name from public.groups), 'Crew', 'non-owner cannot rename the group');
select throws_ok($$ insert into public.daily_usage values ('00000000-0000-0000-0000-00000000000b', 'social', '2026-10-20', 999) $$,
  '42501', null, 'clients cannot write daily totals');
select throws_ok($$ select * from public.alerts $$, '42501', null, 'clients cannot read alerts');

-- ---- A reports usage
select tests.as_user('00000000-0000-0000-0000-00000000000a');
select tests.event(1, '00000000-0000-0000-0000-00000000000a', 'bucket_reached', 15, '2026-10-19');
select tests.event(2, '00000000-0000-0000-0000-00000000000a', 'bucket_reached', 60, '2026-10-19');
insert into public.events (event_id, user_id, platform, label, type, bucket_minutes, local_date, timezone, occurred_at)
values ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-00000000000a', 'android', 'social',
        'bucket_reached', 60, '2026-10-19', 'UTC', now())
on conflict do nothing;
select is((select count(*)::int from public.events), 2, 'resending an event is a no-op');
select tests.event(3, '00000000-0000-0000-0000-00000000000a', 'bucket_reached', 60, '2026-10-20');
select tests.event(4, '00000000-0000-0000-0000-00000000000a', 'bucket_reached', 75, '2026-10-20');
select tests.event(5, '00000000-0000-0000-0000-00000000000a', 'session_threshold', 45, '2026-10-20');
select tests.event(6, '00000000-0000-0000-0000-00000000000a', 'session_threshold', 45, '2026-10-20');

select is((select minutes from public.daily_usage where local_date = '2026-10-20'), 75, 'daily usage keeps the highest bucket');

select is(
  (select l.minutes from public.leaderboard('00000000-0000-0000-0000-0000000000f1', '2026-10-19', '2026-10-20') l
   where l.user_id = '00000000-0000-0000-0000-00000000000a'),
  135, 'week leaderboard sums days');
select is(
  (select l.streak from public.leaderboard('00000000-0000-0000-0000-0000000000f1', '2026-10-20', '2026-10-20') l
   where l.user_id = '00000000-0000-0000-0000-00000000000a'),
  2, 'leaderboard shows the streak');

reset role;
select results_eq(
  $$ select rule, local_date::text from public.alerts where group_id = '00000000-0000-0000-0000-0000000000f1'
     order by local_date, rule $$,
  $$ values ('daily_limit', '2026-10-19'), ('daily_limit', '2026-10-20'), ('session_limit', '2026-10-20'), ('streak', '2026-10-20') $$,
  'one alert per rule per day, streak fires on day 2');

-- ---- C joins with the invite code
select tests.as_user('00000000-0000-0000-0000-00000000000c');
select is(public.join_group('crewcode', accept_stake => true), '00000000-0000-0000-0000-0000000000f1'::uuid, 'join returns the group id');
select is((select count(*)::int from public.daily_usage), 2, 'new member sees group-mates usage');
select lives_ok($$ update public.group_members set muted = true where user_id = auth.uid() $$, 'member can mute');
select lives_ok($$ delete from public.group_members where user_id = auth.uid() $$, 'member can leave');

-- ---- A deletes their account
select tests.as_user('00000000-0000-0000-0000-00000000000a');
select public.delete_account();
reset role;
select is(
  (select count(*)::int from public.events where user_id = '00000000-0000-0000-0000-00000000000a')
    + (select count(*)::int from public.daily_usage where user_id = '00000000-0000-0000-0000-00000000000a')
    + (select count(*)::int from public.groups where id = '00000000-0000-0000-0000-0000000000f1')
    + (select count(*)::int from public.profiles where id = '00000000-0000-0000-0000-00000000000a'),
  0, 'account deletion removes the user''s data');

select * from finish();
rollback;
