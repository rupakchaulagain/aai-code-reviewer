package com.fintech.aireview.llm;

import com.fintech.aireview.settings.AiReviewSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpRequest;

public class GeminiClient extends BaseLlmClient {

    public GeminiClient(AiReviewSettings.State state, String apiKey) {
        super(state, apiKey);
    }

    @Override
    public LlmProvider getProvider() {
        return LlmProvider.GEMINI;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, LlmCallContext ctx) {
        if (apiKey.isBlank()) {
            throw new LlmException("No Gemini API token configured (Settings > Tools > AI Code Reviewer).");
        }

        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", textParts(systemPrompt));

        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        userContent.add("parts", textParts(userPrompt));
        JsonArray contents = new JsonArray();
        contents.add(userContent);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", state.temperature);
        generationConfig.addProperty("maxOutputTokens", state.maxTokens);
        generationConfig.addProperty("responseMimeType", "application/json");

        JsonObject body = new JsonObject();
        body.add("system_instruction", systemInstruction);
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);

        String baseUrl = baseUrl();
        String model = model();
        String verb = state.streaming ? "streamGenerateContent?alt=sse" : "generateContent";
        String url = baseUrl + "/v1beta/models/" + model + ":" + verb;

        HttpRequest request = requestBuilder(url)
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        ctx.onTrace("POST " + baseUrl + "/v1beta/models/" + model + "  (stream=" + state.streaming + ")");
        return send(request, ctx, state.streaming);
    }

    private static JsonArray textParts(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        return parts;
    }

    @Override
    protected String parseStreamLine(String line) {
        JsonObject json = dataPayload(line);
        if (json == null) return null;
        String text = extractText(json);
        return text.isEmpty() ? null : text;
    }

    @Override
    protected String parseFullBody(String body) {
        JsonElement element = JsonParser.parseString(body);
        if (element.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement chunk : element.getAsJsonArray()) sb.append(extractText(chunk.getAsJsonObject()));
            return sb.toString();
        }
        return extractText(element.getAsJsonObject());
    }

    private static String extractText(JsonObject json) {
        JsonArray candidates = json.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) return "";
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        if (content == null) return "";
        JsonArray parts = content.getAsJsonArray("parts");
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement part : parts) {
            String t = string(part.getAsJsonObject(), "text");
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }
}
