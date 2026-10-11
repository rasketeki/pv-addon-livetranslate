package dev.livetranslate

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import su.plo.voice.api.client.PlasmoVoiceClient

/**
 * Client entrypoint (Kotlin adapter): hands our add-on to Plasmo Voice's
 * client loader. The add-on transcribes + translates voice chat and prints
 * each line to the Minecraft chat (client-side only).
 */
object LiveTranslateMod : ClientModInitializer {

    private val addon = LiveTranslateAddon()

    override fun onInitializeClient() {
        PlasmoVoiceClient.getAddonsLoader().load(addon)

        // Register client tick event to clean up expired speech balloons
        ClientTickEvents.END_CLIENT_TICK.register {
            BalloonManager.tick()
        }

        // Speech balloon rendering is handled by AvatarRendererMixin
    }
}
