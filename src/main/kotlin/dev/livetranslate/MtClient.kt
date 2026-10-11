package dev.livetranslate

import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.parameters.ModelParameters
import java.io.File
import java.util.concurrent.TimeUnit

/** Recognition text stays in the Minecraft process, including during translation. */
class MtClient internal constructor(
    private val status: (String) -> Unit = {},
    private val modelFile: () -> File = { ModelStore().ensure(ModelStore.translation, status) },
    private val engineLoader: (File) -> Engine = { NativeEngine(it) }
) : AutoCloseable {
    internal interface Engine : AutoCloseable { fun translate(prompt: String): String }

    private class NativeEngine(file: File) : Engine {
        private val model = LlamaModel(ModelParameters().setModel(file.absolutePath)
            .setCtxSize(2048).setBatchSize(256).setUbatchSize(128).setParallel(1)
            .setThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 6))
            // Completion uses our explicit Gemma prompt; avoid parsing the model's
            // specialized structured translation chat template during server init.
            .setChatTemplate("chatml").setGpuLayers(0).setOffline(true).disableLog())

        override fun translate(prompt: String): String {
            require(model.encode(prompt).size <= 1700) { "Frase grande demais para o contexto do tradutor." }
            val params = InferenceParameters(prompt).withTemperature(0f).withNPredict(256)
                .withStopStrings("<end_of_turn>", "<start_of_turn>").withCachePrompt(true)
            val output = StringBuilder()
            var tokens = 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            model.generate(params).use { generation ->
                for (piece in generation) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    check(System.nanoTime() < deadline) { "Tempo limite na tradução local." }
                    output.append(piece.text)
                    tokens++
                }
            }
            check(tokens < 256) { "Tradução incompleta: limite de tokens." }
            return output.toString()
        }
        override fun close() { model.close() }
    }

    private val cache = object : LinkedHashMap<Triple<String, String, String>, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, String>, String>): Boolean = size > 200
    }
    @Volatile private var closed = false
    @Volatile private var loadingThread: Thread? = null
    private var engine: Engine? = null
    private var loadAttempted = false
    private var lastNotice = 0L

    companion object {
        internal fun prompt(text: String, source: String, target: String): String {
            val from = when (source) {
                "ru" -> "Russian"
                Languages.AUTO -> "automatically detected language"
                else -> Languages.displayName(source)
            }
            val to = if (target == "pt") "Brazilian Portuguese" else Languages.displayName(target)
            val code = if (target == "pt") "pt-BR" else target
            val clean = text.replace(Regex("<(?:bos|eos|start_of_turn|end_of_turn)>"), "")
            val instruction = "You are a professional $from ($source) to $to ($code) translator. " +
                "Convey the meaning and nuances of the original text in natural $to. " +
                "Produce only the translation, without explanations or commentary. " +
                "Please translate the following $from text into $to:\n\n\n$clean"
            return "<start_of_turn>user\n$instruction<end_of_turn>\n<start_of_turn>model\n"
        }
    }

    /** Called on the MT worker; download and native model loading never run on the game thread. */
    @Synchronized
    fun warmUp() {
        if (closed || engine != null || loadAttempted) return
        loadAttempted = true
        loadingThread = Thread.currentThread()
        try {
            val file = modelFile()
            if (closed) return
            status("Carregando tradutor dentro do mod…")
            val loaded = engineLoader(file)
            if (closed) loaded.close() else {
                engine = loaded
                status("Tradutor do mod pronto: russo → português (local).")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            reportFailure(e.message ?: e.javaClass.simpleName)
        } catch (e: LinkageError) {
            reportFailure("Biblioteca nativa incompatível: ${e.message}")
        } finally { loadingThread = null }
    }

    @Synchronized
    fun translate(text: String, source: String, targetLang: String): String? {
        val clean = text.trim()
        if (closed || clean.isEmpty()) return null
        if (source != Languages.AUTO && source == targetLang) return clean
        val key = Triple(clean, source, targetLang)
        cache[key]?.let { return it }
        val local = engine ?: return null
        return try {
            val result = local.translate(prompt(clean, source, targetLang)).trim()
            check(result.isNotBlank() && result.length <= clean.length * 4 + 100 && !result.contains("<start_of_turn>")) {
                "Resposta local inválida."
            }
            cache[key] = result
            result
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            reportFailure(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    private fun reportFailure(reason: String) {
        val now = System.nanoTime()
        if (lastNotice == 0L || now - lastNotice >= TimeUnit.SECONDS.toNanos(30)) {
            lastNotice = now
            status("Tradutor indisponível; mostrando [original]. Reinicie para tentar novamente. Veja o log.")
            System.err.println("[LiveTranslate] embedded translator: $reason")
        }
    }

    override fun close() {
        closed = true
        loadingThread?.interrupt()
        // Native inference must release its handle before close; never wait on the render thread.
        Thread({ synchronized(this) {
            engine?.close()
            engine = null
            cache.clear()
        } }, "livetranslate-mt-close").apply { isDaemon = true }.start()
    }
}
