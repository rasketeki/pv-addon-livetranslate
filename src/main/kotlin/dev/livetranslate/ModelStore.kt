package dev.livetranslate

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Public model weights only: no player audio, text, keys or translation requests. */
internal class ModelStore(private val directory: File = defaultDirectory()) {
    internal data class Asset(val name: String, val url: String, val size: Long, val sha256: String)

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private const val WHISPER_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
        val translation = Asset(
            "translategemma-4b-it.Q4_K_M.gguf",
            "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/d37868f759d2f817a43d28dd80cf9f982ffa62fd/translategemma-4b-it.Q4_K_M.gguf",
            2489909760L, "81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330"
        )
        fun whisper(name: String): Asset? {
            val (size, hash) = when (name.removeSuffix("-q5_1")) {
                "tiny" -> 32152673L to "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
                "base" -> 59707625L to "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
                "small" -> 190085487L to "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
                else -> return null
            }
            val fileName = "ggml-${name.removeSuffix("-q5_1")}-q5_1.bin"
            return Asset(fileName, "https://huggingface.co/ggerganov/whisper.cpp/resolve/$WHISPER_REVISION/$fileName", size, hash)
        }
        fun defaultDirectory(): File = File(
            System.getProperty("livetranslate.models.dir") ?: System.getenv("LIVETRANSLATE_MODELS_DIR")
                ?: File(System.getProperty("user.home"), ".livetranslate").path
        )
    }

    fun ensure(asset: Asset, status: (String) -> Unit = {}): File {
        require(asset.name == File(asset.name).name && !asset.name.contains('/') && !asset.name.contains('\\'))
        val dest = File(directory, asset.name)
        synchronized(locks.computeIfAbsent(dest.canonicalPath) { Any() }) {
            directory.mkdirs()
            // Prevent two Minecraft instances from publishing the same partial file.
            FileChannel.open(File(directory, "${asset.name}.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    if (valid(dest, asset)) return dest
                    val part = File(directory, "${asset.name}.part")
                    var failure: Exception? = null
                    for (attempt in 1..3) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        try {
                            Files.deleteIfExists(part.toPath())
                            check(directory.usableSpace >= asset.size) { "Espaço insuficiente para ${asset.name}." }
                            status("Baixando modelo ${asset.name} (uma vez; tentativa $attempt/3)…")
                            download(asset, part, status)
                            check(valid(part, asset)) { "Modelo incompleto ou checksum inválido: ${asset.name}" }
                            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            return dest
                        } catch (e: InterruptedException) {
                            Files.deleteIfExists(part.toPath())
                            Thread.currentThread().interrupt()
                            throw e
                        } catch (e: Exception) {
                            failure = e
                            Files.deleteIfExists(part.toPath())
                            if (attempt < 3) Thread.sleep(attempt * 1000L)
                        }
                    }
                    throw IllegalStateException("Não foi possível baixar ${asset.name}: ${failure?.message}", failure)
                }
            }
        }
    }

    private fun valid(file: File, asset: Asset): Boolean {
        if (!file.isFile || file.length() != asset.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256
    }

    private fun download(asset: Asset, part: File, status: (String) -> Unit) {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build().use { http ->
                val req = HttpRequest.newBuilder(URI.create(asset.url)).GET()
                    .timeout(Duration.ofMinutes(30)).header("User-Agent", "LiveTranslate/1.0 model-download").build()
                val future = http.sendAsync(req, HttpResponse.BodyHandlers.ofFile(part.toPath()))
                val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30)
                var lastStep = -1L
                try {
                    while (true) {
                        try {
                            val response = future.get(1, TimeUnit.SECONDS)
                            check(response.statusCode() == 200) { "HTTP ${response.statusCode()} ao baixar o modelo." }
                            status("Modelo ${asset.name} baixado; verificando integridade…")
                            return
                        } catch (_: TimeoutException) {
                            check(System.nanoTime() < deadline) { "Tempo limite no download do modelo." }
                            val percent = (part.length() * 100 / asset.size).coerceAtMost(100)
                            if (percent / 10 > lastStep) {
                                lastStep = percent / 10
                                status("Baixando modelo ${asset.name}: $percent%…")
                            }
                        }
                    }
                } finally {
                    future.cancel(true)
                    http.shutdownNow()
                }
            }
    }
}
