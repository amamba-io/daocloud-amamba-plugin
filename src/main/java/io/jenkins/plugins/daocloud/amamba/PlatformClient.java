package io.jenkins.plugins.daocloud.amamba;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.security.ACL;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/** Controller-only platform access. Never exposes the token to a build or follows redirects. */
public final class PlatformClient {
    public String workspaceAlias(String workspaceId, AmambaConfiguration config)
            throws IOException, InterruptedException {
        URI base;
        try {
            base = URI.create(config.getPlatformUrl());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid platform URL");
        }
        if ((!"https".equals(base.getScheme()) && !"http".equals(base.getScheme()))
                || base.getHost() == null
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null) {
            throw new IOException("Configure an HTTP(S) platform URL without credentials, query or fragment");
        }
        StringCredentials credential = CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        URIRequirementBuilder.fromUri(base.toString()).build())
                .stream()
                .filter(c -> c.getId().equals(config.getCredentialsId()))
                .findFirst()
                .orElse(null);
        if (credential == null) throw new IOException("Platform Secret Text credential is missing");
        String token = credential.getSecret().getPlainText().trim();
        if (token.isEmpty() || token.contains("\r") || token.contains("\n"))
            throw new IOException("Invalid platform token");
        URI endpoint = URI.create(
                base.toString().replaceAll("/+$", "") + "/apis/ghippo.io/v1alpha1/workspaces/" + workspaceId);
        Duration timeout = Duration.ofSeconds(config.getRequestTimeoutSeconds());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            // The original exception may contain the Authorization header.
            throw new IOException("Invalid platform token or request configuration");
        }
        HttpResponse<String> response;
        try {
            response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("Platform request failed or timed out");
        }
        if (response.statusCode() != 200) throw new IOException("Platform returned HTTP " + response.statusCode());
        try {
            Object workspace = JSONObject.fromObject(response.body()).get("workspace");
            if (workspace instanceof JSONObject ws && ws.get("alias") instanceof String alias && !alias.isBlank())
                return alias;
        } catch (RuntimeException e) {
            throw new IOException("Invalid workspace response");
        }
        throw new IOException("Workspace alias is missing or empty");
    }
}
