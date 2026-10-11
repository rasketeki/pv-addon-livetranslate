package dev.livetranslate

import io.github.givimad.whisperjni.WhisperContext
import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Offline in-process speech recognition with whisper.cpp. Loads models from
 * ~/.livetranslate, the legacy game directory, or bundled /models resources.
 * Missing standard models are downloaded automatically by ModelStore, with
 * pinned size/checksum validation. No audio or recognition text leaves the PC.
 *
 * Overrides: livetranslate.whisper.model, livetranslate.whisper.dir,
 * livetranslate.whisper.model.path. Model loading runs in the background.
 */
class SttClient {


    /** Sink for human-facing status lines (shown in chat by the addon). */
    fun interface StatusSink {
        fun status(message: String)
    }

    private enum class State { LOADING, READY, DISABLED }

    private companion object {
        // whisper misbehaves on very short input (and language auto-detect gets
        // unreliable): pad chunks with trailing silence up to 1.3 s @ 16 kHz.
        const val MIN_WHISPER_SAMPLES = 16_000 * 13 / 10

    }

    @Volatile private var whisper: WhisperJNI? = null
    @Volatile private var ctx: WhisperContext? = null
    @Volatile private var state = State.LOADING
    @Volatile private var configuredModel = "base"
    @Volatile private var loadedModel = "base"
    @Volatile private var status: StatusSink? = null
    // False until the first warmUp actually kicks off a load — distinguishes the
    // initial LOADING placeholder from a load that is genuinely in progress.
    @Volatile private var loadStarted = false
    @Volatile private var loadGeneration = 0
    @Volatile private var loaderThread: Thread? = null
    @Volatile private var closed = false

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    /**
     * Start native + model loading on a background thread (call at startup).
     * Calling again with a different model hot-swaps it live — no restart. The
     * same model already loaded/loading is a no-op so menu re-reads are cheap.
     */
    @Synchronized
    fun warmUp(modelName: String = "base", statusSink: StatusSink? = null) {
        if (closed) return
        val normalized = modelName.lowercase()
        statusSink?.let { status = it }
        // Already loaded/loading this exact model (and not disabled): nothing to do.
        if (loadStarted && normalized == configuredModel && state != State.DISABLED) return
        configuredModel = normalized
        loadStarted = true
        loaderThread?.interrupt()
        val generation = ++loadGeneration
        state = State.LOADING
        loaderThread = Thread({ load(normalized, generation) }, "livetranslate-whisper-init").apply {
            isDaemon = true
            start()
        }
    }

    private fun load(modelName: String, generation: Int) {
        try {
            val model = resolveModel(modelName)
            if (model == null) {
                synchronized(this) {
                    if (closed || generation != loadGeneration) return
                    System.err.println("[LiveTranslate] whisper model unavailable — STT disabled")
                    status?.status("Modelo de voz indisponível. Verifique a conexão e reinicie para tentar novamente.")
                    state = State.DISABLED
                }
                return
            }
            if (closed || generation != loadGeneration) return
            preloadCustomNative()
            WhisperJNI.loadLibrary()
            val w = WhisperJNI()
            val c = w.init(model.toPath())
            // Swap the live context under the transcribe lock so an in-flight
            // recognition never sees a half-freed native handle.
            synchronized(this) {
                if (closed || generation != loadGeneration) {
                    w.free(c)
                    return
                }
                val old = ctx
                val oldW = whisper
                whisper = w
                ctx = c
                loadedModel = (System.getProperty("livetranslate.whisper.model") ?: modelName).lowercase()
                state = State.READY
                if (old != null) try { oldW?.free(old) } catch (_: Throwable) {}
                status?.status("Modelo de voz '${loadedModel}' pronto (local).")
            }
            System.err.println("[LiveTranslate] whisper ready (in-process, model=${model.name})")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            synchronized(this) {
                if (closed || generation != loadGeneration) return
                System.err.println("[LiveTranslate] whisper init failed: ${e.message} — STT disabled")
                status?.status("speech-to-text off — ${e.message ?: "init failed"} (CPU may lack AVX2).")
                state = State.DISABLED
            }
        }
    }

    /**
     * Transcribe 16 kHz mono PCM. `language` is a whisper code ("en", "pt", …)
     * or [Languages.AUTO] for auto-detect. Returns null if STT isn't ready yet
     * (still loading / disabled) or nothing was recognized.
     *
     * With [translateToEnglish] whisper runs its built-in translation task and
     * outputs English directly (any source language) in the same single pass —
     * no online MT round-trip. Whisper can only translate INTO English, so the
     * caller uses this only when the output language is "en".
     */
    @Synchronized
    fun transcribe(
        pcm16k: ShortArray,
        language: String,
        translateToEnglish: Boolean = false,
        threadsOverride: Int? = null,
        greedyOverride: Boolean? = null
    ): String? {
        if (closed || state != State.READY) return null
        val w = whisper ?: return null
        val c = ctx ?: return null
        if (pcm16k.isEmpty()) return null

        val raw = AudioUtil.pcm16ToFloat(pcm16k)
        val samples = if (raw.size < MIN_WHISPER_SAMPLES) raw.copyOf(MIN_WHISPER_SAMPLES) else raw

        // Greedy is plenty for the tiny model and noticeably faster; beam search
        // wins on the larger models where accents/unclear speech matter more.
        // Note: BEAN_SEARCH is whisper-jni's (misspelled) name for beam search.
        val greedy = greedyOverride ?: loadedModel.startsWith("tiny")
        val strategy = if (greedy) WhisperSamplingStrategy.GREEDY else WhisperSamplingStrategy.BEAN_SEARCH
        val params = WhisperFullParams(strategy).apply {
            this.language = if (language.isBlank() || language == Languages.AUTO) "auto" else language
            translate = translateToEnglish   // whisper's own X->en translation; other targets use MtClient
            printProgress = false
            printRealtime = false
            printTimestamps = false
            printSpecial = false
            suppressNonSpeechTokens = true   // no "(music)" style artifacts in chat
            // bias whisper on minecraft terminology
            initialPrompt = Languages.getMinecraftPrompt(language)
            // Narrower beam on the slower models to stay realtime.
            if (!greedy) {
                beamSearchBeamSize = if (loadedModel.startsWith("small") || loadedModel.startsWith("medium")) 3 else 5
            }
            nThreads = (threadsOverride ?: threads).coerceIn(1, 8)
        }

        if (w.full(c, params, samples, samples.size) != 0) return null

        val n = w.fullNSegments(c)
        if (n <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until n) sb.append(w.fullGetSegmentText(c, i))
        val text = sb.toString().trim().ifBlank { return null }

        return text
    }

