package dev.livetranslate

/**
 * Languages offered in the config dropdowns.
 * `code` is what whisper.cpp / LibreTranslate expect; `label` is what the menu shows.
 */
object Languages {

    const val AUTO = "auto"

    // Order matters: it's the dropdown order and the stored index.
    val LIST: List<Pair<String, String>> = listOf(
        "en" to "English",
        "pt" to "Português",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "ru" to "Русский",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "uk" to "Українська",
        "ja" to "日本語",
        "zh" to "中文",
        "ko" to "한국어",
        "ar" to "العربية"
    )

    /** Input dropdown labels: "Auto-detect" first, then every language. */
    fun inputLabels(): List<String> = listOf("Auto-detect") + LIST.map { it.second }

    fun inputIndex(code: String): Int = LIST.indexOfFirst { it.first == code } + 1

    /** Output dropdown labels: just the languages. */
    fun outputLabels(): List<String> = LIST.map { it.second }

    /** Input index -> code. Index 0 is auto. */
    fun inputCode(index: Int): String =
        if (index <= 0) AUTO else LIST.getOrNull(index - 1)?.first ?: AUTO

    /** Output index -> code. */
    fun outputCode(index: Int): String =
        LIST.getOrNull(index)?.first ?: "en"

    /** Default output index from a locale code like "pt" (falls back to English). */
    fun defaultOutputIndex(localeShort: String): Int {
        val i = LIST.indexOfFirst { it.first == localeShort }
        return if (i >= 0) i else LIST.indexOfFirst { it.first == "en" }.coerceAtLeast(0)
    }

    /** Human-readable language name from a code (e.g. "pt" → "Português"). */
    fun displayName(code: String): String =
        LIST.firstOrNull { it.first == code }?.second ?: code

    /** Returns the flag emoji corresponding to a language code. */
    fun getFlagEmoji(code: String): String = when (code.lowercase()) {
        "en" -> "🇺🇸"
        "pt" -> "🇧🇷"
        "es" -> "🇪🇸"
        "fr" -> "🇫🇷"
        "de" -> "🇩🇪"
        "it" -> "🇮🇹"
        "ru" -> "🇷🇺"
        "nl" -> "🇳🇱"
        "pl" -> "🇵🇱"
        "tr" -> "🇹🇷"
        "uk" -> "🇺🇦"
        "ja" -> "🇯🇵"
        "zh" -> "🇨🇳"
        "ko" -> "🇰🇷"
        "ar" -> "🇸🇦"
        else -> ""
    }

    /** Returns a localized initial prompt of Minecraft terms for Whisper STT. */
    fun getMinecraftPrompt(code: String): String = when (code.lowercase()) {
        "en" -> "Minecraft game play chat, creeper, zombie, enderman, netherite, diamond, redstone."
        "pt" -> "Conversa de jogo Minecraft, creeper, zumbi, enderman, netherita, diamante, redstone."
        "es" -> "Chat de juego Minecraft, creeper, zombie, enderman, netherita, diamante, redstone."
        "fr" -> "Chat de jeu Minecraft, creeper, zombie, enderman, netherite, diamant, redstone."
        "de" -> "Minecraft-Spielchat, Creeper, Zombie, Enderman, Netherit, Diamant, Redstone."
        "it" -> "Chat di gioco Minecraft, creeper, zombie, enderman, netherite, diamante, redstone."
        "ru" -> "Чат игры Майнкрафт, крипер, зомби, эндермен, незерит, алмаз, редстоун."
        "nl" -> "Minecraft spelchat, creeper, zombie, enderman, netherite, diamant, redstone."
        "pl" -> "Czat gry Minecraft, creeper, zombie, enderman, netheryt, diament, redstone."
        "tr" -> "Minecraft oyun sohbeti, creeper, zombie, enderman, netherite, elmas, redstone."
        "uk" -> "Чат гри Майнкрафт, кріпер, зомбі, ендермен, незерит, алмаз, редстоун."
        "ja" -> "マインクラフトのゲームチャット、クリーパー、ゾンビ、エンダーマン、ネザライト、ダイヤモンド、レッドストーン。"
        "zh" -> "麦块游戏聊天，苦力怕，僵尸，末影人，下界合金，钻石，红石。"
        "ko" -> "마인크래프트 게임 채팅, 크리퍼, 좀비, 엔더맨, 네더라이트, 다이아몬드, 레드스톤."
        "ar" -> "محادثة لعبة ماين كرافت، كريبر، زومبي، أندرمان، نيثريت، ألماس، ريدستون."
        else -> "Minecraft game play chat, creeper, zombie, enderman, netherite, diamond, redstone."
    }
}
