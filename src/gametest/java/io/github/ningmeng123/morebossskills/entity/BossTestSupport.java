package io.github.ningmeng123.morebossskills.entity;

import io.github.ningmeng123.mite.worldgen.BloodMoonInvasionData;
import io.github.ningmeng123.modernite.world.ExtremeWorldState;
import io.github.ningmeng123.morebossskills.config.BossConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Small local fixtures; the add-on does not import another project's test source set. @author NingMeng123 */
final class BossTestSupport {
    private BossTestSupport() {}

    static final class Settings implements AutoCloseable {
        private final double damage = BossConfig.DAMAGE_MULTIPLIER.get();
        private final double cooldown = BossConfig.COOLDOWN_MULTIPLIER.get();
        void set(String key, double value) {
            switch (key) {
                case "boss_skill_damage_multiplier" -> BossConfig.DAMAGE_MULTIPLIER.set(value);
                case "boss_skill_cooldown_multiplier" -> BossConfig.COOLDOWN_MULTIPLIER.set(value);
                default -> throw new IllegalArgumentException("Unknown add-on test setting: " + key);
            }
        }
        @Override public void close() {
            BossConfig.DAMAGE_MULTIPLIER.set(damage); BossConfig.COOLDOWN_MULTIPLIER.set(cooldown);
        }
    }

    static final class Site implements AutoCloseable {
        private final ServerLevel level;
        private final Vec3 origin;
        private final long time, highest;
        private final boolean dirty, grace;
        private final List<Mob> mobs = new ArrayList<>();
        Site(GameTestHelper helper) {
            level = helper.getLevel(); origin = helper.absolutePos(new BlockPos(2, 2, 2)).getCenter().add(0, 150, 0);
            time = level.getServer().overworld().getDayTime(); var history = BloodMoonInvasionData.get(level);
            highest = history.highestDay(); dirty = history.isDirty(); grace = ExtremeWorldState.graceEnded(level);
        }
        void date(long day, long historical, boolean graceEnded) {
            level.getServer().overworld().setDayTime((day - 1) * 24_000L + 1000);
            BloodMoonInvasionData.get(level).restoreProgress(historical);
            ExtremeWorldState.restoreGraceEnded(level, graceEnded);
        }
        Mob mob(EntityType<?> type) {
            Mob mob = (Mob) type.create(level);
            if (mob == null) throw new AssertionError("Registered base boss creates");
            mob.moveTo(origin); mob.setNoAi(true); mobs.add(mob); return mob;
        }
        @Override public void close() {
            mobs.forEach(Mob::discard); level.getServer().overworld().setDayTime(time);
            var history = BloodMoonInvasionData.get(level); history.restoreProgress(highest); history.setDirty(dirty);
            ExtremeWorldState.restoreGraceEnded(level, grace);
        }
    }
}
