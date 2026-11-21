// The developer's token endpoint for the streamcall template (example, Node 18+, Express).
// The Stream API secret stays on this server, read from the environment; it is never sent to the
// app. Install: npm install express @stream-io/node-sdk jsonwebtoken
// Stream user tokens are HS256 JWTs signed with the API secret; the refresh endpoint checks that
// signature with jsonwebtoken. Check both calls against the SDK versions you install.
import express from "express";
import jwt from "jsonwebtoken";
import { StreamClient } from "@stream-io/node-sdk";

const apiKey = process.env.STREAM_API_KEY;
const secret = process.env.STREAM_API_SECRET;
if (!apiKey || !secret) throw new Error("set STREAM_API_KEY and STREAM_API_SECRET");
const stream = new StreamClient(apiKey, secret);
const MAX_RENEW_AGE_SECONDS = 7 * 24 * 60 * 60;
const app = express();
app.use(express.json({ limit: "8kb" }));

// Your own sign-in decides who the user is. This example trusts a session your middleware set.
function signedInUser(req) {
  return req.user && req.user.id;
}

// The page calls this, then passes { apiKey, userId, token } to streamcall.configure().
app.post("/stream/token", (req, res) => {
  const userId = signedInUser(req);
  if (!userId) return res.status(401).json({ error: "sign in first" });
  const token = stream.generateUserToken({ user_id: userId, validity_in_seconds: 60 * 60 });
  res.json({ apiKey, userId, token });
});

// Optional refresh endpoint (the manifest's token_url host list must name this host). The app POSTs
// { user_id, expired_token } when a token expires, also with no page loaded. Only a token this
// server signed for that user is renewed, and only within MAX_RENEW_AGE_SECONDS after it expired:
// a token found later (in a log, on a lost device) is no longer a credential, and the user signs in
// again through /stream/token. Shorten the window to your own session policy.
app.post("/stream/refresh", (req, res) => {
  const { user_id: userId, expired_token: expired } = req.body || {};
  if (typeof userId !== "string" || typeof expired !== "string") return res.status(400).json({ error: "bad request" });
  let claims;
  try {
    claims = jwt.verify(expired, secret, { algorithms: ["HS256"], ignoreExpiration: true });
  } catch (e) {
    return res.status(401).json({ error: "not our token" });
  }
  if (!claims || claims.user_id !== userId) return res.status(401).json({ error: "not our token" });
  const now = Math.floor(Date.now() / 1000);
  if (typeof claims.exp !== "number" || now - claims.exp > MAX_RENEW_AGE_SECONDS) {
    return res.status(401).json({ error: "sign in again" });
  }
  const token = stream.generateUserToken({ user_id: userId, validity_in_seconds: 60 * 60 });
  res.json({ token });
});

app.listen(process.env.PORT || 8787);
