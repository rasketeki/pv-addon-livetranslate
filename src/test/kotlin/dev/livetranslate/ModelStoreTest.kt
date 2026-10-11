package dev.livetranslate

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ModelStoreTest {
    private val bytes = "fake-model-weights".toByteArray()
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun `validated model is reused without network`() {
        val dir = Files.createTempDirectory("model-store-test").toFile()
        try {
            dir.resolve("model.gguf").writeBytes(bytes)
            val asset = ModelStore.Asset("model.gguf", "https://example.invalid", bytes.size.toLong(), hash(bytes))
            assertContentEquals(bytes, ModelStore(dir).ensure(asset).readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `automatic download is validated and partial never becomes final`() {
        val dir = Files.createTempDirectory("model-store-test").toFile()
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/model") { exchange ->
                requests.incrementAndGet()
                assertEquals("GET", exchange.requestMethod)
                assertEquals(0, exchange.requestBody.readAllBytes().size)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val good = ModelStore.Asset("model.gguf", "http://127.0.0.1:${server.address.port}/model", bytes.size.toLong(), hash(bytes))
            val store = ModelStore(dir)
            assertContentEquals(bytes, store.ensure(good).readBytes())
            store.ensure(good)
            assertEquals(1, requests.get())
            assertFalse(dir.resolve("model.gguf.part").exists())
            val bad = good.copy(name = "bad.gguf", sha256 = "0".repeat(64))
            assertFailsWith<IllegalStateException> { store.ensure(bad) }
            assertFalse(dir.resolve("bad.gguf").exists())
            assertFalse(dir.resolve("bad.gguf.part").exists())
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    @Test
    fun `asset name cannot escape model directory`() {
        val dir = Files.createTempDirectory("model-store-test").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                ModelStore(dir).ensure(ModelStore.Asset("../escape", "https://example.invalid", 1, ""))
            }
        } finally { dir.deleteRecursively() }
    }
}
