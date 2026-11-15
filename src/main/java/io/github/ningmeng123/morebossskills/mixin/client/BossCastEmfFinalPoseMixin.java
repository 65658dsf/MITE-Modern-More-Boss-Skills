package io.github.ningmeng123.morebossskills.mixin.client;

import io.github.ningmeng123.morebossskills.client.boss.BossCastAnimations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional final EMF/FA pose; no dependency is loaded when EMF is absent. @author NingMeng123 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPartRoot", remap = false)
public abstract class BossCastEmfFinalPoseMixin {
    @Inject(method = "animate()V", at = @At("TAIL"), require = 0)
    private void modernite$bossCastAfterPackAnimation(CallbackInfo ci) { BossCastAnimations.afterEmf(this); }
}
