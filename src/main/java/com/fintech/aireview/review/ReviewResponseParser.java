package com.fintech.aireview.review;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;

public final class ReviewResponseParser {

    private static final Gson GSON = new Gson();

    private ReviewResponseParser() {
    }

    /**
     * Models occasionally wrap the JSON in a markdown fence or add a sentence before it.
     * Extract the outermost balanced JSON object and parse that.
     */
    public static ReviewResult parse(String raw) {
        String json = extractJsonObject(raw);
        if (json == null) return fallback(raw, "The model did not return JSON.");
        try {
            ReviewResult result = GSON.fromJson(json, ReviewResult.class);
            return result != null ? result : fallback(raw, "Empty response.");
        } catch (JsonSyntaxException e) {
            return fallback(raw, "The JSON returned by the model could not be parsed: " + e.getMessage());
        }
    }

    private static ReviewResult fallback(String raw, String reason) {
        ReviewResult result = new ReviewResult();
        result.setSummary(reason);
        result.setRiskLevel("UNKNOWN");
        Finding finding = new Finding();
        finding.setSeverity("SHOULD");
        finding.setTitle("Raw model output");
        finding.setCategory("Design");
        finding.setExplanation(raw.length() <= 8000 ? raw : raw.substring(0, 8000));
        List<Finding> findings = new ArrayList<>();
        findings.add(finding);
        result.setFindings(findings);
        return result;
    }

    private static String extractJsonObject(String raw) {
        String text = raw.trim();
        text = removePrefix(text, "```json");
        text = removePrefix(text, "```JSON");
        text = removePrefix(text, "```");
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        text = text.trim();

        int start = text.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\' && inString) {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (inString) {
                // inside a string literal - braces do not count
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String removePrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }
}
