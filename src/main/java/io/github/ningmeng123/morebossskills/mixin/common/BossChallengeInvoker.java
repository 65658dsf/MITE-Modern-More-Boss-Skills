package io.github.ningmeng123.morebossskills.mixin.common;

import io.github.ningmeng123.modernite.entity.MobAbilities;
import io.github.ningmeng123.modernite.entity.MobMemory;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuses ITE's original failure announcement and contribution reset. @author NingMeng123 */
@Mixin(value=MobAbilities.class,remap=false)
public interface BossChallengeInvoker {
    @Invoker("bossDisengaged")
    static void moreBossSkills$disengage(Mob mob,MobMemory memory){throw new AssertionError("Mixin invoker missing");}
}
