package io.github.ningmeng123.morebossskills.entity;

import io.github.ningmeng123.modernite.entity.ExtremeKind;
import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkill;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPreview;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Three-stomp gaps require deliberate dodging and keep their announced direction for each wave. @author NingMeng123 */
@GameTestHolder("more_boss_skills_test")
@PrefixGameTestTemplate(false)
public final class BossTripleDirectionGameTests {
    private BossTripleDirectionGameTests() {}

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void threeStompsHitTheStationaryTargetWhileTheRearGapAndTimedJumpsStaySafe(GameTestHelper h) {
        var f = new BossSkillsGameTests.Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        f.player.setPos(f.origin.add(5, 0, 0));
        var rear = f.addPlayer(f.origin.add(-5, 0, 0));
        var groundedSide = f.addPlayer(f.origin.add(0, 0, -5));
        var jumping = f.addPlayer(f.origin.add(0, 0, 5));
        BossSkillsGameTests.ready(h, List.of(f), () -> {
            f.cast(BossSkill.ZOMBIE_TRIPLE);
            for (int wave = 0; wave < 3; wave++) {
                jumping.setPos(f.origin.add(0, 0, 5));
                advance(f, 20);
                h.assertTrue(f.hitCount(f.player) == wave && f.hitCount(groundedSide) == wave,
                        "The next wave's twenty-tick warning does not damage either grounded control");
                jumping.setPos(f.origin.add(0, 1.1, 5));
                advance(f, 20);
                h.assertTrue(f.hitCount(f.player) == wave + 1 && f.hitCount(groundedSide) == wave + 1,
                        "Each expanding wave hits the stationary target and the side control exactly once: wave " + (wave + 1));
                h.assertTrue(f.hitCount(rear) == 0 && f.hitCount(jumping) == 0,
                        "The opposite-side gap and a jump during the passing rim remain genuine safe routes");
            }
            h.assertTrue(f.player.getHealth() < 1000 && rear.getHealth() == 1000 && jumping.getHealth() == 1000,
                    "Actual damage, not only geometric classification, distinguishes exposed and dodging players");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void eachAnnouncedGapLocksUntilItsWaveEndsAndTheFollowingWaveAimsAgain(GameTestHelper h) {
        var parked = new BossSkillsGameTests.Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        var movingAgain = new BossSkillsGameTests.Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        List<BossSkillsGameTests.Fixture> fixtures = List.of(parked, movingAgain);
        for (var f : fixtures) f.player.setPos(f.origin.add(5, 0, 0));
        BossSkillsGameTests.ready(h, fixtures, () -> {
            for (var f : fixtures) {
                f.cast(BossSkill.ZOMBIE_TRIPLE); advance(f, 10);
                f.player.setPos(f.origin.add(-5, 0, 0));
                assertAim(h, f, f.origin.add(5, 1, 0), "Moving into the first announced gap does not rotate that wave's look target");
                advance(f, 29);
                h.assertTrue(f.hitCount(f.player) == 0, "Crossing to the advertised first gap avoids the real first shockwave");
                assertAim(h, f, f.origin.add(5, 1, 0), "The first wave retains its aim throughout its expansion");
                f.step();
                assertAim(h, f, f.origin.add(-5, 1, 0), "At the second warning's start the caster locks the player's new position");
            }
            advance(movingAgain, 10);
            movingAgain.player.setPos(movingAgain.origin.add(5, 0, 0));
            assertAim(h, movingAgain, movingAgain.origin.add(-5, 1, 0), "The second warning also stays fixed after another deliberate dodge");
            advance(movingAgain, 30);
            h.assertTrue(movingAgain.hitCount(movingAgain.player) == 0, "A second deliberate crossing avoids the second locked wave");
            assertAim(h, movingAgain, movingAgain.origin.add(5, 1, 0), "The third warning independently reacquires the now-returned player");
            advance(parked, 40);
            h.assertTrue(parked.hitCount(parked.player) == 1, "Remaining in the old first-wave gap is punished by the second wave");
            assertAim(h, parked, parked.origin.add(-5, 1, 0), "The third wave also locks the stationary player's current side");
            parked.finishCast(); movingAgain.finishCast();
            h.assertTrue(parked.hitCount(parked.player) == 2 && movingAgain.hitCount(movingAgain.player) == 1,
                    "Old gaps never grant permanent immunity: standing still after either dodge is hit by the next aimed wave");
            h.assertTrue(BossAbilities.castingAim(parked.boss) == null && BossAbilities.castingAim(movingAgain.boss) == null,
                    "Finishing the three-wave cast releases its temporary facing target");
        });
    }

    @GameTest(template = "gear_empty")
    public static void previewUsesTheOppositeTripleGapWithoutReversingTyrantTideOrEmperorSun(GameTestHelper h) {
        var viewer = h.makeMockPlayer(GameType.SURVIVAL);
        Vec3 center = new Vec3(1_700_000.5, 70.025, -1_700_000.5);
        try {
            for (Vec3 facing : List.of(new Vec3(1, 0, 0), new Vec3(0, 0, 1),
                    new Vec3(-1, 0, 0), new Vec3(0, 0, -1), new Vec3(.6, 0, .8))) {
                Vec3 input = facing.scale(5).add(0, 9, 0);
                var triple = BossSkillPreview.describe(viewer, BossSkill.ZOMBIE_TRIPLE, center, input, 1000, 2000);
                h.assertTrue(triple.size() == 3, "Preview still announces all three shockwaves");
                for (int wave = 0; wave < 3; wave++) {
                    var packet = triple.get(wave);
                    h.assertTrue(packet.effect() == BossSkillPayload.Effect.RING && packet.valid()
                                    && packet.startTick() == 1000 + wave * 40L && packet.windup() == 20 && packet.duration() == 20
                                    && packet.radius() == 6 + wave * 2 && packet.gapDegrees() == 90,
                            "Changing the gap direction preserves the three warnings, spread timings, radii and ninety-degree opening");
                    assertDirection(h, packet, center, facing, -1, "Every triple-stomp preview opens opposite the displayed target direction");
                }
                var tide = BossSkillPreview.describe(viewer, BossSkill.TYRANT_TIDE, center, input, 1000, 3000);
                h.assertTrue(tide.size() == 3, "The tyrant keeps all three existing waves");
                for (var packet : tide) {
                    assertDirection(h, packet, center, facing, 1, "Tyrant tide retains its original forward-facing escape gap");
                    h.assertTrue(packet.gapDegrees() == 70 && packet.windup() == 24 && packet.duration() == 30 && packet.radius() == 12,
                            "The tyrant's unrelated wave shape and timing remain unchanged");
                }
                var sun = BossSkillPreview.describe(viewer, BossSkill.EMPEROR_SUN, center, input, 1000, 4000);
                var rings = sun.stream().filter(packet -> packet.effect() == BossSkillPayload.Effect.RING).toList();
                h.assertTrue(sun.size() == 3 && rings.size() == 2, "The emperor retains the sun body and its two waves");
                for (var packet : rings) {
                    assertDirection(h, packet, center, facing, 1, "The emperor's sun waves retain their original forward-facing gaps");
                    h.assertTrue(packet.gapDegrees() == 90 && packet.windup() == 24 && packet.duration() == 30 && packet.radius() == 12,
                            "The emperor's unrelated wave shape and timing remain unchanged");
                }
            }
        } finally { viewer.discard(); }
        h.succeed();
    }

    private static void advance(BossSkillsGameTests.Fixture fixture, int ticks) {
        for (int tick = 0; tick < ticks; tick++) fixture.step();
    }

    private static void assertAim(GameTestHelper h, BossSkillsGameTests.Fixture fixture, Vec3 expected, String message) {
        Vec3 actual = BossAbilities.castingAim(fixture.boss);
        h.assertTrue(actual != null && actual.distanceToSqr(expected) < 1e-8, message);
    }

    private static void assertDirection(GameTestHelper h, BossSkillPayload packet, Vec3 center, Vec3 facing, int sign, String message) {
        Vec3 gap = packet.to().subtract(packet.from());
        h.assertTrue(packet.from().distanceToSqr(center) < 1e-10 && Math.abs(gap.y) < 1e-10
                        && gap.lengthSqr() > .9 && gap.normalize().dot(facing) * sign > .999999,
                message);
    }
}
