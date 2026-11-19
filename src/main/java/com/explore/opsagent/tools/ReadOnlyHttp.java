// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: ops-agent-canary-f59f1ff0dca746d68a51
package com.explore.opsagent.tools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The only HTTP client the tools have, and it has one verb: GET. There is no way to build a POST, PUT, PATCH or DELETE
 * through it, which is how "the agent cannot change anything" holds for HTTP: not by an instruction to the model, by
 * the absence of the method. {@code ToolRegistryTest} checks this class's public surface.
 */
public final class ReadOnlyHttp {

    public record Reply(int status, String body) {
    }

    private final HttpClient client;
    private final Duration timeout;

    public ReadOnlyHttp(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Reply get(URI uri) {
        try {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Reply(r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(0, "interrupted");
        } catch (IOException e) {
            return new Reply(0, "unreachable (" + e.getClass().getSimpleName() + ")");
        }
    }
}
