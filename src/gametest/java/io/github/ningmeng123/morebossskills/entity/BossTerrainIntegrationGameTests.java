package io.github.ningmeng123.morebossskills.entity;

import io.github.ningmeng123.mite.entity.SourceBreakPortalGoal;
import io.github.ningmeng123.mite.entity.ai.MonsterFarmlandGoal;
import io.github.ningmeng123.modernite.entity.ExtremeKind;
import io.github.ningmeng123.modernite.entity.ExtremeMobs;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real destructible blocks and vanilla controls verify that the optional veto is boss-scoped. @author NingMeng123 */
@GameTestHolder("more_boss_skills_test")
@PrefixGameTestTemplate(false)
public final class BossTerrainIntegrationGameTests {
    private BossTerrainIntegrationGameTests() {}

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void bossGriefingAndQueuedDestructiveGoalsAreBlockedWhileOrdinaryMonstersStillWork(GameTestHelper h) {
        var fixtures = new ArrayList<BossSkillsGameTests.Fixture>();
        for (var kind : List.of(ExtremeKind.ZOMBIE_BOSS, ExtremeKind.ANNIHILATION_SKELETON,
                ExtremeKind.SKELETON_ELITE_LORD, ExtremeKind.UNDYING_ZOMBIE_EMPEROR))
            fixtures.add(new BossSkillsGameTests.Fixture(h, kind));
        BossSkillsGameTests.ready(h, fixtures, () -> {
            var level = h.getLevel(); long time = level.getDayTime();
            boolean grief = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
            Difficulty difficulty = level.getDifficulty();
            var vanilla = EntityType.ZOMBIE.create(level);
            var unrelated = ExtremeMobs.type(ExtremeKind.ZOMBIE_CALLER).create(level);
            h.assertTrue(vanilla != null && unrelated != null, "Both vanilla and unrelated ITE controls create");
            try {
                level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, level.getServer());
                level.getServer().getWorldData().setDifficulty(Difficulty.HARD);
                level.setDayTime(31L * 24_000L + 18_000L);
                h.assertTrue(EventHooks.canEntityGrief(level, vanilla) && EventHooks.canEntityGrief(level, unrelated),
                        "The installed extension preserves the griefing permission of vanilla and unrelated ITE monsters");
                for (var f : fixtures) {
                    var boss = (PathfinderMob) f.boss; boss.setTarget(null);
                    h.assertFalse(EventHooks.canEntityGrief(level, boss), "The actual NeoForge griefing hook denies only the enhanced boss: " + f.kind);
                    BlockPos gate = BlockPos.containing(f.origin).east();
                    var portal = gate(h, boss, gate);
                    h.assertFalse(portal.canUse(), "A path and collision against a real wooden obstacle cannot start boss pounding");
                    set(portal, "target", gate); set(portal, "progress", portal.threshold(level.getBlockState(gate)) - 1);
                    h.assertFalse(portal.canContinueToUse(), "An already selected boss portal task cannot continue");
                    portal.tick();
                    h.assertTrue(level.getBlockState(gate).is(Blocks.OAK_TRAPDOOR), "A queued final break tick cannot remove the boss's wooden obstacle");
                    BlockPos farm = BlockPos.containing(f.origin).south(4).below(); field(h, farm);
                    boss.moveTo(Vec3.atBottomCenterOf(farm.above()));
                    var raid = new MonsterFarmlandGoal(boss); boss.tickCount += 1000;
                    h.assertFalse(raid.canUse(), "Bosses cannot schedule a raid against a real reachable farm");
                    set(raid, "target", farm); set(raid, "workTicks", MonsterFarmlandGoal.BREAK_TICKS - 1);
                    h.assertFalse(raid.canContinueToUse(), "An already queued boss farmland task cannot continue");
                    raid.tick();
                    h.assertTrue(level.getBlockState(farm).is(Blocks.FARMLAND) && level.getBlockState(farm.above()).is(Blocks.WHEAT),
                            "A queued final trampling tick preserves both the crop and its soil");
                }
                // Positive controls use the same actual goal classes and the same prepared arena.
                var first = fixtures.getFirst(); vanilla.moveTo(first.origin); vanilla.setNoAi(true); vanilla.setNoGravity(true);
                BlockPos gate = BlockPos.containing(first.origin).east(); var portal = gate(h, vanilla, gate);
                h.assertTrue(portal.canUse(), "An ordinary zombie still starts pounding the same real path obstacle");
                portal.start(); set(portal, "progress", portal.threshold(level.getBlockState(gate)) - 1); portal.tick();
                h.assertTrue(level.getBlockState(gate).isAir(), "The ordinary control's queued final tick really breaks its trapdoor");
                portal.stop();
                BlockPos farm = BlockPos.containing(first.origin).south(4).below(); field(h, farm);
                vanilla.setTarget(null); vanilla.moveTo(Vec3.atBottomCenterOf(farm.above())); vanilla.setOnGround(true);
                var raid = new MonsterFarmlandGoal(vanilla); vanilla.tickCount += 1000;
                h.assertTrue(raid.canUse(), "The vanilla control finds a genuine path to the same prepared farmland during blood night");
                raid.start(); for (int tick = 0; tick < MonsterFarmlandGoal.BREAK_TICKS; tick++) raid.tick();
                h.assertTrue(!level.getBlockState(farm).is(Blocks.FARMLAND) && level.getBlockState(farm.above()).isAir(),
                        "Ordinary farmland raiding still removes the crop and flattens the soil through the real goal");
                level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, level.getServer());
                h.assertFalse(EventHooks.canEntityGrief(level, vanilla) || EventHooks.canEntityGrief(level, unrelated),
                        "The extension does not grant ordinary monsters permission when the base game rule is disabled");
            } finally {
                vanilla.discard(); unrelated.discard(); level.setDayTime(time);
                level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(grief, level.getServer());
                level.getServer().getWorldData().setDifficulty(difficulty);
            }
        });
    }

    private static SourceBreakPortalGoal gate(GameTestHelper h, PathfinderMob mob, BlockPos gate) {
        h.getLevel().setBlock(gate, Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.EAST), 2);
        var navigation = (GroundPathNavigation) mob.getNavigation(); navigation.setCanOpenDoors(true); navigation.setCanPassDoors(true);
        navigation.moveTo(new Path(List.of(new Node(gate.getX(), gate.getY(), gate.getZ()),
                new Node(gate.getX() + 1, gate.getY(), gate.getZ())), gate.east(), true), 1);
        mob.horizontalCollision = true; return new SourceBreakPortalGoal(mob);
    }

    private static void field(GameTestHelper h, BlockPos farm) {
        h.getLevel().setBlock(farm, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), 2);
        h.getLevel().setBlock(farm.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), 2);
    }

    private static void set(Object goal, String name, Object value) {
        try { var field = goal.getClass().getDeclaredField(name); field.setAccessible(true); field.set(goal, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot prepare an already queued native goal", failure); }
    }
}
