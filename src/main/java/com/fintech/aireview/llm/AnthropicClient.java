package com.fintech.aireview.llm;

import com.fintech.aireview.settings.AiReviewSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;

public class AnthropicClient extends BaseLlmClient {

    public AnthropicClient(AiReviewSettings.State state, String apiKey) {
        super(state, apiKey);
    }

    @Override
    public LlmProvider getProvider() {
        return LlmProvider.ANTHROPIC;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, LlmCallContext ctx) {
        if (apiKey.isBlank()) {
            throw new LlmException("No Anthropic API token configured (Settings > Tools > AI Code Reviewer).");
        }

        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", userPrompt);
        JsonArray content = new JsonArray();
        content.add(text);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", model());
        body.addProperty("max_tokens", state.maxTokens);
        body.addProperty("temperature", state.temperature);
        body.addProperty("system", systemPrompt);
        body.addProperty("stream", state.streaming);
        body.add("messages", messages);

        String baseUrl = baseUrl();
        HttpRequest request = requestBuilder(baseUrl + "/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        ctx.onTrace("POST " + baseUrl + "/v1/messages  (model=" + model() + ", stream=" + state.streaming + ")");
        return send(request, ctx, state.streaming);
    }

    @Override
    protected String parseStreamLine(String line) {
        JsonObject json = dataPayload(line);
        if (json == null) return null;
        if (!"content_block_delta".equals(string(json, "type"))) return null;
        JsonObject delta = json.getAsJsonObject("delta");
        if (delta == null) return null;
        return string(delta, "text");
    }

    @Override
    protected String parseFullBody(String body) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        JsonArray content = json.getAsJsonArray("content");
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement element : content) {
            JsonObject block = element.getAsJsonObject();
            if ("text".equals(string(block, "type"))) {
                String t = string(block, "text");
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
    }
}
