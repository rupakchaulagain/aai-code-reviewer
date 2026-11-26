package com.fintech.aireview.llm;

import com.fintech.aireview.settings.AiReviewSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;

/**
 * Works for OpenAI itself and for anything that speaks the /v1/chat/completions
 * dialect: Azure OpenAI gateways, Ollama, vLLM, LM Studio, OpenRouter, internal proxies.
 */
public class OpenAiCompatibleClient extends BaseLlmClient {

    private final LlmProvider provider;

    public OpenAiCompatibleClient(AiReviewSettings.State state, String apiKey, LlmProvider provider) {
        super(state, apiKey);
        this.provider = provider;
    }

    @Override
    public LlmProvider getProvider() {
        return provider;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, LlmCallContext ctx) {
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt);
        JsonArray messages = new JsonArray();
        messages.add(system);
        messages.add(user);

        String model = model();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", state.temperature);
        body.addProperty("max_tokens", state.maxTokens);
        body.addProperty("stream", state.streaming);
        body.add("messages", messages);

        String url = baseUrl() + "/v1/chat/completions";
        HttpRequest.Builder builder = requestBuilder(url)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);

        ctx.onTrace("POST " + url + "  (model=" + model + ", stream=" + state.streaming + ")");
        return send(builder.build(), ctx, state.streaming);
    }

    @Override
    protected String parseStreamLine(String line) {
        JsonObject json = dataPayload(line);
        if (json == null) return null;
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) return null;
        JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
        if (delta == null) return null;
        return string(delta, "content");
    }

    @Override
    protected String parseFullBody(String body) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) return "";
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null) return "";
        JsonElement content = message.get("content");
        return content == null || content.isJsonNull() ? "" : content.getAsString();
    }
}
