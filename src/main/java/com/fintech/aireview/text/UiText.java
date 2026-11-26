package com.fintech.aireview.text;

import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All user-visible UI text, loaded from YAML.
 *
 * <p>Defaults come from the bundled {@code /ui/ui-text.yml}. If
 * {@code ~/.ai-code-reviewer/ui-text.yml} exists its keys are merged on top, so a user or a
 * team can reword (or translate) the UI without rebuilding the plugin. The override file is
 * re-read automatically when its modification time changes.
 *
 * <p>Nested YAML maps are flattened to dotted keys ({@code panel.toast.copied}).
 * Placeholders are written {@code {name}} and filled by {@link #get(String, Object...)}.
 */
public final class UiText {

    private static final Logger LOG = Logger.getInstance(UiText.class);
    private static final String BUNDLED = "/ui/ui-text.yml";
    private static final Path OVERRIDE = Paths.get(System.getProperty("user.home"), ".ai-code-reviewer", "ui-text.yml");
    private static final long CHECK_INTERVAL_MS = 2000;

    private static final Map<String, String> DEFAULTS = Collections.unmodifiableMap(loadBundled());

    private static volatile Map<String, String> merged = DEFAULTS;
    private static volatile long overrideStamp = Long.MIN_VALUE;
    private static volatile long lastCheck;
    private static volatile int version;

    private UiText() {
    }

    /** Text for {@code key}; the key itself if it is not defined anywhere (makes typos visible). */
    public static String get(String key) {
        String value = current().get(key);
        return value != null ? value : key;
    }

    /**
     * Text for {@code key} with placeholders replaced. {@code args} are name/value pairs:
     * {@code get("status.done", "seconds", 3.2, "risk", "LOW")}.
     */
    public static String get(String key, Object... args) {
        String text = get(key);
        for (int i = 0; i + 1 < args.length; i += 2) {
            text = text.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
        }
        return text;
    }

    /** Every key, flattened, as a JSON object - handed to the HTML panel. */
    public static String json() {
        return new Gson().toJson(current());
    }

    /** Bumped whenever the override file is (re)loaded, so views can tell the text changed. */
    public static int version() {
        current();
        return version;
    }

    private static Map<String, String> current() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_INTERVAL_MS) return merged;
        synchronized (UiText.class) {
            if (now - lastCheck < CHECK_INTERVAL_MS) return merged;
            lastCheck = now;
            long stamp = overrideStamp();
            if (stamp != overrideStamp) {
                overrideStamp = stamp;
                merged = stamp == Long.MIN_VALUE ? DEFAULTS : mergeOverride();
                version++;
            }
            return merged;
        }
    }

    private static long overrideStamp() {
        try {
            return Files.isRegularFile(OVERRIDE) ? Files.getLastModifiedTime(OVERRIDE).toMillis() : Long.MIN_VALUE;
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static Map<String, String> mergeOverride() {
        Map<String, String> result = new LinkedHashMap<>(DEFAULTS);
        try (Reader reader = Files.newBufferedReader(OVERRIDE, StandardCharsets.UTF_8)) {
            flatten("", parse(reader), result);
        } catch (Exception e) {
            LOG.warn("Ignoring unreadable UI text override " + OVERRIDE + ": " + e.getMessage());
            return DEFAULTS;
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> loadBundled() {
        Map<String, String> result = new LinkedHashMap<>();
        try (InputStream in = UiText.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                LOG.error("UI text resource " + BUNDLED + " is missing from the plugin");
                return result;
            }
            flatten("", parse(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)), result);
        } catch (Exception e) {
            LOG.error("Could not load " + BUNDLED, e);
        }
        return result;
    }

    private static Object parse(Reader reader) {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                flatten(key, e.getValue(), out);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) flatten(prefix + "." + i, list.get(i), out);
        } else if (node != null && !prefix.isEmpty()) {
            out.put(prefix, String.valueOf(node));
        }
    }
}
