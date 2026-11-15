package io.github.ningmeng123.morebossskills.integration;

import io.github.ningmeng123.mite.entity.R196DiggerGoal;
import io.github.ningmeng123.mite.entity.SourceMonsterTargeting;
import io.github.ningmeng123.modernite.entity.ExtremeKind;
import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.modernite.entity.MobMemory;
import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import io.github.ningmeng123.morebossskills.entity.boss.BossCastGoal;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload;
import io.github.ningmeng123.morebossskills.mixin.common.BossChallengeInvoker;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;

/** Narrow additions after ITE's unchanged spawn, support and lifetime logic. @author NingMeng123 */
public final class BossIntegration {
    private BossIntegration() {}

    public static void configureGoals(Mob mob,ExtremeKind kind){
        if(!BossAbilities.handles(kind))return;
        mob.goalSelector.getAvailableGoals().stream().map(WrappedGoal::getGoal)
                .filter(goal->goal instanceof R196DiggerGoal||goal instanceof BreakDoorGoal)
                .toList().forEach(mob.goalSelector::removeGoal);
        if(mob.goalSelector.getAvailableGoals().stream().noneMatch(goal->goal.getGoal() instanceof BossCastGoal))
            mob.goalSelector.addGoal(0,new BossCastGoal(mob));
        if(mob instanceof Zombie zombie)zombie.setCanBreakDoors(false);
    }

    public static void afterTick(Mob mob,ExtremeMob extreme){
        if(!BossAbilities.handles(extreme.extremeKind())||!(mob.level() instanceof ServerLevel level)
                ||!mob.isAlive()||mob.isRemoved())return;
        BossAbilities.tick(mob,extreme);
        if(extreme.extremeKind()!=ExtremeKind.ZOMBIE_BOSS)return;
        MobMemory memory=extreme.extremeMemory();
        LivingEntity target=mob.getTarget();
        if(target==null||!target.isAlive()||target instanceof Player player&&(player.isCreative()||player.isSpectator())){
            if(!BossAbilities.isBusy(mob))disengage(mob,memory);
            return;
        }
        if(!(target instanceof Player)||BossAbilities.isBusy(mob))return;
        if(mob.distanceToSqr(target)>24*24)disengage(mob,memory);
        if(mob.distanceToSqr(target)<=2.4*2.4)return;
        if(++memory.summons<60)return;
        var nearby=level.getEntitiesOfClass(Player.class,mob.getBoundingBox().inflate(16),
                player->player.isAlive()&&!player.isCreative()&&!player.isSpectator()
                        &&(player==target||SourceMonsterTargeting.canSee(mob,player,false)));
        if(!nearby.isEmpty())mob.setTarget(nearby.get(mob.getRandom().nextInt(nearby.size())));
        memory.summons=0;
    }

    private static void disengage(Mob mob,MobMemory memory){
        boolean reset=memory.cooldown<=0&&(!memory.contribution.isEmpty()||mob.getHealth()<mob.getMaxHealth());
        BossChallengeInvoker.moreBossSkills$disengage(mob,memory);
        if(reset){
            BossAbilities.stop(mob);
            BossStates.of(memory).read(new CompoundTag());
            if(mob.level() instanceof ServerLevel level)BossSkillPayload.sendCancel(level,mob);
        }
    }
}
