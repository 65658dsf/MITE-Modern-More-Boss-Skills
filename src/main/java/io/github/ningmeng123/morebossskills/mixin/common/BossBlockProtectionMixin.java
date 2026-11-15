package io.github.ningmeng123.morebossskills.mixin.common;

import io.github.ningmeng123.mite.entity.R196DiggerGoal;
import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Defense in depth for a queued digger left by another AI integration. @author NingMeng123 */
@Mixin(value=R196DiggerGoal.class,remap=false)
public abstract class BossBlockProtectionMixin {
    @Shadow(remap=false) @Final private PathfinderMob mob;
    @Inject(method="canDestroyMaterial",at=@At("HEAD"),cancellable=true)
    private void moreBossSkills$protectTerrain(BlockPos pos,CallbackInfoReturnable<Boolean> cir){
        if(mob instanceof ExtremeMob extreme&&BossAbilities.handles(extreme.extremeKind()))cir.setReturnValue(false);
    }
}
