begin;
select plan(24);

-- Week of Monday 2026-10-19. A scrolls the most; B less; C not at all. D is outside, holding the invite.
insert into auth.users (id, email, raw_user_meta_data) values
  ('00000000-0000-0000-0000-00000000000a', 'a@test', '{"display_name":"Ana"}'),
  ('00000000-0000-0000-0000-00000000000b', 'b@test', '{"display_name":"Ben"}'),
  ('00000000-0000-0000-0000-00000000000c', 'c@test', '{"display_name":"Cat"}'),
  ('00000000-0000-0000-0000-00000000000d', 'd@test', '{"display_name":"Dan"}');

create schema tests;
grant usage on schema tests to authenticated;
create function tests.as_user(uid text) returns void language sql as $$
  select set_config('role', 'authenticated', true),
         set_config('request.jwt.claims', json_build_object('sub', uid, 'role', 'authenticated')::text, true);
$$;

select tests.as_user('00000000-0000-0000-0000-00000000000a');
create temp table g on commit drop as select * from public.create_group('Crew');
grant select on g to authenticated;
select is((select stake_cents from g), 2000, 'new groups default to a $20 stake');

select tests.as_user('00000000-0000-0000-0000-00000000000b');
select is((select stake_cents from public.group_preview((select invite_code from g))), 2000,
  'an invite shows the stake before joining');
select throws_ok(format('select public.join_group(%L)', (select invite_code from g)), 'P0001', null,
  'joining a staked group needs consent');
select lives_ok(format('select public.join_group(%L, accept_stake => true)', (select invite_code from g)),
  'joining with consent works');
select throws_ok($$ update public.groups set stake_cents = 0 $$, '42501', null, 'the stake cannot be changed later');

select tests.as_user('00000000-0000-0000-0000-00000000000c');
select public.join_group((select invite_code from g), accept_stake => true);

reset role;
-- A, B and C have been in since October 1st.
update public.group_members set joined_at = '2026-10-01' where group_id = (select id from g);
insert into public.daily_usage (user_id, label, local_date, minutes) values
  ('00000000-0000-0000-0000-00000000000a', 'social', '2026-10-12', 30),
  ('00000000-0000-0000-0000-00000000000c', 'social', '2026-10-13', 10),
  ('00000000-0000-0000-0000-00000000000a', 'social', '2026-10-19', 60),
  ('00000000-0000-0000-0000-00000000000a', 'social', '2026-10-25', 60),
  ('00000000-0000-0000-0000-00000000000a', 'social', '2026-10-26', 999), -- next week, doesn't count
  ('00000000-0000-0000-0000-00000000000b', 'social', '2026-10-20', 90),
  ('00000000-0000-0000-0000-00000000000d', 'social', '2026-10-21', 500);

select tests.as_user('00000000-0000-0000-0000-00000000000b');
select results_eq(
  $$ select stake_cents, fee_cents, share_cents, members, leader_name, you_in
     from public.pot((select id from g), '2026-10-19') $$,
  $$ values (2000, 200, 900, 3, 'Ana', true) $$,
  'pot: 10% fee, the rest split between the other two, Ana on the hook');

select tests.as_user('00000000-0000-0000-0000-00000000000d');
select is((select count(*)::int from public.pot((select id from g), '2026-10-19')), 0, 'outsiders see no pot');

-- Dan joins mid-week with the most minutes: not in this week's pot.
select public.join_group((select invite_code from g), accept_stake => true);
select results_eq(
  $$ select members, leader_name, you_in from public.pot((select id from g), '2026-10-19') $$,
  $$ values (3, 'Ana', false) $$,
  'someone joining mid-week starts next Monday');

reset role;
select is(private.settle_week('2026-10-19'), 1, 'settling the week creates one settlement');
select is(private.settle_week('2026-10-19'), 0, 'settling again is a no-op');
select results_eq(
  $$ select loser_id::text, loser_minutes, fee_cents + share_cents * 2, group_name
     from public.settlements where group_id = (select id from g) $$,
  $$ values ('00000000-0000-0000-0000-00000000000a', 120, 2000, 'Crew') $$,
  'Ana owes, and fee plus shares add up to the stake');
select results_eq(
  $$ select user_id::text, amount_cents from public.settlement_shares
     join public.settlements s on s.id = settlement_id where s.group_id = (select id from g) order by user_id $$,
  $$ values ('00000000-0000-0000-0000-00000000000b', 900), ('00000000-0000-0000-0000-00000000000c', 900) $$,
  'Ben and Cat each get a share; Dan, who joined late, does not');
select is((select count(*)::int from public.alerts where rule = 'weekly_stake' and group_id = (select id from g)), 1,
  'the group is told who owes');

-- A tie at the top: nobody pays, and the week stays settled even if the tie is broken by a late upload.
insert into public.daily_usage (user_id, label, local_date, minutes) values
  ('00000000-0000-0000-0000-00000000000b', 'social', '2026-10-27', 60);
update public.daily_usage set minutes = 60 where local_date = '2026-10-26';
select is(private.settle_week('2026-10-26'), 0, 'a tie at the top settles nothing');
update public.daily_usage set minutes = 61 where local_date = '2026-10-26';
select is(private.settle_week('2026-10-26'), 0, 'a settled week is final');

-- Ana tops the week of the 12th, then leaves before it settles: she still owes it.
select tests.as_user('00000000-0000-0000-0000-00000000000a');
delete from public.group_members where group_id = (select id from g) and user_id = auth.uid();
reset role;
select is(private.settle_week('2026-10-12'), 1, 'a week settles after its top scroller left');
select results_eq(
  $$ select s.loser_id::text, count(x.user_id)::int from public.settlements s
     join public.settlement_shares x on x.settlement_id = s.id
     where s.group_id = (select id from g) and s.week_start = '2026-10-12' group by s.loser_id $$,
  $$ values ('00000000-0000-0000-0000-00000000000a', 2) $$,
  'leaving mid-week does not get you out of that week');

select tests.as_user('00000000-0000-0000-0000-00000000000a');
select is((select count(*)::int from public.settlements where loser_id = auth.uid()), 2,
  'someone who left still sees what they owe');
select is((select count(*)::int from public.pot((select id from g), '2026-10-19')), 0,
  'but no longer sees the pot');

-- The group is deleted (say its owner deleted their account): the debts stay.
reset role;
delete from public.groups where id = (select id from g);
select tests.as_user('00000000-0000-0000-0000-00000000000b');
select is((select count(*)::int from public.settlements where group_name = 'Crew' and group_id is null), 2,
  'deleting the group keeps its settlements, visible to whoever is owed');
select is((select count(*)::int from public.settlement_shares where user_id = auth.uid()), 2,
  'and their shares');

reset role;
select is((private.split(2000, 7)).fee_cents, 201, 'rounding leftovers go to the fee');
update private.config set value = '25000' where key = 'stake_fee_bps';
select results_eq($$ select fee_cents, share_cents from private.split(2000, 2) $$, $$ values (2000, 0) $$,
  'a fee over 100% is capped');
update private.config set value = '-500' where key = 'stake_fee_bps';
select results_eq($$ select fee_cents, share_cents from private.split(2000, 2) $$, $$ values (0, 1000) $$,
  'a negative fee counts as none');

select * from finish();
rollback;
