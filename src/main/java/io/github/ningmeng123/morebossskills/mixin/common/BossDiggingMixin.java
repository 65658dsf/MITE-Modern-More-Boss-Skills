package io.github.ningmeng123.morebossskills.mixin.common;

import io.github.ningmeng123.mite.entity.R196MonsterAi;
import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import net.minecraft.world.entity.PathfinderMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional bosses resolve obstructions with announced skills, never excavation. @author NingMeng123 */
@Mixin(value=R196MonsterAi.class,remap=false)
public abstract class BossDiggingMixin {
    @Inject(method="diggingEnabled",at=@At("HEAD"),cancellable=true)
    private static void moreBossSkills$noDigging(PathfinderMob mob,CallbackInfoReturnable<Boolean> cir){
        if(mob instanceof ExtremeMob extreme&&BossAbilities.handles(extreme.extremeKind()))cir.setReturnValue(false);
    }
}
