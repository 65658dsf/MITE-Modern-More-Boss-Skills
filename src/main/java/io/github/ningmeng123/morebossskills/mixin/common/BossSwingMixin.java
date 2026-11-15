package io.github.ningmeng123.morebossskills.mixin.common;

import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shared inherited swing entry, guarded to the four actively casting addon bosses. @author NingMeng123 */
@Mixin(LivingEntity.class)
public abstract class BossSwingMixin {
    @Inject(method="swing(Lnet/minecraft/world/InteractionHand;Z)V",at=@At("HEAD"),cancellable=true)
    private void moreBossSkills$suspendSwing(InteractionHand hand,boolean updateSelf,CallbackInfo ci){
        if((Object)this instanceof Mob mob&&BossAbilities.isBusy(mob))ci.cancel();
    }
}
