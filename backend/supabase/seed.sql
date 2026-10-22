-- Local dev only: point alert delivery at the locally served edge function.
insert into private.config (key, value) values
  ('alerts_url', 'http://kong:8000/functions/v1/send-alert'),
  ('alerts_secret', 'local-dev-secret');
