package io.github.ningmeng123.morebossskills.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.ningmeng123.morebossskills.client.boss.BossCastAnimations;
import java.util.List;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply after native/Better Combat setup, and restore every borrowed model even on a failed draw. @author NingMeng123 */
@Mixin(LivingEntityRenderer.class)
public abstract class BossCastRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {
    @Shadow protected M model;
    @Shadow @Final protected List<RenderLayer<T, M>> layers;
    @WrapMethod(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void modernite$scopeBossCast(T entity, float yaw, float partial, PoseStack pose,
            MultiBufferSource buffers, int light, Operation<Void> original) {
        try (var scope = BossCastAnimations.begin(model, entity, partial, layers)) {
            original.call(entity, yaw, partial, pose, buffers, light);
        }
    }
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getRenderType(Lnet/minecraft/world/entity/LivingEntity;ZZZ)Lnet/minecraft/client/renderer/RenderType;"))
    private void modernite$poseBoss(T entity, float yaw, float partial, PoseStack pose,
            MultiBufferSource buffers, int light, CallbackInfo ci) {
        BossCastAnimations.apply(model, entity);
    }
}
