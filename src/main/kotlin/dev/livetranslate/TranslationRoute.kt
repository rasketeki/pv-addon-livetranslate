package dev.livetranslate

/** Tracks the language actually produced by recognition before choosing the MT step. */
internal class TranslationRoute(source: String, target: String) {
    // Whisper can translate to English only. For ru -> pt, preserve Russian
    // and translate directly with the local MT model, without an English pivot.
    val translateLocally = target == "en" && source != "en"
    val textLanguage = if (translateLocally) "en" else source
    val needsTranslation = textLanguage == Languages.AUTO || textLanguage != target
}