    @Synchronized
    fun close() {
        closed = true
        loadGeneration++
        loaderThread?.interrupt()
        try {
            ctx?.let { whisper?.free(it) }
        } catch (_: Throwable) {
        }
        ctx = null
        whisper = null
        state = State.DISABLED
    }

    /**
     * Optional GPU acceleration: whisper-jni bundles a CPU-only native, but if
     * the user drops a self-built whisper library (e.g. whisper.cpp compiled
     * with Vulkan or CUDA, 5-15x faster) into the model dir, load it first —
     * the JNI wrapper then links against the already-loaded module instead of
     * extracting the bundled CPU one. Must match whisper.cpp 1.7.x ABI.
     * Any failure just logs and falls through to the bundled CPU library.
     */
    private fun preloadCustomNative() {
        val os = System.getProperty("os.name").lowercase()
        val libName = when {
            os.contains("win") -> "whisper.dll"
            os.contains("mac") -> "libwhisper.dylib"
            else -> "libwhisper.so"
        }
        val custom = File(modelDir(), libName)
        if (!custom.isFile) return
        try {
            @Suppress("UnsafeDynamicallyLoadedCode")
            System.load(custom.absolutePath)
            System.err.println("[LiveTranslate] using custom whisper native: $custom (GPU build?)")
        } catch (t: Throwable) {
            System.err.println("[LiveTranslate] failed to load $custom (${t.message}) — using bundled CPU library")
        }
    }

    // -- offline model resolution ---------------------------------

    private fun resolveModel(modelName: String): File? {
        System.getProperty("livetranslate.whisper.model.path")?.let {
            val f = File(it)
            if (f.isFile) return f
            System.err.println("[LiveTranslate] model.path '$it' not found — falling back")
        }
        val name = (System.getProperty("livetranslate.whisper.model") ?: modelName).lowercase()

        // Supported quantized ggml files (q5) are: ~2.5x smaller and a
        // bit faster on CPU for a negligible accuracy cost. A full-precision
        // model already on disk (older builds downloaded those) keeps being
        // used so nothing re-downloads. Explicit names like "base-q5_1" or
        // "small.en" via -Dlivetranslate.whisper.model are used verbatim.
        val candidates = if (name.contains('-') || name.contains('.')) {
            listOf("ggml-$name.bin")
        } else {
            listOf("ggml-$name.bin", "ggml-$name-${quantSuffix(name)}.bin")
        }

        // The model lives in a per-user dir shared by every launcher/client
        // (vanilla, Lunar, …) so it is downloaded once per machine, not once
        // per game directory. Older builds stored it under <gameDir>/livetranslate;
        // keep reading from there so nothing re-downloads.
        for (fileName in candidates) {
            val shared = File(modelDir(), fileName)
            if (shared.isFile && shared.length() > 1_000_000) {
                val asset = ModelStore.whisper(name)
                if (asset != null && fileName == asset.name) {
                    return ModelStore(modelDir()).ensure(asset) { status?.status(it) }
                }
                return shared
            }
            legacyModelDir()?.let { legacy ->
                val f = File(legacy, fileName)
                if (f.isFile && f.length() > 1_000_000) return f
            }
        }

        // Offline path: a model bundled inside the mod jar under /models/.
        // Extract it once into the shared dir so no download is needed at all.
        for (fileName in candidates) {
            extractBundledModel(fileName)?.let { return it }
        }

        val asset = ModelStore.whisper(name) ?: return null
        return ModelStore(modelDir()).ensure(asset) { status?.status(it) }
    }

    /** Copy a model shipped inside the jar (`/models/<fileName>`) to the model dir, if present. */
    private fun extractBundledModel(fileName: String): File? {
        val res = javaClass.getResourceAsStream("/models/$fileName") ?: return null
        val dest = File(modelDir(), fileName)
        return try {
            dest.parentFile?.mkdirs()
            res.use { input -> Files.copy(input, dest.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            System.err.println("[LiveTranslate] extracted bundled model to $dest")
            if (dest.isFile && dest.length() > 1_000_000) dest else null
        } catch (e: Exception) {
            System.err.println("[LiveTranslate] failed to extract bundled model: ${e.message}")
            null
        }
    }

    /** Quantization variant published on HF for each base model name. */
    private fun quantSuffix(name: String) = if (name.startsWith("medium") || name.startsWith("large")) "q5_0" else "q5_1"

    private fun modelDir(): File {
        val override = System.getProperty("livetranslate.whisper.dir")
            ?: System.getenv("LIVETRANSLATE_WHISPER_DIR")
        if (!override.isNullOrBlank()) return File(override)
        return ModelStore.defaultDirectory()
    }

    private fun legacyModelDir(): File? = try {
        FabricLoader.getInstance().gameDir.resolve("livetranslate").toFile()
    } catch (_: Throwable) {
        null
    }
}
