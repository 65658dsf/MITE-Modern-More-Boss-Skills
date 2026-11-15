package io.github.ningmeng123.morebossskills.mixin.client;

import io.github.ningmeng123.morebossskills.client.boss.BossCastAnimations;
import net.minecraft.client.model.HumanoidModel;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Save a shared custom armor model before NeoForge copies the already posed default into it. @author NingMeng123 */
@Mixin(value = ClientHooks.class, remap = false)
public abstract class BossCastArmorCopyMixin {
    @Inject(method = "copyModelProperties", at = @At("HEAD"))
    private static void modernite$preserveBossArmorReplacement(HumanoidModel<?> original,
            HumanoidModel<?> replacement, CallbackInfo ci) {
        BossCastAnimations.preserveArmorCopy(original, replacement);
    }
}
