package ai.ciris.mobile.shared.models

import kotlinx.serialization.Serializable

/**
 * Setup mode determines how LLM access is configured.
 *
 * Source: android/app/src/main/java/ai/ciris/mobile/setup/SetupViewModel.kt:35-38
 *
 * - CIRIS_PROXY: Free AI via CIRIS hosted proxy (requires Google OAuth)
 *   Uses Google ID token with llm01.ciris-services-* proxy
 *
 * - BYOK: Bring Your Own Key
 *   User provides their own API key from OpenAI/Anthropic/etc/on-device
 *
 * On-device Gemma 4 inference lives under BYOK as the "mobile_local"
 * provider so it can coexist with other providers: the Python
 * LLMBus picks between installed providers by priority, which means a
 * capable phone can run local-first with cloud fallback (or vice versa)
 * simply by having both adapters loaded.
 */
@Serializable
enum class SetupMode {
    /**
     * Free AI via CIRIS proxy - requires Google OAuth.
     * Google ID token is used to access llm01.ciris-services-* proxy.
     */
    CIRIS_PROXY,

    /**
     * Bring Your Own Key - user provides their own LLM API key.
     * Supports OpenAI, Anthropic, Azure OpenAI, LocalAI, etc.
     */
    BYOK,

    /**
     * Local on-device inference using Gemma 4 via the mobile_local_llm adapter.
     * No API key required - runs entirely on-device using LiteRT-LM.
     * Available on capable Android devices (6GB+ RAM, arm64) and desktops (6GB+ RAM).
     */
    LOCAL_ON_DEVICE
}

/**
 * WHICH LLM MODE AN OAUTH SIGN-IN LANDS IN.
 *
 * Signing in with Google or Apple entitles the person to CIRIS's hosted models
 * at no cost, and the OAuth ID TOKEN IS THE CREDENTIAL — there is no key to
 * paste. So an eligible sign-in defaults to [SetupMode.CIRIS_PROXY] and the AI
 * screen offers the free service, with one tap to [SetupMode.BYOK] for anyone
 * who would rather bring their own provider, and one tap back.
 *
 * WHY THE FIRST BRANCH IS NOT `!= null`. "Unchosen" has TWO representations
 * here — `null`, and the non-null [SetupMode.LOCAL_ON_DEVICE] default — so a
 * bare null-guard reads the second as "already chosen" and an eligible OAuth
 * NEVER reaches the proxy. That was the "forced BYOK despite a Google login"
 * bug. Only BYOK and CIRIS_PROXY count as a choice the person actually made,
 * and such a choice is preserved.
 *
 * Extracted from the assignment it used to be inline in, so the rule can be
 * RUN rather than only read: a mode that silently falls to BYOK looks identical
 * on screen to one that was chosen, and the difference is a bill.
 */
fun initialSetupMode(current: SetupMode?, isCirisEligible: Boolean): SetupMode = when {
    current == SetupMode.BYOK || current == SetupMode.CIRIS_PROXY -> current
    isCirisEligible -> SetupMode.CIRIS_PROXY
    else -> SetupMode.BYOK
}
