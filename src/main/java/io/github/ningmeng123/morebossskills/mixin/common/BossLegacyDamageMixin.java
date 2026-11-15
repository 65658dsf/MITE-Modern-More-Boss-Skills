package io.github.ningmeng123.morebossskills.mixin.common;

import io.github.ningmeng123.modernite.entity.ExtremeMobEvents;
import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spell transactions retain attribution but must not masquerade as ordinary ITE melee effects. @author NingMeng123 */
@Mixin(value=ExtremeMobEvents.class,remap=false)
public abstract class BossLegacyDamageMixin {
    @Inject(method="damaged",at=@At("HEAD"),cancellable=true)
    private static void moreBossSkills$ignoreSpellSecondaryEffects(LivingDamageEvent.Post event,CallbackInfo ci){
        if(BossAbilities.isSkillDamage(event.getSource()))ci.cancel();
    }
}
