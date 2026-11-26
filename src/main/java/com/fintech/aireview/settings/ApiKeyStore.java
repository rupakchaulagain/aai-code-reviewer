package com.fintech.aireview.settings;

import com.fintech.aireview.llm.LlmProvider;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;

/**
 * Stores LLM API tokens in the IDE credential store (OS keychain / KeePass),
 * never in plain-text plugin settings. One slot per provider.
 */
public final class ApiKeyStore {

    private static final String SUBSYSTEM = "FintechAiCodeReviewer";

    private ApiKeyStore() {
    }

    private static CredentialAttributes attributes(LlmProvider provider) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SUBSYSTEM, provider.name()));
    }

    public static String get(LlmProvider provider) {
        String password = PasswordSafe.getInstance().getPassword(attributes(provider));
        return password == null ? "" : password;
    }

    public static void set(LlmProvider provider, String token) {
        String clean = token == null ? "" : token.trim();
        PasswordSafe.getInstance().setPassword(attributes(provider), clean.isEmpty() ? null : clean);
    }

    public static boolean isConfigured(LlmProvider provider) {
        return !get(provider).isBlank();
    }
}
