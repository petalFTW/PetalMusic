/*
 * Copyright 2022 devgianlu
 * SPDX-License-Identifier: Apache-2.0
 *
 * Login5 exchange adapted from librespot-java's TokenProvider (dev branch),
 * retaining the scope-taking API used by the bundled 1.6.5 classes.
 */
package xyz.gianlu.librespot.core;

import com.google.protobuf.ByteString;
import com.spotify.login5v3.Credentials;
import com.spotify.login5v3.Login5;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public final class TokenProvider {
    private final Session session;
    private StoredToken token;

    TokenProvider(Session session) {
        this.session = session;
    }

    public synchronized StoredToken getToken(String... scopes) throws IOException {
        if (scopes.length == 0) throw new IllegalArgumentException("No scopes requested");
        // Login5 grants a session token, rather than separate Mercury tokens
        // per requested scope. Reuse it until its expiry, as current upstream does.
        if (token != null && !token.expired()) return token;
        try {
            Login5.LoginResponse response = new Login5Api(session).login5(
                Login5.LoginRequest.newBuilder()
                    .setStoredCredential(Credentials.StoredCredential.newBuilder()
                        .setUsername(session.username())
                        .setData(ByteString.copyFrom(
                            session.apWelcome().getReusableAuthCredentials().toByteArray()))
                        .build())
                    .build());
            if (!response.hasOk()) {
                throw new IOException("Spotify Login5 rejected session: " + response.getError());
            }
            Login5.LoginOk ok = response.getOk();
            if (ok.getAccessToken().isEmpty() || ok.getAccessTokenExpiresIn() <= 0) {
                throw new IOException("Spotify Login5 returned an invalid token");
            }
            token = new StoredToken(ok.getAccessToken(), ok.getAccessTokenExpiresIn(), scopes);
            return token;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Spotify Login5 challenge failed", e);
        }
    }

    public String get(String scope) throws IOException {
        return getToken(scope).accessToken;
    }

    public static class StoredToken {
        public final int expiresIn;
        public final String accessToken;
        public final String[] scopes;
        public final long timestamp;

        private StoredToken(String accessToken, int expiresIn, String[] scopes) {
            this.accessToken = accessToken;
            this.expiresIn = expiresIn;
            this.scopes = scopes.clone();
            timestamp = TimeProvider.currentTimeMillis();
        }

        public boolean expired() {
            return timestamp + (expiresIn - 10L) * 1000L < TimeProvider.currentTimeMillis();
        }

        public boolean hasScope(String scope) {
            return Arrays.asList(scopes).contains(scope);
        }

        public boolean hasScopes(String[] requested) {
            for (String scope : requested) if (!hasScope(scope)) return false;
            return true;
        }

        @Override public String toString() {
            return "StoredToken{expiresIn=" + expiresIn + ", timestamp=" + timestamp + "}";
        }
    }
}
