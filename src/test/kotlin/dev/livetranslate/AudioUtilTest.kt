package dev.livetranslate

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioUtilTest {
    @Test
    fun `pause starts inside a packet containing speech`() {
        assertEquals(3, AudioUtil.trailingSilenceSamples(shortArrayOf(1200, -900, 0, 30, -20), 600))
    }

    @Test
    fun `entirely silent packet contributes its full length`() {
        assertEquals(960, AudioUtil.trailingSilenceSamples(ShortArray(960), 600))
    }

    @Test
    fun `speech at end resets silence including negative PCM limit`() {
        assertEquals(0, AudioUtil.trailingSilenceSamples(shortArrayOf(0, 0, Short.MIN_VALUE), 600))
        assertEquals(0, AudioUtil.trailingSilenceSamples(shortArrayOf(0, -600), 600))
    }

    @Test
    fun `empty packet adds no silence`() {
        assertEquals(0, AudioUtil.trailingSilenceSamples(shortArrayOf(), 600))
    }
}
