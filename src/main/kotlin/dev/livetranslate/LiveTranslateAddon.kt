package dev.livetranslate

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import su.plo.config.entry.BooleanConfigEntry
import su.plo.config.entry.IntConfigEntry
import su.plo.slib.api.chat.component.McTextComponent
import su.plo.voice.api.addon.AddonInitializer
import su.plo.voice.api.addon.InjectPlasmoVoice
import su.plo.voice.api.addon.annotation.Addon
import su.plo.voice.api.client.PlasmoVoiceClient
import su.plo.voice.api.client.audio.source.ClientAudioSource
import su.plo.voice.api.client.event.audio.source.AudioSourceClosedEvent
import su.plo.voice.api.client.event.audio.source.AudioSourceWriteEvent
import su.plo.voice.api.event.EventSubscribe
import su.plo.voice.proto.data.audio.source.PlayerSourceInfo
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Live translation add-on.
 *
 * Per speaking player:
 *   AudioSourceWriteEvent  -> append decoded PCM
 *   AudioSourceClosedEvent -> flush: 48k->16k -> whisper.cpp in-process (STT)
 *                             -> local translation (MT) -> print the line to chat
 *
 * Input/output languages are chosen from two dropdowns added to the Plasmo Voice
 * menu (Addons tab). The local profile defaults to Russian → Portuguese.
 */
@Addon(
    id = "pv-addon-livetranslate",
    name = "Live Translate",
    version = "1.0.0",
    authors = ["rasketeki"]
)
class LiveTranslateAddon : AddonInitializer {

    private companion object {
        const val SOURCE_RATE = 48_000                         // Plasmo Voice PCM rate; downsampled to 16 kHz for whisper
        const val MIN_SAMPLES = SOURCE_RATE * 400 / 1000       // drop < 0.4 s fragments on close
        const val MIN_CHUNK_SAMPLES = SOURCE_RATE * 900 / 1000  // wait >=0.9 s before a pause-flush
        const val SILENCE_SAMPLES = SOURCE_RATE * 350 / 1000    // 0.35 s of quiet = sentence boundary
        const val MAX_CHUNK_SAMPLES = SOURCE_RATE * 6           // hard cap: flush every 6 s of speech
    }

    @InjectPlasmoVoice
    private lateinit var voiceClient: PlasmoVoiceClient

    private val stt = SttClient()
    private val mt = MtClient(status = { showStatus(it) })

    // Config entries backing the dropdowns (store the selected index).
    private var inputLangEntry: IntConfigEntry? = null
    private var outputLangEntry: IntConfigEntry? = null
    private var modelEntry: IntConfigEntry? = null
    private var showBalloonsEntry: BooleanConfigEntry? = null
    private var printChatEntry: BooleanConfigEntry? = null
    private var balloonDurationEntry: IntConfigEntry? = null
    private var balloonColorEntry: IntConfigEntry? = null
    private var sensitivityEntry: IntConfigEntry? = null

    // New configuration entries
    private var whisperThreadsEntry: IntConfigEntry? = null
    private var whisperSearchEntry: IntConfigEntry? = null

    private fun getSpeechPeak(): Int {
        val sens = (sensitivityEntry?.value() ?: 5).coerceIn(1, 10)
        // Sensitivity 1 -> 1500 (ignores quiet sound)
        // Sensitivity 5 -> 900 (default)
        // Sensitivity 10 -> 150 (very sensitive)
        return 1500 - (sens - 1) * 150
    }

    private fun getSilencePeak(): Int {
        return getSpeechPeak() * 2 / 3
    }

    // Whisper model per dropdown index; order matches the labels below.
    private val modelNames = listOf("tiny", "base", "small")
    // Model currently applied; when the dropdown changes we hot-swap (no restart).
    @Volatile private var appliedModelIndex = -1

    private val buffers = ConcurrentHashMap<ClientAudioSource<*>, Utterance>()
    private lateinit var worker: ThreadPoolExecutor       // STT (CPU-bound, local)
    private lateinit var mtWorker: ThreadPoolExecutor     // embedded translation — kept off the STT thread

