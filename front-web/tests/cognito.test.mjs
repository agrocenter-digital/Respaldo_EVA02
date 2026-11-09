import assert from "node:assert/strict";
import test from "node:test";
import {
  decodeClaims,
  isAccessToken,
  isTokenExpired,
} from "../lib/cognito.ts";

function jwt(claims) {
  const header = Buffer.from(JSON.stringify({ alg: "none" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify(claims)).toString("base64url");
  return `${header}.${payload}.firma`;
}

test("solo un access token de Cognito puede viajar como Bearer", () => {
  const future = Math.floor(Date.now() / 1000) + 3600;

  const access = jwt({
    token_use: "access",
    exp: future,
    "cognito:groups": ["ADMIN"],
  });
  const idToken = jwt({ token_use: "id", exp: future, email: "admin@agrocenter.cl" });

  assert.equal(decodeClaims(access).token_use, "access");
  assert.equal(isAccessToken(access), true);
  assert.equal(isAccessToken(idToken), false);
});

test("un access token expirado no se considera vigente", () => {
  const expired = jwt({ token_use: "access", exp: 1 });
  assert.equal(isTokenExpired(expired), true);
});
