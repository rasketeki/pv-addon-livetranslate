package dev.livetranslate

import java.io.File
import io.github.givimad.whisperjni.WhisperJNI

/** Explicit developer check: downloads the actual weights and exercises the packaged JNI runtime. */
object TranslationSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = ModelStore(File(args.single()))
        val speech = store.ensure(checkNotNull(ModelStore.whisper("base")), ::println)
        WhisperJNI.loadLibrary()
        val whisper = WhisperJNI()
        val context = whisper.init(speech.toPath())
        whisper.free(context)
        println("Embedded Whisper model loaded and released.")
        MtClient(status = ::println, modelFile = { store.ensure(ModelStore.translation, ::println) }).use { client ->
            client.warmUp()
            val start = System.nanoTime()
            val result = client.translate("Привет! Как дела?", "ru", "pt")
            check(!result.isNullOrBlank()) { "Embedded translation did not produce a result." }
            check(result.any { it in 'a'..'z' } && result.none { it in '\u0400'..'\u04ff' }) {
                "Expected Portuguese text, got: $result"
            }
            println("Russian -> Portuguese: $result (${(System.nanoTime() - start) / 1_000_000} ms)")
        }
    }
}