    // Status lines from the STT loader, shown in gray so the player knows what's
    // happening (ready, missing model, disabled) instead of only seeing the log.
    private val statusSink = SttClient.StatusSink { msg -> showStatus(msg) }

    override fun onAddonInitialize() {
        worker = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(8),
            { r -> Thread(r, "livetranslate-worker").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy()   // under overload drop stale audio, not latency
        )
        // Local translation runs serially here to limit memory usage and preserve order; it cannot
        // stall speech recognition of the next chunks (they are independent).
        mtWorker = ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            LinkedBlockingQueue(4),
            { r -> Thread(r, "livetranslate-mt").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardOldestPolicy()
        )
        registerConfig()   // first: warmUp needs the saved model selection

        appliedModelIndex = modelEntry?.value() ?: 1
        val model = modelNames.getOrNull(appliedModelIndex) ?: "base"
        stt.warmUp(model, statusSink)  // download/load without blocking the game
        mtWorker.execute { mt.warmUp() }
    }

    /** Adds the two language dropdowns under our block in the Plasmo Voice menu. */
    private fun registerConfig() {
        val cfg = voiceClient.getAddonConfig(this)

        inputLangEntry = cfg.addDropDown(
            "input-language-local",
            McTextComponent.literal("Input language"),
            McTextComponent.literal("Language spoken by others. 'Auto-detect' lets Whisper decide."),
            Languages.inputLabels(),
            false,
            Languages.inputIndex("ru") // local profile: Russian
        )

        outputLangEntry = cfg.addDropDown(
            "output-language-local",
            McTextComponent.literal("Output language"),
            McTextComponent.literal("Language the messages are translated into."),
            Languages.outputLabels(),
            false,
            Languages.defaultOutputIndex("pt")
        )

        modelEntry = cfg.addDropDown(
            "whisper-model",
            McTextComponent.literal("Recognition model"),
            McTextComponent.literal(
                "Bigger = more accurate but slower (helps with accents). " +
                    "Downloads automatically once; loads in the background."
            ),
            listOf(
                "Tiny — fastest, least accurate (~32 MB)",
                "Base — balanced (~60 MB)",
                "Small — best accuracy (~190 MB)"
            ),
            false,
            1 // Base
        )

        showBalloonsEntry = cfg.addToggle(
            "show-balloons",
            McTextComponent.literal("Show speech balloons"),
            McTextComponent.literal("Display translated speech bubbles above players' heads."),
            true
        )

        printChatEntry = cfg.addToggle(
            "print-chat",
            McTextComponent.literal("Print in chat"),
            McTextComponent.literal("Print translated speech in the chat window."),
            true
        )

        balloonDurationEntry = cfg.addIntSlider(
            "balloon-duration",
            McTextComponent.literal("Balloon duration"),
            McTextComponent.literal("How long speech bubbles remain visible above players."),
            "s",
            8,
            2,
            30
        )

        balloonColorEntry = cfg.addDropDown(
            "balloon-color",
            McTextComponent.literal("Balloon color"),
            McTextComponent.literal("Color of the speech bubbles text."),
            listOf("Light Blue", "Yellow", "Green", "Red", "Gold", "White"),
            false,
            0 // Light Blue
        )

        sensitivityEntry = cfg.addIntSlider(
            "speech-sensitivity",
            McTextComponent.literal("Speech sensitivity"),
            McTextComponent.literal("Higher = hears quieter voices (may trigger on noise). Lower = filters background noise."),
            "",
            5,
            1,
            10
        )

        whisperThreadsEntry = cfg.addIntSlider(
            "whisper-threads",
            McTextComponent.literal("Whisper CPU threads"),
            McTextComponent.literal("Number of CPU threads to use for local Whisper transcription."),
            "",
            Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
            1,
            8
        )

        whisperSearchEntry = cfg.addDropDown(
            "whisper-search-local",
            McTextComponent.literal("Whisper search strategy"),
            McTextComponent.literal("Greedy is faster; Beam Search is more accurate."),
            listOf("Greedy (Fast)", "Beam Search (Accurate)"),
            false,
            1 // Beam Search for Russian recognition
        )

    }

