// Supabase Edge Function: otp-guard
//
// Issues and verifies the 6-digit email codes used to confirm a new account.
//
// Why a function at all instead of letting the client call GoTrue directly?
//   1. Rate limiting. otp_request_gate / otp_verify_gate live in Postgres and are
//      keyed on the email address *and* the caller's IP, so a bot cannot mint
//      codes in a loop or grind a 6-digit space.
//   2. One API for both purposes. The same endpoint resends a code and verifies
//      one, so the client never needs a signed-in session to finish signup.
//   3. The service-role key never leaves the server.
//
// Flow  (POST to /functions/v1/otp-guard — the action travels in the body, since
// extra path segments after the function slug are not reliably forwarded)
//   { action: "resend", email }         -> gate, signInWithOtp, email sent
//   { action: "verify", email, code }   -> gate, verifyOtp, attempt recorded
//
// Both routes answer with `{ ok: true }` / `{ ok: false, message }` and use 200
// for handled failures so the client can show the real reason (the SDK surfaces
// non-2xx bodies poorly).

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

// Codes are 6 digits and expire in 10 minutes; five wrong guesses blocks the
// address for half an hour (enforced in Postgres).
const CODE_LENGTH = 6;

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS });
  }
  if (req.method !== "POST") {
    return json({ ok: false, message: "Use POST." }, 405);
  }

  let body: { action?: string; email?: string; code?: string } = {};
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, message: "Malformed request." }, 400);
  }

  const route = (body.action ?? "").toLowerCase();

  const email = (body.email ?? "").trim().toLowerCase();
  if (!isEmail(email)) {
    return json({ ok: false, message: "Enter a valid email address." });
  }

  const admin = createClient(SUPABASE_URL, SERVICE_ROLE, {
    auth: { autoRefreshToken: false, persistSession: false },
  });

  const ipHash = await hashIp(req);

  if (route === "resend") {
    return await handleResend(admin, email, ipHash);
  }
  if (route === "verify") {
    return await handleVerify(admin, email, ipHash, (body.code ?? "").trim());
  }
  return json({ ok: false, message: "Unknown action." }, 404);
});

// ---------------------------------------------------------------------------
// resend: mint and mail a fresh code
// ---------------------------------------------------------------------------

async function handleResend(
  admin: ReturnType<typeof createClient>,
  email: string,
  ipHash: string,
) {
  const { data: wait, error: gateError } = await admin.rpc("otp_request_gate", {
    p_email: email,
    p_ip_hash: ipHash,
  });

  if (gateError) {
    console.error("otp_request_gate", gateError);
    return json({ ok: false, message: "Verification is unavailable right now." }, 500);
  }

  const waitSeconds = Number(wait ?? 0);
  if (waitSeconds > 0) {
    return json({
      ok: false,
      message: `Please wait ${formatWait(waitSeconds)} before requesting another code.`,
      retryAfterSeconds: waitSeconds,
    });
  }

  // Signup codes come from signInWithOtp for an address that does not exist yet;
  // GoTrue creates the pending user. `shouldCreateUser` must stay true or a new
  // signup would never receive anything.
  const { error } = await admin.auth.signInWithOtp({
    email,
    options: { shouldCreateUser: true },
  });

  if (error) {
    // GoTrue's own SMTP throttle is separate from ours; surface it plainly.
    console.error("signInWithOtp", error.message);
    const friendly = /rate limit/i.test(error.message)
      ? "Too many emails sent recently. Try again in a few minutes."
      : "We couldn't send that code. Check the address and try again.";
    return json({ ok: false, message: friendly });
  }

  await admin.rpc("otp_record_request", { p_email: email, p_ip_hash: ipHash });

  return json({
    ok: true,
    message: "We sent a 6-digit code to your email.",
    codeLength: CODE_LENGTH,
    expiresInMinutes: 10,
  });
}

// ---------------------------------------------------------------------------
// verify: check the code and record the attempt
// ---------------------------------------------------------------------------

async function handleVerify(
  admin: ReturnType<typeof createClient>,
  email: string,
  ipHash: string,
  code: string,
) {
  if (!/^\d{6}$/.test(code)) {
    return json({ ok: false, message: "Enter the 6-digit code from your email." });
  }

  const { data: wait, error: gateError } = await admin.rpc("otp_verify_gate", {
    p_email: email,
  });

  if (gateError) {
    console.error("otp_verify_gate", gateError);
    return json({ ok: false, message: "Verification is unavailable right now." }, 500);
  }

  const waitSeconds = Number(wait ?? 0);
  if (waitSeconds > 0) {
    return json({
      ok: false,
      message: `Too many incorrect codes. Try again in ${formatWait(waitSeconds)}.`,
      retryAfterSeconds: waitSeconds,
    });
  }

  const { data, error } = await admin.auth.verifyOtp({
    email,
    token: code,
    type: "email",
  });

  const succeeded = !error && !!data?.session;
  await admin.rpc("otp_record_attempt", { p_email: email, p_succeeded: succeeded });

  if (!succeeded) {
    const remaining = await remainingAttempts(admin, email);
    console.error("verifyOtp", error?.message ?? "no session");
    const message = remaining > 0
      ? `That code isn't right. ${remaining} attempt${remaining === 1 ? "" : "s"} left.`
      : "That code isn't right. Request a new one.";
    return json({ ok: false, message, attemptsRemaining: remaining });
  }

  // The client gets a real session so it can finish onboarding (username,
  // display name, avatar) immediately after confirming the address.
  return json({
    ok: true,
    message: "Email confirmed.",
    accessToken: data!.session!.access_token,
    refreshToken: data!.session!.refresh_token,
    expiresAt: data!.session!.expires_at,
    userId: data!.user?.id ?? null,
  });
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

async function remainingAttempts(
  admin: ReturnType<typeof createClient>,
  email: string,
): Promise<number> {
  // `head: true` means no rows come back; the total lives on `count`.
  const { count } = await admin
    .from("otp_attempts")
    .select("id", { count: "exact", head: true })
    .eq("email", email)
    .eq("succeeded", false)
    .gte("created_at", new Date(Date.now() - 30 * 60_000).toISOString());

  return Math.max(0, 5 - Number(count ?? 0));
}

/** IPs are stored only as a salted hash; the raw address is never persisted. */
async function hashIp(req: Request): Promise<string> {
  const forwarded = req.headers.get("x-forwarded-for") ?? "";
  const ip = forwarded.split(",")[0].trim() || "unknown";
  const salt = Deno.env.get("OTP_IP_SALT") ?? "minichat";
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(`${salt}:${ip}`),
  );
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function formatWait(seconds: number): string {
  if (seconds < 60) return `${Math.max(1, Math.round(seconds))} seconds`;
  const minutes = Math.ceil(seconds / 60);
  return minutes === 1 ? "a minute" : `${minutes} minutes`;
}

function isEmail(value: string): boolean {
  return /^[^@\s]+@[^@\s.]+\.[^@\s]+$/.test(value) && value.length <= 254;
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}
