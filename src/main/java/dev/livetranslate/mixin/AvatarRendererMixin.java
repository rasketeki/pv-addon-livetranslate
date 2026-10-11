package dev.livetranslate.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.livetranslate.BalloonManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedList;

/**
 * Hooks into AvatarRenderer.submitNameDisplay to render speech balloons
 * directly above the player's name tag. This subclass-specific injection is required
 * because AvatarRenderer overrides submitNameDisplay, bypassing base EntityRenderer mixins.
 */
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {

    @Inject(
        method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("RETURN")
    )
    private void livetranslate$renderBalloons(
            AvatarRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState cameraRenderState,
            CallbackInfo ci
    ) {
        if (state.nameTag == null) return;

        String nameTagText = state.nameTag.getString();

        LinkedList<BalloonManager.Balloon> balloons = BalloonManager.INSTANCE.getBalloonsForNameTag(nameTagText);
        if (balloons == null || balloons.isEmpty()) return;

        poseStack.pushPose();

        // Offset above the existing name tag.
        // submitNameTag uses nameTagAttachment which already positions at the
        // nametag height. We need to push UP (positive Y in model space) by
        // enough to clear the nametag text. Each text line at scale 0.025 is
        // about 0.25 units tall; start one line above the nametag.
        float lineStep = 0.35f;
        poseStack.translate(0.0f, lineStep, 0.0f);

        // Fetch light coordinate from the EntityRenderState base fields
        int light = state.lightCoords;

        synchronized (balloons) {
            for (int i = balloons.size() - 1; i >= 0; i--) {
                BalloonManager.Balloon balloon = balloons.get(i);

                ChatFormatting color = balloonColor(balloon.getColor());
                Component text = Component.literal(balloon.getMessage()).withStyle(color);

                collector.submitNameTag(
                        poseStack,
                        state.nameTagAttachment,
                        light,
                        text,
                        !state.isDiscrete,      // show background when not sneaking
                        0,                       // no outline color
                        cameraRenderState
                );

                // Move up for the next balloon
                poseStack.translate(0.0f, lineStep, 0.0f);
            }
        }

        poseStack.popPose();
    }

    /** Maps balloon color names to ChatFormatting. */
    private static ChatFormatting balloonColor(String name) {
        return switch (name) {
            case "light_blue", "aqua" -> ChatFormatting.AQUA;
            case "yellow"            -> ChatFormatting.YELLOW;
            case "green"             -> ChatFormatting.GREEN;
            case "red"               -> ChatFormatting.RED;
            case "gold"              -> ChatFormatting.GOLD;
            case "white"             -> ChatFormatting.WHITE;
            default                  -> ChatFormatting.AQUA;
        };
    }
}