    override fun onAddonShutdown() {
        if (::worker.isInitialized) worker.shutdownNow()
        if (::mtWorker.isInitialized) mtWorker.shutdownNow()
        buffers.clear()
        stt.close()
        mt.close()
        BalloonManager.clearAll()
    }

    /** Re-read the model dropdown; if it changed, hot-swap whisper in the background. */
    private fun applyModelIfChanged() {
        val idx = modelEntry?.value() ?: return
        if (idx == appliedModelIndex) return
        appliedModelIndex = idx
        stt.warmUp(modelNames.getOrElse(idx) { "base" }, statusSink)
    }

    @EventSubscribe
    fun onWrite(event: AudioSourceWriteEvent) {
        val source = event.source
        val info = source.sourceInfo as? PlayerSourceInfo ?: return
        val samples = event.samples
        if (samples.isEmpty()) return

        applyModelIfChanged()

        val nick = info.playerInfo?.playerNick ?: "?"
        val uuid = info.playerInfo?.playerId

        val utt = buffers.getOrPut(source) { Utterance(nick, uuid) }
        utt.append(samples, getSilencePeak())

        // Translate mid-speech: as soon as the speaker pauses (a sentence break)
        // or after 6 s, flush that chunk instead of waiting for them to fully stop.
        val chunk = utt.takeReadyChunk()
        if (chunk != null) worker.submit { process(nick, uuid, chunk) }
    }

    @EventSubscribe
    fun onClosed(event: AudioSourceClosedEvent) {
        val utt = buffers.remove(event.source) ?: return
        val pcm = utt.drain()                 // whatever is left after the last chunk
        if (pcm.size < MIN_SAMPLES) return
        worker.submit { process(utt.nick, utt.uuid, pcm) }
    }

    private fun process(nick: String, uuid: UUID?, pcm48k: ShortArray) {
        try {
            // Whisper hallucinates stock phrases ("Редаktor субтитров…",
            // "Thanks for watching") on near-silent audio, and normalization
            // amplifies breath/background noise to speech level. A chunk that
            // never even reaches SPEECH_PEAK can't contain speech — skip it.
            var peak = 0
            for (s in pcm48k) {
                val a = if (s < 0) -s.toInt() else s.toInt()
                if (a > peak) peak = a
            }
            if (peak < getSpeechPeak()) return

            // Read current selections live, so menu changes apply immediately.
            val source = Languages.inputCode(inputLangEntry?.value() ?: Languages.inputIndex("ru"))
            val target = Languages.outputCode(
                outputLangEntry?.value() ?: Languages.defaultOutputIndex("pt")
            )

            val pcm16k = AudioUtil.downsample48to16(pcm48k)

            // Config parameters
            val threadsOverride = whisperThreadsEntry?.value()
            val greedyOverride = when (whisperSearchEntry?.value()) {
                0 -> true
                1 -> false
                else -> null
            }

            val route = TranslationRoute(source, target)

            val recognized = stt.transcribe(pcm16k, source, route.translateLocally, threadsOverride, greedyOverride)?.trim().orEmpty()

            if (recognized.isBlank()) return


            val showChat = printChatEntry?.value() ?: true
            val showBalloons = showBalloonsEntry?.value() ?: true

            // Flag indicators calculation
            val prefix = if (source != target) {
                val srcFlag = Languages.getFlagEmoji(source)
                val tgtFlag = Languages.getFlagEmoji(target)
                if (srcFlag.isNotEmpty() && tgtFlag.isNotEmpty()) {
                    "$srcFlag ➔ $tgtFlag "
                } else if (tgtFlag.isNotEmpty()) {
                    "➔ $tgtFlag "
                } else {
                    ""
                }
            } else {
                val srcFlag = Languages.getFlagEmoji(source)
                if (srcFlag.isNotEmpty()) "$srcFlag " else ""
            }

            // Skip MT only when the text is already in the requested language.
            if (!route.needsTranslation) {
                if (showChat) showInChat(nick, prefix, recognized)
                if (showBalloons) showAsBalloon(nick, prefix, recognized)
                return
            }

            // Local translation runs on the MT pool so the STT worker is free to
            // recognize the next chunk while native translation runs.
            val queuedAt = System.nanoTime()
            mtWorker.submit {
                if (System.nanoTime() - queuedAt > TimeUnit.SECONDS.toNanos(15)) return@submit
                try {
                    val translated = (mt.translate(recognized, route.textLanguage, target)?.trim().orEmpty())
                        .ifBlank { "[original] $recognized" }   // No online fallback
                    if (showChat) showInChat(nick, prefix, translated)
                    if (showBalloons) showAsBalloon(nick, prefix, translated)
                } catch (e: Exception) {
                    System.err.println("[LiveTranslate] translate: ${e.message}")
                    if (showChat) showInChat(nick, prefix, "[original] $recognized")
                    if (showBalloons) showAsBalloon(nick, prefix, "[original] $recognized")
                }
            }
        } catch (e: Exception) {
            System.err.println("[LiveTranslate] ${e.message}")
        }
    }

