package io.github.ningmeng123.morebossskills.mixin.common;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.ningmeng123.modernite.entity.ExtremeKind;
import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.modernite.entity.MobAbilities;
import io.github.ningmeng123.modernite.entity.MobMemory;
import io.github.ningmeng123.morebossskills.integration.BossIntegration;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps ITE's tick intact while replacing only its old zombie-boss anti-kiting branch. @author NingMeng123 */
@Mixin(value=MobAbilities.class,remap=false)
public abstract class BossAbilitiesMixin {
    @WrapMethod(method="tick")
    private static void moreBossSkills$tick(Mob mob,ExtremeMob state,Operation<Void> original){
        original.call(mob,state);
        BossIntegration.afterTick(mob,state);
    }

    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Mob;distanceToSqr(Lnet/minecraft/world/entity/Entity;)D"))
    private static double moreBossSkills$replaceInstantLightning(Mob mob,Entity target,Operation<Double> original){
        // All earlier distance branches in this original method belong to other exact ExtremeKinds.
        // Returning zero only for ZOMBIE_BOSS skips its final >2.4 branch, without touching those kinds.
        if(mob instanceof ExtremeMob extreme&&extreme.extremeKind()==ExtremeKind.ZOMBIE_BOSS)return 0;
        return original.call(mob,target);
    }

    @WrapOperation(method="tick",at=@At(value="INVOKE",target="Lio/github/ningmeng123/modernite/entity/MobAbilities;bossDisengaged(Lnet/minecraft/world/entity/Mob;Lio/github/ningmeng123/modernite/entity/MobMemory;)V"))
    private static void moreBossSkills$deferChallenge(Mob mob,MobMemory memory,Operation<Void> original){
        if(!(mob instanceof ExtremeMob extreme)||extreme.extremeKind()!=ExtremeKind.ZOMBIE_BOSS)original.call(mob,memory);
    }

    @Inject(method="configureGoals",at=@At("TAIL"))
    private static void moreBossSkills$goals(Mob mob,ExtremeKind kind,CallbackInfo ci){BossIntegration.configureGoals(mob,kind);}
}
