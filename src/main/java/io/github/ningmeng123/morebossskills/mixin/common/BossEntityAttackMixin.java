package io.github.ningmeng123.morebossskills.mixin.common;

import io.github.ningmeng123.modernite.entity.ExtremeMobs;
import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reject before the superclass can schedule a delayed Better Combat swing. @author NingMeng123 */
@Mixin(value={ExtremeMobs.ZombieVariant.class,ExtremeMobs.SkeletonVariant.class},remap=false)
public abstract class BossEntityAttackMixin {
    @Inject(method="doHurtTarget",at=@At("HEAD"),cancellable=true)
    private void moreBossSkills$suspendMelee(Entity target,CallbackInfoReturnable<Boolean> cir){
        if(BossAbilities.isBusy((Mob)(Object)this))cir.setReturnValue(false);
    }
}
