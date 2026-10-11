package dev.livetranslate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationRouteTest {
    @Test
    fun `Russian to Portuguese keeps Russian for direct local translation`() {
        val route = TranslationRoute("ru", "pt")
        assertFalse(route.translateLocally)
        assertEquals("ru", route.textLanguage)
        assertTrue(route.needsTranslation)
    }

    @Test
    fun `English output uses Whisper translation without MT`() {
        for (source in listOf("ru", Languages.AUTO)) {
            val route = TranslationRoute(source, "en")
            assertTrue(route.translateLocally)
            assertEquals("en", route.textLanguage)
            assertFalse(route.needsTranslation)
        }
    }

    @Test
    fun `matching languages bypass translation`() {
        for (language in listOf("ru", "pt", "en")) {
            val route = TranslationRoute(language, language)
            assertFalse(route.translateLocally)
            assertFalse(route.needsTranslation)
        }
    }

    @Test
    fun `auto detection is preserved for Portuguese`() {
        val route = TranslationRoute(Languages.AUTO, "pt")
        assertEquals(Languages.AUTO, route.textLanguage)
        assertTrue(route.needsTranslation)
    }

    @Test
    fun `local profile selects Russian and Portuguese`() {
        assertEquals("ru", Languages.inputCode(Languages.inputIndex("ru")))
        assertEquals("pt", Languages.outputCode(Languages.defaultOutputIndex("pt")))
    }
}