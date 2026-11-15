package io.github.ningmeng123.morebossskills.entity;

import io.github.ningmeng123.modernite.entity.*;
import io.github.ningmeng123.morebossskills.integration.BossStates;

import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import io.github.ningmeng123.morebossskills.entity.boss.BossGeometry;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkill;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Registered bosses, native collision and actual damage transactions. @author NingMeng123 */
@GameTestHolder("more_boss_skills_test")
@PrefixGameTestTemplate(false)
public final class BossSkillsGameTests {
    private static final AtomicInteger IDS = new AtomicInteger();
    private BossSkillsGameTests() {}

    @GameTest(template = "gear_empty")
    public static void finiteBeamAndMovingWaveGeometryRemainCorrectFarFromTheOrigin(GameTestHelper h) {
        for (Vec3 origin : new Vec3[]{Vec3.ZERO, new Vec3(1_700_000, -40, -1_700_000)}) {
            Vec3 start = origin.add(0, 2, 0), end = origin.add(10, 2, 0);
            h.assertTrue(Math.abs(BossGeometry.distanceToSegment(origin.add(5, 8, 3), start, end) - 3) < 1e-8,
                    "The middle of a finite horizontal beam is three blocks from the side point");
            h.assertTrue(Math.abs(BossGeometry.distanceToSegment(origin.add(14, 2, 3), start, end) - 5) < 1e-8,
                    "A ray beyond its end uses the endpoint, not an infinite extension");
            h.assertTrue(Math.abs(BossGeometry.distanceToSegment(origin.add(3, 2, 4), start, start) - 5) < 1e-8,
                    "A zero-length path remains finite");
        }
        Vec3 center = new Vec3(32, 70, -32);
        for (Vec3 escape : new Vec3[]{new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(0, 0, -1)}) {
            Vec3 side = new Vec3(-escape.z, 0, escape.x);
            h.assertFalse(BossGeometry.inRing(center, center, escape, 6, 1, 60), "The center is behind the moving rim");
            h.assertFalse(BossGeometry.inRing(center.add(escape.scale(6)), center, escape, 6, 1, 60), "The indicated gap stays safe");
            h.assertTrue(BossGeometry.inRing(center.add(side.scale(6)), center, escape, 6, 1, 60), "The side is a dangerous rim");
            h.assertFalse(BossGeometry.inRing(center.add(side.scale(7)), center, escape, 6, 1, 60), "A wave cannot hit ahead of its rim");
            h.assertFalse(BossGeometry.inRing(center.add(side.scale(5)), center, escape, 6, 1, 60), "A passed wave leaves the inner disk safe");
        }
        Vec3 facing = BossGeometry.horizontalDirection(new Vec3(5, 2, 5), new Vec3(5, 30, -5));
        h.assertTrue(facing.distanceToSqr(new Vec3(0, 0, -1)) < 1e-10, "A height difference does not tilt a ground-facing vector");
        Vec3 same = BossGeometry.horizontalDirection(new Vec3(5, 2, 5), new Vec3(5, 30, 5));
        h.assertTrue(Double.isFinite(same.length()) && Math.abs(same.length() - 1) < 1e-9 && same.y == 0,
                "Coincident horizontal positions still have a finite unit direction");
        h.succeed();
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void allSixteenSkillsRunThroughTheirRealSchedulerWithoutChangingTerrain(GameTestHelper h) {
        List<Fixture> fixtures = new ArrayList<>();
        for (ExtremeKind kind : new ExtremeKind[]{ExtremeKind.ZOMBIE_BOSS, ExtremeKind.ANNIHILATION_SKELETON,
                ExtremeKind.SKELETON_ELITE_LORD, ExtremeKind.UNDYING_ZOMBIE_EMPEROR}) {
            Fixture f = new Fixture(h, kind); fixtures.add(f);
            if (kind == ExtremeKind.SKELETON_ELITE_LORD || kind == ExtremeKind.UNDYING_ZOMBIE_EMPEROR) f.addFollower();
        }
        ready(h, fixtures, () -> {
            int checked = 0;
            for (Fixture f : fixtures) {
                long terrain = f.terrainFingerprint();
                for (BossSkill skill : BossSkill.values()) if (skill.kind == f.kind) {
                    f.player.setHealth(1000); f.cast(skill);
                    h.assertTrue(BossStates.of(f.extreme).cooldown(skill) > 0, skill + " starts its independent cooldown");
                    f.finishCast();
                    h.assertTrue(f.boss.isAlive() && !f.boss.isRemoved(), skill + " completes with a live caster");
                    for (int recovery = 0; recovery < 20; recovery++) f.step();
                    h.assertFalse(BossAbilities.isBusy(f.boss), skill + " releases the shared recovery window");
                    checked++;
                }
                h.assertTrue(terrain == f.terrainFingerprint(), f.kind + " preserves the full arena across every skill including its ultimate");
            }
            h.assertTrue(checked == 16, "Every one of the sixteen public skills was executed");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void groundMarksFollowTheLocalFloorAndHalfSlabInsteadOfTheUpperStorey(GameTestHelper h) {
        Fixture f = new Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        ready(h, List.of(f), () -> {
            for (int x = -2; x <= 2; x++) for (int z = 3; z <= 7; z++) f.block(x, 3, z, Blocks.STONE);
            Vec3 ground = BossGeometry.ground(h.getLevel(), f.boss, f.origin.add(0, 0, 5)).orElseThrow();
            h.assertTrue(Math.abs(ground.y - f.origin.y) < .1, "The lower room's warning remains on its floor beneath the upper storey");
            h.getLevel().setBlock(BlockPos.containing(f.origin).offset(0, 0, 5), Blocks.STONE_SLAB.defaultBlockState(), 2);
            Vec3 slab = BossGeometry.ground(h.getLevel(), f.boss, f.origin.add(0, .5, 5)).orElseThrow();
            h.assertTrue(Math.abs(slab.y - (f.origin.y + .5)) < .1, "A half slab's actual collision top supports the marker");
            h.assertTrue(slab.y < f.origin.y + 1, "The ceiling is never mistaken for the fighting surface");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void configuredLongCooldownSurvivesSavingWithoutResumingTheCast(GameTestHelper h) {
        Fixture f = new Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        ready(h, List.of(f), () -> {
            try (var settings = new BossTestSupport.Settings()) {
                settings.set("boss_skill_cooldown_multiplier", 2D); f.cast(BossSkill.ZOMBIE_MARK);
                h.assertTrue(BossStates.of(f.extreme).cooldown(BossSkill.ZOMBIE_MARK) == 400, "The configured twofold cooldown is used for a newly started skill");
                var saved = new net.minecraft.nbt.CompoundTag(); BossStates.of(f.extreme).write(saved);
                var restored = new io.github.ningmeng123.morebossskills.entity.boss.BossCombatState(); restored.read(saved);
                h.assertTrue(restored.cooldown(BossSkill.ZOMBIE_MARK) == 400, "Reading a saved cast retains the full configured remaining cooldown");
                h.assertTrue(restored.activeSkill() == null && restored.recoveryTicks() > 0, "A saved windup becomes safe recovery rather than an invisible resumed strike");
            }
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void warningDoesNoDamageAndLeavingTheLockedMarkAvoidsTheStrike(GameTestHelper h) {
        Fixture dodged = new Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        Fixture control = new Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        ready(h, List.of(dodged, control), () -> {
            dodged.cast(BossSkill.ZOMBIE_MARK); control.cast(BossSkill.ZOMBIE_MARK);
            for (int tick = 0; tick < BossSkill.ZOMBIE_MARK.windupTicks() - 2; tick++) { dodged.step(); control.step(); }
            h.assertTrue(dodged.hits == 0 && control.hits == 0, "Neither target is hurt during the visible windup");
            dodged.player.setPos(dodged.origin.add(12, 0, 8));
            dodged.finishCast(); control.finishCast();
            h.assertTrue(dodged.hits == 0 && dodged.player.getHealth() == 1000, "Moving out of the locked mark avoids its damage");
            h.assertTrue(control.hits == 1 && control.player.getHealth() < 1000, "The stationary control receives exactly one real mark strike");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void opaqueStoneBlocksStraightSlashAndSoulChain(GameTestHelper h) {
        Fixture slash = new Fixture(h, ExtremeKind.ANNIHILATION_SKELETON);
        Fixture chain = new Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        Fixture openSlash = new Fixture(h, ExtremeKind.ANNIHILATION_SKELETON);
        Fixture openChain = new Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        ready(h, List.of(slash, chain, openSlash, openChain), () -> {
            for (Fixture f : List.of(slash, chain)) f.wall(3, true);
            slash.cast(BossSkill.ANNIHILATION_SLASH); chain.cast(BossSkill.ZOMBIE_CHAIN);
            slash.finishCast(); chain.finishCast();
            h.assertTrue(slash.hits == 0 && chain.hits == 0, "A full stone divider blocks the actual line skills");
            h.assertTrue(chain.player.getDeltaMovement().lengthSqr() < 1e-6, "An obstructed chain cannot pull the player through the wall");
            openSlash.cast(BossSkill.ANNIHILATION_SLASH); openSlash.finishCast();
            openChain.cast(BossSkill.ZOMBIE_CHAIN); openChain.finishCast();
            h.assertTrue(openSlash.hits == 1 && openChain.largestPlayerImpulse > .01,
                    "The otherwise identical exposed controls are hit and pulled, ruling out disabled skills");
            h.assertTrue(openSlash.player.getHealth() >= 970, "The annihilation skill does not inherit its lethal ordinary attack attribute");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void tideKeepsItsGapAndJumpSafeAndHitsOnlyOncePerPassingWave(GameTestHelper h) {
        Fixture f = new Fixture(h, ExtremeKind.SKELETON_ELITE_LORD);
        Player grounded = f.addPlayer(f.origin.add(0, 0, 6));
        Player jumping = f.addPlayer(f.origin.add(0, 1.1, -6));
        ready(h, List.of(f), () -> {
            f.cast(BossSkill.TYRANT_TIDE); f.finishCast();
            h.assertTrue(f.hitCount(f.player) == 0, "The announced gap directed at the target remains a genuine safe route");
            h.assertTrue(f.hitCount(jumping) == 0, "A player above the shallow wave is safe while jumping");
            h.assertTrue(f.hitCount(grounded) == 3, "Three passing waves cause exactly three hits despite clearing native damage immunity every tick");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void intersectingBoneBladesShareOneDamageBudgetPerVolley(GameTestHelper h) {
        Fixture f = new Fixture(h, ExtremeKind.SKELETON_ELITE_LORD);
        ready(h, List.of(f), () -> {
            f.player.setPos(f.origin.add(1.2, 0, 0));
            f.cast(BossSkill.TYRANT_BLADES); f.finishCast();
            h.assertTrue(f.hits == 1, "All three overlapping blade paths share a single hit per victim rather than stacking at point blank range");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void fourBossesAnswerLowCeilingFootAttacksWithoutChangingBlocks(GameTestHelper h) {
        List<Fixture> fixtures = new ArrayList<>();
        for (ExtremeKind kind : new ExtremeKind[]{ExtremeKind.ZOMBIE_BOSS, ExtremeKind.ANNIHILATION_SKELETON,
                ExtremeKind.SKELETON_ELITE_LORD, ExtremeKind.UNDYING_ZOMBIE_EMPEROR}) fixtures.add(new Fixture(h, kind));
        ready(h, fixtures, () -> {
            for (Fixture f : fixtures) {
                f.player.setPos(f.origin.add(2.4, 0, 0));
                // A full head-height obstruction leaves the same exposed-feet arrangement as the report.
                for (int z = -1; z <= 1; z++) {
                    f.block(1, 1, z, Blocks.STONE); f.block(1, 2, z, Blocks.STONE);
                    f.block(2, 2, z, Blocks.STONE); f.block(3, 2, z, Blocks.STONE);
                }
                long terrain = f.terrainFingerprint();
                for (int tick = 0; tick < 180; tick++) {
                    if (tick % 12 == 0) {
                        f.boss.invulnerableTime = 0;
                        f.boss.hurt(f.boss.damageSources().playerAttack(f.player), 4);
                        f.boss.setHealth(f.boss.getMaxHealth());
                    }
                    f.step();
                }
                h.assertTrue(f.hits > 0, f.kind + " retaliates against an immobile foot-attacking player behind the low obstruction");
                h.assertTrue(terrain == f.terrainFingerprint(), f.kind + " leaves all floor, ceiling and barrier blocks intact");
            }
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void safeLandingRejectsWallsLiquidUnsupportedAirAndASealedArena(GameTestHelper h) {
        Fixture f = new Fixture(h, ExtremeKind.ANNIHILATION_SKELETON);
        ready(h, List.of(f), () -> {
            Vec3 point = f.origin.add(0, .025, 5);
            h.assertTrue(BossGeometry.safeLanding(h.getLevel(), f.boss, point, f.player.position()), "Open supported dry ground is accepted");
            f.block(0, 1, 5, Blocks.STONE);
            h.assertFalse(BossGeometry.safeLanding(h.getLevel(), f.boss, point, f.player.position()), "The entire boss body must fit below the ceiling");
            f.block(0, 1, 5, Blocks.AIR); f.block(0, 0, 5, Blocks.WATER);
            h.assertFalse(BossGeometry.safeLanding(h.getLevel(), f.boss, point, f.player.position()), "A liquid destination is rejected");
            f.block(0, 0, 5, Blocks.AIR); f.block(0, -1, 5, Blocks.AIR);
            h.assertFalse(BossGeometry.safeLanding(h.getLevel(), f.boss, point, f.player.position()), "Unsupported air is rejected");
            // Fill every proposed escape ring while leaving the original boss and target columns open.
            for (int x = -2; x <= 14; x++) for (int z = -8; z <= 8; z++) {
                if (x == 0 && z == 0 || x == 6 && z == 0) continue;
                f.block(x, 0, z, Blocks.STONE); f.block(x, 1, z, Blocks.STONE); f.block(x, 2, z, Blocks.STONE);
            }
            h.assertTrue(BossGeometry.escapeLanding(h.getLevel(), f.boss, f.player.position()).isEmpty(), "A fully blocked escape search has no fallback inside a wall");
            Vec3 before = f.boss.position();
            boolean scheduled = BossAbilities.forceCast(f.boss, f.extreme, BossSkill.ANNIHILATION_BLINK, f.player);
            if (scheduled) f.finishCast();
            h.assertTrue(f.boss.position().distanceToSqr(before) < 1e-6, "A blink with no valid destination is cancelled without moving the boss");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void blinkFindsSupportedSpaceAndCannotOverlapItsTarget(GameTestHelper h) {
        Fixture f = new Fixture(h, ExtremeKind.ANNIHILATION_SKELETON);
        ready(h, List.of(f), () -> {
            Vec3 before = f.boss.position(); f.cast(BossSkill.ANNIHILATION_BLINK); f.finishCast();
            h.assertTrue(f.boss.position().distanceToSqr(before) > .25, "The blink actually moves in an open arena");
            h.assertTrue(h.getLevel().noCollision(f.boss, f.boss.getBoundingBox()), "The completed blink has no body collision");
            h.assertTrue(BossGeometry.horizontalDistanceSquared(f.boss.position(), f.player.position()) >= 9, "The destination retains the announced reaction space around the player");
            h.assertTrue(BossGeometry.ground(h.getLevel(), f.boss, f.boss.position()).isPresent(), "The destination has a local supporting floor");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void activeSkillRejectsASecondCastAndNativeMeleeDamage(GameTestHelper h) {
        List<Fixture> fixtures = new ArrayList<>();
        for (ExtremeKind kind : new ExtremeKind[]{ExtremeKind.ZOMBIE_BOSS, ExtremeKind.ANNIHILATION_SKELETON,
                ExtremeKind.SKELETON_ELITE_LORD, ExtremeKind.UNDYING_ZOMBIE_EMPEROR}) fixtures.add(new Fixture(h, kind));
        ready(h, fixtures, () -> {
            BossSkill[] skills = {BossSkill.ZOMBIE_MARK, BossSkill.ANNIHILATION_MARK, BossSkill.TYRANT_SPIKES, BossSkill.EMPEROR_EDICT};
            for (int i = 0; i < fixtures.size(); i++) {
                Fixture f = fixtures.get(i); f.player.setPos(f.origin.add(1, 0, 0)); f.cast(skills[i]);
                h.assertFalse(BossAbilities.forceCast(f.boss, f.extreme, skills[i], f.player), "An active cast cannot be replaced by a second cast");
                h.assertFalse(f.boss.doHurtTarget(f.player), "Native melee is suspended during the telegraph for " + f.kind);
                h.assertTrue(f.hits == 0 && f.player.getHealth() == 1000, "No hidden normal hit is added to the skill windup");
            }
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void ordinaryArenaDodgingPreservesTheZombieBossChallenge(GameTestHelper h) {
        Fixture f = new Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        ready(h, List.of(f), () -> {
            f.boss.invulnerableTime = 0; f.boss.hurt(f.boss.damageSources().playerAttack(f.player), 20);
            float health = f.boss.getHealth();
            h.assertTrue(health < f.boss.getMaxHealth() && !f.extreme.extremeMemory().contribution.isEmpty(), "A native accepted player hit starts the challenge");
            f.player.setPos(f.origin.add(15, 0, 0));
            for (int tick = 0; tick < 260; tick++) { f.player.setHealth(1000); MobAbilities.tick(f.boss, f.extreme); }
            h.assertTrue(f.boss.getHealth() == health && !f.extreme.extremeMemory().contribution.isEmpty(), "Normal movement within the arena cannot heal the boss or discard contribution after ten seconds");
        });
    }

    @GameTest(template = "gear_empty", timeoutTicks = 240)
    public static void invalidTargetCancelsPendingDamageAndUnloadingNeverResumesAHiddenAttack(GameTestHelper h) {
        Fixture f = new Fixture(h, ExtremeKind.ZOMBIE_BOSS);
        ready(h, List.of(f), () -> {
            f.cast(BossSkill.ZOMBIE_MARK); f.step();
            var serialized = new net.minecraft.nbt.CompoundTag(); BossStates.of(f.extreme).write(serialized);
            var restored = new io.github.ningmeng123.morebossskills.entity.boss.BossCombatState(); restored.read(serialized);
            h.assertTrue(restored.activeSkill() == null, "Reloading a saved windup does not resume an attack without a visible warning");
            f.player.discard();
            for (int tick = 0; tick < 80; tick++) f.step();
            h.assertTrue(BossStates.of(f.extreme).activeSkill() == null && f.hits == 0,
                    "A removed target cancels the scheduled skill instead of damaging its stale position");
        });
    }

    static void ready(GameTestHelper h, List<Fixture> fixtures, Runnable assertions) {
        h.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { fixtures.forEach(Fixture::close); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { fixtures.forEach(Fixture::close); }
        });
        h.startSequence().thenWaitUntil(() -> {
            // FULL block chunks can precede entity-section promotion, especially when a saved
            // test world is reused. Advance the native queue, then admit actors only after both
            // section visibility and the previous entity-file load are complete.
            if (fixtures.stream().anyMatch(f -> !f.chunksReady()))
                for (int task = 0; task < 64 && h.getLevel().getChunkSource().pollTask(); task++) {}
            for (Fixture f : fixtures) h.assertTrue(f.chunksReady(), "Arena entity sections finish loading before admission: " + f.readiness());
        }).thenExecute(() -> fixtures.forEach(Fixture::admit)).thenWaitUntil(() -> {
            for (Fixture f : fixtures) h.assertTrue(h.getLevel().getEntitiesOfClass(Player.class, f.boss.getBoundingBox().inflate(20)).containsAll(f.participants),
                    "Every real player is published in the level entity index: " + f.readiness());
        }).thenExecute(assertions).thenSucceed();
    }

    static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final int key = IDS.getAndIncrement();
        final Vec3 origin;
        final ExtremeKind kind;
        final Mob boss;
        final ExtremeMob extreme;
        final Player player;
        final List<Player> participants = new ArrayList<>();
        final List<Mob> followers = new ArrayList<>();
        final Map<UUID, Integer> damageCounts = new HashMap<>();
        final List<ChunkPos> chunks = new ArrayList<>();
        final List<ChunkPos> heldChunks = new ArrayList<>();
        final Consumer<LivingIncomingDamageEvent> damage;
        int hits;
        double largestPlayerImpulse;
        boolean admitted, closed;
        Fixture(GameTestHelper h, ExtremeKind kind) {
            helper = h; this.kind = kind;
            origin = new Vec3(1_760_000.5 + key * 96, h.absolutePos(BlockPos.ZERO).getY() + 10, 1_760_000.5);
            BlockPos base = BlockPos.containing(origin);
            ChunkPos low = new ChunkPos(base.offset(-18, 0, -18)), high = new ChunkPos(base.offset(18, 0, 18));
            for (int x = low.x; x <= high.x; x++) for (int z = low.z; z <= high.z; z++) {
                ChunkPos chunk = new ChunkPos(x, z); chunks.add(chunk);
                if (!h.getLevel().getForcedChunks().contains(chunk.toLong())) {
                    h.getLevel().setChunkForced(x, z, true); heldChunks.add(chunk);
                }
                h.getLevel().getChunk(x, z);
            }
            for (int x = -18; x <= 18; x++) for (int z = -18; z <= 18; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y <= 4; y++) block(x, y, z, Blocks.AIR);
            }
            boss = ExtremeMobs.type(kind).create(h.getLevel()); h.assertTrue(boss != null, "The registered boss creates"); extreme = (ExtremeMob) boss;
            boss.moveTo(origin); boss.setNoAi(true); boss.setNoGravity(true); boss.setPersistenceRequired();
            extreme.extremeMemory().entourage = true; MobAbilities.initialize(boss, extreme);
            EventHooks.finalizeMobSpawn(boss, h.getLevel(), h.getLevel().getCurrentDifficultyAt(base), MobSpawnType.SPAWN_EGG, null);
            player = addPlayer(origin.add(6, 0, 0)); boss.setTarget(player);
            damage = event -> {
                if (participants.contains(event.getEntity()) && event.getSource().getEntity() == boss && event.getAmount() > 0) {
                    hits++; damageCounts.merge(event.getEntity().getUUID(), 1, Integer::sum);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, LivingIncomingDamageEvent.class, damage);
        }
        boolean chunksReady() {
            return chunks.stream().allMatch(chunk -> helper.getLevel().areEntitiesLoaded(chunk.toLong())
                    && helper.getLevel().isPositionEntityTicking(new BlockPos(chunk.getMinBlockX(), (int) origin.y, chunk.getMinBlockZ())));
        }
        void admit() {
            helper.assertTrue(!admitted && chunksReady(), "Native entity sections are ready for the single fixture admission");
            helper.assertTrue(helper.getLevel().addFreshEntity(boss), "Explicit test boss passes real level admission");
            for (Player participant : participants)
                helper.assertTrue(helper.getLevel().addFreshEntity(participant), "Survival target enters the real level after entity sections become visible");
            for (Mob follower : followers)
                helper.assertTrue(helper.getLevel().addFreshEntity(follower), "Owned command follower enters the ready level");
            admitted = true;
        }
        String readiness() {
            long loaded = chunks.stream().filter(chunk -> helper.getLevel().areEntitiesLoaded(chunk.toLong())).count();
            long ticking = chunks.stream().filter(chunk -> helper.getLevel().isPositionEntityTicking(
                    new BlockPos(chunk.getMinBlockX(), (int) origin.y, chunk.getMinBlockZ()))).count();
            String actors = participants.stream().map(p -> p.getUUID() + "@" + p.position() + ":alive=" + p.isAlive()
                    + ",removed=" + p.isRemoved()).collect(java.util.stream.Collectors.joining(";"));
            return kind + " arena=" + key + " admitted=" + admitted + " chunks=" + chunks.size()
                    + " loaded=" + loaded + " ticking=" + ticking + " players=" + actors;
        }
        void cast(BossSkill skill) { helper.assertTrue(BossAbilities.forceCast(boss, extreme, skill, player), "The real skill starts: " + skill); }
        void step() {
            // Clearing native immunity makes accidental repeated skill hits observable rather than masked.
            for (Player participant : participants) participant.invulnerableTime = 0;
            BossAbilities.tick(boss, extreme);
            largestPlayerImpulse = Math.max(largestPlayerImpulse, player.getDeltaMovement().horizontalDistanceSqr());
        }
        void addFollower() {
            var type = kind == ExtremeKind.SKELETON_ELITE_LORD ? net.minecraft.world.entity.EntityType.SKELETON : net.minecraft.world.entity.EntityType.ZOMBIE;
            Mob follower = type.create(helper.getLevel()); helper.assertTrue(follower != null, "Command skill follower creates");
            follower.moveTo(origin.add(1, 0, 4)); follower.setNoAi(true); follower.setNoGravity(true);
            follower.getPersistentData().putUUID(SpawnPopulation.SUMMONER, boss.getUUID());
            EventHooks.finalizeMobSpawn(follower, helper.getLevel(), helper.getLevel().getCurrentDifficultyAt(follower.blockPosition()), MobSpawnType.SPAWN_EGG, null);
            helper.assertFalse(admitted, "Command followers are prepared before native admission"); followers.add(follower);
        }
        Player addPlayer(Vec3 position) {
            Player created = helper.makeMockPlayer(GameType.SURVIVAL); created.moveTo(position); created.setNoGravity(true); created.setOnGround(true);
            created.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); created.setHealth(1000);
            helper.assertFalse(admitted, "Players are prepared before native admission"); participants.add(created); return created;
        }
        int hitCount(Player participant) { return damageCounts.getOrDefault(participant.getUUID(), 0); }
        void finishCast() {
            int remaining = 700;
            while (BossStates.of(extreme).activeSkill() != null && remaining-- > 0) step();
            helper.assertTrue(remaining > 0, "The active skill has a bounded lifecycle");
        }
        void wall(int x, boolean solid) { for (int y = 0; y <= 4; y++) for (int z = -4; z <= 4; z++) block(x, y, z, solid ? Blocks.STONE : Blocks.AIR); }
        void block(int x, int y, int z, net.minecraft.world.level.block.Block block) { helper.getLevel().setBlock(BlockPos.containing(origin).offset(x, y, z), block.defaultBlockState(), 2); }
        long terrainFingerprint() {
            long hash = 1;
            for (int x = -18; x <= 18; x++) for (int z = -18; z <= 18; z++) for (int y = -1; y <= 4; y++)
                hash = hash * 31 + helper.getLevel().getBlockState(BlockPos.containing(origin).offset(x, y, z)).hashCode();
            return hash;
        }
        @Override public void close() {
            if (closed) return; closed = true; NeoForge.EVENT_BUS.unregister(damage); boss.discard(); participants.forEach(Player::discard); followers.forEach(Mob::discard);
            for (ChunkPos chunk : heldChunks) helper.getLevel().setChunkForced(chunk.x, chunk.z, false);
        }
    }
}