    /** Print the translated line to the client chat (must run on the render thread). */
    private fun showInChat(nick: String, prefix: String, text: String) {
        val line = Component.literal("")
            .append(Component.literal("[$nick] ").withStyle(ChatFormatting.AQUA))
        line.append(Component.literal(text))
        val mc = Minecraft.getInstance()
        mc.execute { mc.player?.sendSystemMessage(line) }
    }

    /** Display a speech balloon above the player's head. */
    private fun showAsBalloon(nick: String, prefix: String, text: String) {
        val durationSecs = balloonDurationEntry?.value() ?: 8
        val colorIdx = balloonColorEntry?.value() ?: 0
        val colors = listOf("light_blue", "yellow", "green", "red", "gold", "white")
        val color = colors.getOrNull(colorIdx) ?: "light_blue"
        BalloonManager.addBalloon(nick, text, color, durationSecs * 1000L)
    }

    /** Show model preparation progress without filling chat with download percentages. */
    private fun showStatus(text: String) {
        val line = Component.literal("[Live Translate] ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(text).withStyle(ChatFormatting.GRAY))
        val mc = Minecraft.getInstance()
        mc.execute {
            if (text.startsWith("Baixando") || text.contains("verificando integridade")) {
                mc.gui.hud.setOverlayMessage(line, false)
            } else {
                mc.player?.sendSystemMessage(line)
            }
        }
    }

    private class Utterance(val nick: String, val uuid: UUID?) {
        private val chunks = ArrayList<ShortArray>()
        private var total = 0
        private var silentRun = 0   // trailing consecutive silent samples

        @Synchronized
        fun append(samples: ShortArray, silencePeak: Int) {
            chunks.add(samples.copyOf())
            total += samples.size

            val trailingSilence = AudioUtil.trailingSilenceSamples(samples, silencePeak)
            silentRun = if (trailingSilence == samples.size) silentRun + trailingSilence else trailingSilence
        }

        /**
         * Returns the buffered PCM (and resets) once enough audio has piled up
         * AND the speaker has paused, or when the hard cap is reached — else null.
         * This is what lets long speech stream out sentence-by-sentence.
         */
        @Synchronized
        fun takeReadyChunk(): ShortArray? {
            val ready = total >= MAX_CHUNK_SAMPLES ||
                (total >= MIN_CHUNK_SAMPLES && silentRun >= SILENCE_SAMPLES)
            return if (ready) drain() else null
        }

        @Synchronized
        fun drain(): ShortArray {
            val out = ShortArray(total)
            var i = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, i, c.size)
                i += c.size
            }
            chunks.clear()
            total = 0
            silentRun = 0
            return out
        }
    }
}
