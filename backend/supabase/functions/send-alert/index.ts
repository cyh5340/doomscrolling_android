// Called by the database (private.on_alert) for each new alert row. Sends an FCM push to the
// other, unmuted members of the group. Needs secrets ALERT_SECRET and FCM_SERVICE_ACCOUNT
// (the Firebase service-account JSON); without the latter it only logs what it would send.
import { createClient } from "jsr:@supabase/supabase-js@2";

type Alert = { id: number; group_id: string; user_id: string; rule: string; streak_days: number | null };

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

Deno.serve(async (req) => {
  if (req.headers.get("x-alert-secret") !== Deno.env.get("ALERT_SECRET")) {
    return new Response("forbidden", { status: 403 });
  }
  const alert: Alert = await req.json();

  // Other alerts go to the rest of the group. The weekly stake result also goes to the person who owes it,
  // even if they muted or left the group.
  const stake = alert.rule === "weekly_stake";
  const membersQuery = db.from("group_members").select("user_id").eq("group_id", alert.group_id).eq("muted", false)
    .neq("user_id", alert.user_id);
  const [{ data: group }, { data: who }, { data: members }] = await Promise.all([
    db.from("groups").select("name, daily_alert_minutes, session_alert_minutes, stake_cents").eq("id", alert.group_id)
      .single(),
    db.from("profiles").select("display_name").eq("id", alert.user_id).single(),
    membersQuery,
  ]);
  const recipients = (members ?? []).map((m) => m.user_id);
  if (stake) recipients.push(alert.user_id);
  if (!group || !who || !recipients.length) return new Response("nothing to send");

  const { data: tokens } = await db.from("push_tokens").select("user_id, token").in("user_id", recipients);

  const name = who.display_name;
  const body = {
    daily_limit: `${name} passed ${formatMinutes(group.daily_alert_minutes)} of doomscrolling today.`,
    session_limit: `${name} has been scrolling for ${formatMinutes(group.session_alert_minutes)} straight.`,
    streak: `${name} is over the limit ${alert.streak_days} days in a row.`,
    weekly_stake: `${name} doomscrolled the most last week and owes the pot $${group.stake_cents / 100}.`,
  }[alert.rule];
  const bodyFor = (userId: string) =>
    stake && userId === alert.user_id
      ? `You doomscrolled the most last week. You owe the pot $${group.stake_cents / 100}.`
      : body;

  const serviceAccount = Deno.env.get("FCM_SERVICE_ACCOUNT");
  if (!serviceAccount) {
    console.log(`FCM not configured; would send "${body}" to ${tokens?.length ?? 0} device(s)`);
    return new Response("fcm not configured");
  }
  const sa = JSON.parse(serviceAccount);
  const accessToken = await googleAccessToken(sa);

  await Promise.all((tokens ?? []).map(async ({ user_id, token }) => {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        message: {
          token,
          notification: { title: group.name, body: bodyFor(user_id) },
          data: { groupId: alert.group_id },
          android: { priority: "high" },
        },
      }),
    });
    // Uninstalled app or rotated token: forget it.
    if (res.status === 404) await db.from("push_tokens").delete().eq("user_id", user_id).eq("token", token);
    else if (!res.ok) console.error("fcm", res.status, await res.text());
  }));

  await db.from("alerts").update({ sent_at: new Date().toISOString() }).eq("id", alert.id);
  return new Response("sent");
});

function formatMinutes(m: number) {
  return m % 60 === 0 ? `${m / 60}h` : m > 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m} min`;
}

// OAuth token for FCM from a service account, via a self-signed RS256 JWT.
async function googleAccessToken(sa: { client_email: string; private_key: string }) {
  const b64url = (bytes: Uint8Array) =>
    btoa(String.fromCharCode(...bytes)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
  const json = (o: unknown) => b64url(new TextEncoder().encode(JSON.stringify(o)));
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${json({ alg: "RS256", typ: "JWT" })}.${json({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })}`;
  const der = Uint8Array.from(atob(sa.private_key.replace(/-----[^-]+-----|\s/g, "")), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, [
    "sign",
  ]);
  const signature = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)),
  );
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${b64url(signature)}`,
    }),
  });
  return (await res.json()).access_token as string;
}
