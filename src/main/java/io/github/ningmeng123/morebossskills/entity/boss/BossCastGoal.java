package io.github.ningmeng123.morebossskills.entity.boss;

import java.util.EnumSet;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/** Keeps normal movement and attacks from overlapping an announced cast. @author NingMeng123 */
public final class BossCastGoal extends Goal {
    private final Mob mob;
    public BossCastGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }
    @Override public boolean canUse() { return BossAbilities.isBusy(mob); }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { mob.getNavigation().stop(); }
    @Override public void tick() {
        mob.getNavigation().stop();
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
        var aim = BossAbilities.castingAim(mob);
        if (aim != null) mob.getLookControl().setLookAt(aim.x, aim.y, aim.z, 30, 30);
        else if (mob.getTarget() != null) mob.getLookControl().setLookAt(mob.getTarget(), 30, 30);
    }
}
