package com.fintech.aireview.llm;

import com.fintech.aireview.settings.AiReviewSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public abstract class BaseLlmClient implements LlmClient {

    protected final AiReviewSettings.State state;
    protected final String apiKey;

    protected final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    protected BaseLlmClient(AiReviewSettings.State state, String apiKey) {
        this.state = state;
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    protected String baseUrl() {
        String url = state.customBaseUrl.isBlank() ? getProvider().getDefaultBaseUrl() : state.customBaseUrl;
        return url.replaceAll("/+$", "");
    }

    protected String model() {
        return state.model.isBlank() ? getProvider().getDefaultModel() : state.model;
    }

    protected HttpRequest.Builder requestBuilder(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30L, state.requestTimeoutSeconds)))
                .header("Content-Type", "application/json");
    }

    /** Parses one raw SSE line and returns the text fragment it carries, or null. */
    protected abstract String parseStreamLine(String line);

    /** Parses a complete (non streaming) JSON response body into assistant text. */
    protected abstract String parseFullBody(String body);

    protected String send(HttpRequest request, LlmCallContext ctx, boolean streaming) {
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Could not reach " + getProvider().getDisplayName() + " at " + baseUrl() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new LlmException("Could not reach " + getProvider().getDisplayName() + " at " + baseUrl() + ": " + e.getMessage(), e);
        }

        try (InputStream input = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status > 299) {
                String err = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                throw new LlmException("HTTP " + status + " from " + getProvider().getDisplayName() + ": " + take(err, 1500));
            }

            if (!streaming) {
                String text = parseFullBody(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                if (text == null || text.isBlank()) {
                    throw new LlmException("The model returned an empty response. Check the model name and API token.");
                }
                ctx.onDelta(text);
                return text;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            while (true) {
                ctx.checkCancelled();
                String line = reader.readLine();
                if (line == null) break;
                if (line.isBlank()) continue;
                String delta;
                try {
                    delta = parseStreamLine(line);
                } catch (Exception ignored) {
                    delta = null;
                }
                if (delta == null || delta.isEmpty()) continue;
                sb.append(delta);
                ctx.onDelta(delta);
            }
            if (sb.isEmpty()) {
                throw new LlmException("The model returned an empty stream. Check the model name and API token.");
            }
            ctx.onTrace("Stream finished - received " + sb.length() + " characters.");
            return sb.toString();
        } catch (IOException e) {
            throw new LlmException(e.getMessage(), e);
        }
    }

    protected static JsonObject dataPayload(String line) {
        if (!line.startsWith("data:")) return null;
        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || payload.equals("[DONE]")) return null;
        JsonElement element = JsonParser.parseString(payload);
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** Returns the string value of {@code key}, or null when it is missing or JSON null. */
    protected static String string(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    protected static String take(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
