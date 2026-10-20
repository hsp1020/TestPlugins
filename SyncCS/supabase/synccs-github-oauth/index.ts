// SyncCS GitHub OAuth PKCE callback and single-use app handoff.
// No GitHub client secret or Supabase service_role credential is ever sent to the app.
const PROJECT = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const TABLE = PROJECT + "/rest/v1/synccs_oauth_pending";
const secureHeaders = {
  "cache-control": "no-store, private",
  "referrer-policy": "no-referrer",
  "x-content-type-options": "nosniff",
  "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
};

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status, headers: { ...secureHeaders, "content-type": "application/json; charset=utf-8" },
  });
}
function html(title: string, description: string, status = 200): Response {
  const doc = `<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SyncCS · GitHub 로그인</title><style>
body{font:16px/1.6 system-ui,sans-serif;background:#15181c;color:#f5f5f5;min-height:100vh;margin:0;display:grid;place-items:center}
main{margin:16px;padding:28px;max-width:460px;border:1px solid #484b50;border-radius:14px}
h1{font-size:22px;margin:0 0 12px}p{color:#cbd0d6}</style></head><body><main>
<h1>${title}</h1><p>${description}</p></main></body></html>`;
  return new Response(doc, { status, headers: { ...secureHeaders, "content-type": "text/html; charset=utf-8" } });
}
function validNonce(v: unknown): v is string {
  return typeof v === "string" && /^[A-Za-z0-9_-]{35,90}$/.test(v);
}
async function hashNonce(nonce: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(nonce));
  return Array.from(new Uint8Array(bytes)).map(x => x.toString(16).padStart(2, "0")).join("");
}
async function database(path: string, method: string, body?: unknown) {
  const response = await fetch(TABLE + path, {
    method,
    headers: {
      apikey: SERVICE_KEY,
      authorization: "Bearer " + SERVICE_KEY,
      accept: "application/json",
      ...(body !== undefined ? { "content-type": "application/json", prefer: "resolution=merge-duplicates,return=minimal" } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
  if (!response.ok) throw Error("Database service unavailable");
  return response;
}

Deno.serve(async (request: Request) => {
  try {
    const url = new URL(request.url);
    if (request.method === "GET") {
      const nonce = url.pathname.split("/").filter(Boolean).pop();
      const code = url.searchParams.get("code");
      const error = url.searchParams.get("error");
      if (error) {
        return html("로그인 취소 또는 실패", "CloudStream의 SyncCS 설정으로 돌아가 다시 시도해 주세요.", 400);
      }
      if (!validNonce(nonce) || !code || code.length > 2048) {
        return html("잘못된 인증 요청", "SyncCS에서 GitHub 로그인을 다시 시작해 주세요.", 400);
      }
      // Only the one-time state hash is visible to the database. Never send a code into HTML/URL.
      const hashed = await hashNonce(nonce);
      await database("?on_conflict=nonce_hash", "POST", [{
        nonce_hash: hashed, auth_code: code,
        expires_at: new Date(Date.now() + 5 * 60000).toISOString(),
      }]);
      return html("GitHub 로그인 인증 완료", "CloudStream 앱의 SyncCS 화면으로 돌아가세요. 로그인 처리가 자동으로 계속됩니다.");
    }
    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405, headers: { ...secureHeaders, allow: "GET, POST" } });
    }
    const incoming = await request.json().catch(() => null);
    if (!incoming || !validNonce(incoming.state)) return json({ error: "Invalid login state" }, 400);
    const hashed = await hashNonce(incoming.state);
    const search = "?select=auth_code,expires_at&nonce_hash=eq." + hashed + "&limit=1";
    const response = await database(search, "GET");
    const rows = await response.json() as { auth_code: string; expires_at: string }[];
    if (rows.length === 0) return json({ status: "pending" });
    const row = rows[0];
    await database("?nonce_hash=eq." + hashed, "DELETE");
    if (Date.parse(row.expires_at) < Date.now()) return json({ status: "expired" }, 410);
    return json({ status: "approved", code: row.auth_code });
  } catch (e) {
    console.error("OAuth relay failure:", e instanceof Error ? e.name : "unknown");
    return json({ error: "Temporary login service failure" }, 503);
  }
});
