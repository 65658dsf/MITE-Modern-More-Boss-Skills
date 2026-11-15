package io.github.ningmeng123.morebossskills.integration;

import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.morebossskills.MoreBossSkills;
import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Optional event wiring; ITE itself never needs to reference the addon. @author NingMeng123 */
@EventBusSubscriber(modid=MoreBossSkills.MOD_ID)
public final class BossEvents {
    private BossEvents() {}
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void griefing(EntityMobGriefingEvent event){
        // Also covers native burning-monster ignition, farmland landing and vanilla egg trampling.
        if(event.getEntity() instanceof ExtremeMob extreme&&BossAbilities.handles(extreme.extremeKind()))
            event.setCanGrief(false);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void incoming(LivingIncomingDamageEvent event){
        if(event.isCanceled())return;
        Mob attacker=attacker(event.getSource());
        if(attacker!=null&&BossAbilities.isBusy(attacker)&&event.getSource().is(DamageTypes.MOB_ATTACK)
                &&event.getSource().getDirectEntity()==attacker)event.setCanceled(true);
    }
    @SubscribeEvent public static void damaged(LivingDamageEvent.Post event){
        if(!(event.getNewDamage()>0)||!Float.isFinite(event.getNewDamage()))return;
        Mob attacker=attacker(event.getSource());
        if(attacker!=null&&!BossAbilities.isSkillDamage(event.getSource())&&event.getSource().getDirectEntity()==attacker)
            BossAbilities.onMeleeHit(attacker);
        if(event.getEntity() instanceof Mob mob&&mob instanceof ExtremeMob extreme&&BossAbilities.handles(extreme.extremeKind())
                &&event.getSource().getEntity() instanceof LivingEntity source)
            BossAbilities.onHurt(mob,extreme,source,event.getNewDamage());
    }
    @SubscribeEvent public static void tracking(PlayerEvent.StartTracking event){
        if(event.getEntity() instanceof ServerPlayer player&&event.getTarget() instanceof Mob mob)BossAbilities.onTracking(player,mob);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void death(LivingDeathEvent event){
        if(!event.isCanceled()&&!event.getEntity().level().isClientSide&&event.getEntity() instanceof Mob mob)BossAbilities.stop(mob);
    }
    @SubscribeEvent public static void leave(EntityLeaveLevelEvent event){
        if(!event.getLevel().isClientSide&&event.getEntity() instanceof Mob mob)BossAbilities.stop(mob);
    }
    private static Mob attacker(DamageSource source){
        if(source.getEntity() instanceof Mob mob)return mob;
        return source.getDirectEntity() instanceof Projectile projectile&&projectile.getOwner() instanceof Mob mob?mob:null;
    }
}
