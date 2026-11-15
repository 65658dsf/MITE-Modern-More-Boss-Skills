package io.github.ningmeng123.morebossskills.mixin.common;

import io.github.ningmeng123.mite.entity.SourceBreakPortalGoal;
import io.github.ningmeng123.mite.entity.ai.MonsterFarmlandGoal;
import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import net.minecraft.world.entity.PathfinderMob;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Independent of goal installation order, optional bosses never pound doors or raid farms. @author NingMeng123 */
@Mixin(value={SourceBreakPortalGoal.class,MonsterFarmlandGoal.class},remap=false)
public abstract class BossNonDestructiveGoalsMixin {
    @Shadow(remap=false) @Final private PathfinderMob mob;

    @Inject(method={"canUse","canContinueToUse"},at=@At("HEAD"),cancellable=true)
    private void moreBossSkills$declineDestruction(CallbackInfoReturnable<Boolean> cir){
        if(mob instanceof ExtremeMob extreme&&BossAbilities.handles(extreme.extremeKind()))cir.setReturnValue(false);
    }

    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void moreBossSkills$cancelQueuedDestruction(CallbackInfo ci){
        if(mob instanceof ExtremeMob extreme&&BossAbilities.handles(extreme.extremeKind()))ci.cancel();
    }
}
