// MINI CHAT — mints short lived LiveKit access tokens for one-to-one and group calls.
//
// Required project secrets (Supabase Studio -> Project Settings -> Edge Functions -> Secrets):
//   LIVEKIT_URL         wss://<your-project>.livekit.cloud
//   LIVEKIT_API_KEY     API...
//   LIVEKIT_API_SECRET  <secret>
//
// The function never trusts the client: it re-checks that the authenticated user is a party to
// the call and that the call is still live before issuing a token.

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const LIVEKIT_URL = (Deno.env.get("LIVEKIT_URL") ?? "").trim();
const LIVEKIT_API_KEY = (Deno.env.get("LIVEKIT_API_KEY") ?? "").trim();
const LIVEKIT_API_SECRET = (Deno.env.get("LIVEKIT_API_SECRET") ?? "").trim();

const TOKEN_TTL_SECONDS = 60 * 60 * 2;
const UUID_RE = /^[0-9a-fA-F-]{36}$/;

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
  });
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlEncodeJson(value: unknown): string {
  return base64UrlEncode(new TextEncoder().encode(JSON.stringify(value)));
}

function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const segment = token.split(".")[1];
    const padded = segment.replace(/-/g, "+").replace(/_/g, "/");
    const decoded = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
    return JSON.parse(decoded);
  } catch (_error) {
    return null;
  }
}

async function signHs256(input: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(input));
  return base64UrlEncode(new Uint8Array(signature));
}

async function mintLiveKitToken(identity: string, name: string, room: string): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "HS256", typ: "JWT" };
  const payload = {
    iss: LIVEKIT_API_KEY,
    sub: identity,
    name: name,
    nbf: now - 10,
    exp: now + TOKEN_TTL_SECONDS,
    jti: crypto.randomUUID(),
    metadata: JSON.stringify({ app: "minichat" }),
    video: {
      roomJoin: true,
      room: room,
      canPublish: true,
      canSubscribe: true,
      canPublishData: true,
      canUpdateOwnMetadata: false,
      roomAdmin: false,
      roomCreate: false,
      roomList: false,
      hidden: false,
    },
  };
  const signingInput = `${base64UrlEncodeJson(header)}.${base64UrlEncodeJson(payload)}`;
  const signature = await signHs256(signingInput, LIVEKIT_API_SECRET);
  return `${signingInput}.${signature}`;
}

async function restGet(path: string): Promise<Record<string, unknown>[]> {
  const response = await fetch(`${SUPABASE_URL}/rest/v1/${path}`, {
    headers: {
      apikey: SERVICE_ROLE,
      Authorization: `Bearer ${SERVICE_ROLE}`,
      Accept: "application/json",
    },
  });
  if (!response.ok) {
    throw new Error(`PostgREST ${response.status}: ${await response.text()}`);
  }
  return await response.json();
}

async function displayNameFor(userId: string): Promise<string> {
  const rows = await restGet(`profiles?id=eq.${userId}&select=display_name,username`);
  const profile = (rows[0] ?? {}) as Record<string, unknown>;
  return String(profile.display_name ?? profile.username ?? "MINI CHAT user");
}

/** One-to-one call: caller may join while ringing, callee only once answered. */
async function resolveDirectCall(callId: string, userId: string) {
  const rows = await restGet(`calls?id=eq.${callId}&select=*`);
  const call = rows[0];
  if (!call) return { error: json({ error: "call_not_found" }, 404) };

  const status = String(call.status);
  const callerId = String(call.caller_id);
  const calleeId = String(call.callee_id);

  if (userId !== callerId && userId !== calleeId) {
    return { error: json({ error: "forbidden" }, 403) };
  }
  if (!["ringing", "active"].includes(status)) {
    return { error: json({ error: "call_finished", status }, 409) };
  }
  if (userId === calleeId && status !== "active") {
    return { error: json({ error: "call_not_answered" }, 409) };
  }

  return { room: String(call.room_name), callType: String(call.call_type) };
}

/** Group call: only invited participants (or the host) may join a live room. */
async function resolveGroupCall(callId: string, userId: string) {
  const rows = await restGet(`group_calls?id=eq.${callId}&select=*`);
  const call = rows[0];
  if (!call) return { error: json({ error: "call_not_found" }, 404) };

  const status = String(call.status);
  if (!["ringing", "active"].includes(status)) {
    return { error: json({ error: "call_finished", status }, 409) };
  }

  const membership = await restGet(
    `group_call_participants?call_id=eq.${callId}&user_id=eq.${userId}&select=status`,
  );
  if (!membership.length) {
    return { error: json({ error: "forbidden" }, 403) };
  }
  const memberStatus = String(membership[0].status);
  if (!["invited", "joined"].includes(memberStatus)) {
    return { error: json({ error: "forbidden", reason: "not_a_member" }, 403) };
  }

  return { room: String(call.room_name), callType: String(call.call_type) };
}

Deno.serve(async (request: Request): Promise<Response> => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: CORS_HEADERS });
  }
  if (request.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }

  let body: Record<string, unknown> = {};
  try {
    body = await request.json();
  } catch (_error) {
    body = {};
  }

  const configured = Boolean(LIVEKIT_URL && LIVEKIT_API_KEY && LIVEKIT_API_SECRET);

  if (body.action === "health") {
    return json({ configured, livekitUrl: configured ? LIVEKIT_URL : null });
  }

  if (!configured) {
    return json(
      {
        error: "calling_not_configured",
        message:
          "LiveKit credentials are missing. Add LIVEKIT_URL, LIVEKIT_API_KEY and LIVEKIT_API_SECRET as Edge Function secrets.",
      },
      503,
    );
  }

  const authorization = request.headers.get("Authorization") ?? "";
  const jwt = authorization.replace(/^Bearer\s+/i, "").trim();
  const claims = decodeJwtPayload(jwt);
  const userId = typeof claims?.sub === "string" ? claims.sub : null;
  if (!userId) {
    return json({ error: "unauthorized" }, 401);
  }

  const isGroup = typeof body.group_call_id === "string";
  const callId = isGroup ? (body.group_call_id as string) : (body.call_id as string | undefined);
  if (!callId || !UUID_RE.test(callId)) {
    return json({ error: "invalid_call_id" }, 400);
  }

  try {
    const resolved = isGroup
      ? await resolveGroupCall(callId, userId)
      : await resolveDirectCall(callId, userId);

    if ("error" in resolved && resolved.error) {
      return resolved.error;
    }

    const room = resolved.room as string;
    const token = await mintLiveKitToken(userId, await displayNameFor(userId), room);

    return json({
      url: LIVEKIT_URL,
      token,
      room,
      identity: userId,
      call_type: resolved.callType,
      scope: isGroup ? "group" : "direct",
      expires_in: TOKEN_TTL_SECONDS,
    });
  } catch (error) {
    console.error("livekit-token failed", error);
    return json({ error: "token_generation_failed", message: String(error) }, 500);
  }
});
