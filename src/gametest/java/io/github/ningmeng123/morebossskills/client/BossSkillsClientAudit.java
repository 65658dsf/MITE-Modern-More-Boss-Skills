package io.github.ningmeng123.morebossskills.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.github.ningmeng123.morebossskills.MoreBossSkills;
import io.github.ningmeng123.morebossskills.client.boss.BossSkillVisuals;
import io.github.ningmeng123.morebossskills.client.boss.BossVisualConfig;
import io.github.ningmeng123.modernite.entity.ExtremeKind;
import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.modernite.entity.ExtremeMobs;
import io.github.ningmeng123.modernite.entity.MobAbilities;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkill;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPreview;
import io.github.ningmeng123.modernite.world.ExtremeWorldState;
import io.github.ningmeng123.mite.worldgen.BloodMoonInvasionData;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Production VFX replay and native framebuffer proof in a disposable world. @author NingMeng123 */
@EventBusSubscriber(modid = MoreBossSkills.MOD_ID, value = Dist.CLIENT)
public final class BossSkillsClientAudit {
    private enum Phase { TITLE, LOADING, PREPARING, SETTLING, SPAWNING, WAIT_OWNER, BASELINE, EXECUTING_COMMAND, PLAYBACK, STOPPING_COMMAND, DONE }
    private record Case(String name, ExtremeKind kind, BossSkillPayload.Effect effect, int theme,
                        float radius, float width, float gap, boolean minimal, boolean lowCeiling) {
        Case(String name, ExtremeKind kind, BossSkillPayload.Effect effect, int theme,
             float radius, float width, float gap, boolean minimal) {
            this(name, kind, effect, theme, radius, width, gap, minimal, false);
        }
    }
    private static final Case[] BASE_CASES = {
            new Case("zombie-shockwave", ExtremeKind.ZOMBIE_BOSS, BossSkillPayload.Effect.RING, 0, 7, 1, 0, false),
            new Case("annihilation-singularity", ExtremeKind.ANNIHILATION_SKELETON, BossSkillPayload.Effect.BLACK_HOLE, 1, 8, 1, 0, false),
            new Case("tyrant-tide-safe-gap", ExtremeKind.SKELETON_ELITE_LORD, BossSkillPayload.Effect.RING, 2, 12, 1, 60, false),
            new Case("emperor-black-sun", ExtremeKind.UNDYING_ZOMBIE_EMPEROR, BossSkillPayload.Effect.SUN, 3, 12, 1, 0, false),
            new Case("minimal-particles-ground-warning", ExtremeKind.ZOMBIE_BOSS, BossSkillPayload.Effect.MARK, 0, 3, 1, 0, true)
    };
    private static final Case[] EXTENDED_CASES = {
            new Case("extended-tyrant-bone-spikes", ExtremeKind.SKELETON_ELITE_LORD, BossSkillPayload.Effect.MARK, 2, 2.5F, 0, 0, false),
            new Case("extended-emperor-fan", ExtremeKind.UNDYING_ZOMBIE_EMPEROR, BossSkillPayload.Effect.FAN, 3, 9, 160, 0, false),
            new Case("extended-annihilation-beam", ExtremeKind.ANNIHILATION_SKELETON, BossSkillPayload.Effect.BEAM, 1, 0, 2, 0, false),
            new Case("extended-zombie-chain", ExtremeKind.ZOMBIE_BOSS, BossSkillPayload.Effect.CHAIN, 0, 0, 1.2F, 0, false),
            new Case("extended-tyrant-blade", ExtremeKind.SKELETON_ELITE_LORD, BossSkillPayload.Effect.BLADE, 2, 0, 1.4F, 0, false),
            new Case("extended-annihilation-blink", ExtremeKind.ANNIHILATION_SKELETON, BossSkillPayload.Effect.BLINK, 1, 1, 0, 0, false),
            new Case("extended-emperor-command", ExtremeKind.UNDYING_ZOMBIE_EMPEROR, BossSkillPayload.Effect.COMMAND, 3, 16, 0, 0, false),
            new Case("extended-low-ceiling-singularity", ExtremeKind.ANNIHILATION_SKELETON, BossSkillPayload.Effect.BLACK_HOLE, 1, 7, 3, 0, false, true)
    };
    private record Shot(String name, int tick, boolean warning) {}
    private record CommandCase(String skill, int expectedPackets, int endTick, int stopTick, Shot[] shots) {}
    private record CommandResult(long tick, int result) {}
    private static final boolean PRESENTATION_MODE = Boolean.getBoolean("modern_ite.bossPresentationAudit");
    private static final boolean COMMAND_MODE = Boolean.getBoolean("modern_ite.bossSkillsCommandAudit") || PRESENTATION_MODE;
    private static final CommandCase[] ORIGINAL_COMMAND_SCRIPTS = {
            new CommandCase("zombie_stomp", 1, 40, -1, new Shot[]{
                    new Shot("warning", 10, true), new Shot("active", 30, false)}),
            new CommandCase("zombie_triple", 3, 120, -1, new Shot[]{
                    new Shot("wave-1-warning", 10, true), new Shot("wave-1-active", 30, false),
                    new Shot("wave-2-warning", 55, true), new Shot("wave-2-active", 70, false),
                    new Shot("wave-3-warning", 95, true), new Shot("wave-3-active", 110, false)}),
            new CommandCase("tyrant_blades", 3, 54, -1, new Shot[]{
                    new Shot("three-blades-warning", 12, true), new Shot("three-blades-active", 42, false)}),
            new CommandCase("annihilation_singularity", 2, 102, -1, new Shot[]{
                    new Shot("singularity-warning", 15, true), new Shot("singularity-active", 50, false),
                    new Shot("collapse-warning", 80, true), new Shot("collapse-active", 95, false)}),
            new CommandCase("emperor_sun", 3, 148, -1, new Shot[]{
                    new Shot("sun-warning", 20, true), new Shot("sun-active", 45, false),
                    new Shot("wave-1-warning", 55, true), new Shot("wave-1-active", 80, false),
                    new Shot("wave-2-warning", 105, true), new Shot("wave-2-active", 132, false)}),
            new CommandCase("annihilation_blink", 2, 68, -1, new Shot[]{
                    new Shot("blink-warning", 10, true), new Shot("blink-active", 30, false),
                    new Shot("slash-warning", 50, true), new Shot("slash-active", 64, false)}),
            new CommandCase("emperor_sun", 3, 148, 46, new Shot[]{
                    new Shot("before-stop-warning", 20, true), new Shot("before-stop-active", 45, false)})
    };
    private static Shot[] presentationShots(int windup, int end) {
        return new Shot[]{new Shot("warning-pose", windup / 2, true),
                new Shot("release-pose", windup + 2, false), new Shot("restored-pose", end + 18, false)};
    }
    private static final CommandCase[] PRESENTATION_SCRIPTS = {
            new CommandCase("zombie_stomp", 1, 40, -1, presentationShots(20, 40)),
            new CommandCase("zombie_chain", 1, 26, -1, presentationShots(20, 26)),
            new CommandCase("annihilation_slash", 1, 34, -1, presentationShots(24, 34)),
            new CommandCase("annihilation_singularity", 2, 102, -1, presentationShots(30, 102)),
            new CommandCase("tyrant_command", 1, 44, -1, presentationShots(24, 44)),
            new CommandCase("tyrant_blades", 3, 54, -1, presentationShots(24, 54)),
            new CommandCase("emperor_sun", 3, 148, -1, presentationShots(40, 148)),
            new CommandCase("emperor_sun", 3, 148, 46,
                    new Shot[]{new Shot("preview-warning", 20, true), new Shot("preview-active", 45, false)})
    };
    private static final CommandCase[] COMMAND_SCRIPTS = PRESENTATION_MODE ? PRESENTATION_SCRIPTS : ORIGINAL_COMMAND_SCRIPTS;
    private static final Case[] COMMAND_CASES = {
            new Case("command-zombie-stomp", ExtremeKind.ZOMBIE_BOSS, BossSkillPayload.Effect.RING, 0, 7, .8F, 0, false),
            new Case("command-zombie-triple", ExtremeKind.ZOMBIE_BOSS, BossSkillPayload.Effect.RING, 0, 10, .8F, 90, false),
            new Case("command-tyrant-three-blades", ExtremeKind.SKELETON_ELITE_LORD, BossSkillPayload.Effect.BLADE, 2, 0, 1.4F, 0, false),
            new Case("command-annihilation-singularity", ExtremeKind.ANNIHILATION_SKELETON, BossSkillPayload.Effect.BLACK_HOLE, 1, 7, 3, 0, false),
            new Case("command-emperor-sun-two-waves", ExtremeKind.UNDYING_ZOMBIE_EMPEROR, BossSkillPayload.Effect.SUN, 3, 12, 0, 0, false),
            new Case("command-annihilation-blink-slash", ExtremeKind.ANNIHILATION_SKELETON, BossSkillPayload.Effect.BLINK, 1, 1, 0, 0, false),
            new Case("command-stop-pending-sun-waves", ExtremeKind.UNDYING_ZOMBIE_EMPEROR, BossSkillPayload.Effect.SUN, 3, 12, 0, 0, false)
    };
    private static final Case[] PRESENTATION_CASES = {
            new Case("pose-zombie-stomp", ExtremeKind.ZOMBIE_BOSS, BossSkillPayload.Effect.RING, 0, 7, .8F, 0, false),
            new Case("pose-zombie-chain", ExtremeKind.ZOMBIE_BOSS, BossSkillPayload.Effect.CHAIN, 0, 0, 1.2F, 0, false),
            new Case("pose-annihilation-slash", ExtremeKind.ANNIHILATION_SKELETON, BossSkillPayload.Effect.BEAM, 1, 0, 2, 0, false),
            new Case("pose-annihilation-singularity", ExtremeKind.ANNIHILATION_SKELETON, BossSkillPayload.Effect.BLACK_HOLE, 1, 7, 3, 0, false),
            new Case("pose-tyrant-command", ExtremeKind.SKELETON_ELITE_LORD, BossSkillPayload.Effect.COMMAND, 2, 16, 0, 0, false),
            new Case("pose-tyrant-three-blades", ExtremeKind.SKELETON_ELITE_LORD, BossSkillPayload.Effect.BLADE, 2, 0, 1.4F, 0, false),
            new Case("pose-emperor-sun", ExtremeKind.UNDYING_ZOMBIE_EMPEROR, BossSkillPayload.Effect.SUN, 3, 12, 0, 0, false),
            new Case("sound-preview-stop-future-waves", ExtremeKind.UNDYING_ZOMBIE_EMPEROR, BossSkillPayload.Effect.SUN, 3, 12, 0, 0, false)
    };
    private static final Case[] CASES = PRESENTATION_MODE ? PRESENTATION_CASES : COMMAND_MODE ? COMMAND_CASES
            : Boolean.getBoolean("modern_ite.bossSkillsAuditExtended") ? EXTENDED_CASES : BASE_CASES;
    private static final Vec3 CENTER = new Vec3(300.5, 80.025, .5);
    private static final int WINDUP = 60, DURATION = 120;
    private static final JsonObject REPORT = new JsonObject();
    private static Phase phase = Phase.TITLE;
    private static String worldName;
    private static int elapsed, settling, caseIndex, ownerId;
    private static long started;
    private static CompletableFuture<Integer> pending;
    private static volatile JsonObject serverSnapshot;
    private static JsonObject current;
    private static NativeImage baseline;
    private static boolean warningCaptured, activeCaptured;
    private static final String PACKET_OBSERVER = "modern_ite_boss_command_audit";
    private static final ConcurrentLinkedQueue<BossSkillPayload> PACKET_INBOX = new ConcurrentLinkedQueue<>();
    private static final List<BossSkillPayload> receivedPackets = new ArrayList<>();
    private static Channel observedChannel;
    private static CompletableFuture<CommandResult> commandPending;
    private static CompletableFuture<Void> observerReady;
    private static int nextCommandShot;
    private static long stopExecutedTick = Long.MIN_VALUE;
    private static boolean stopCaptured;
    private static volatile int controlId = -1;
    private static boolean playerPreview() { return COMMAND_MODE && (!PRESENTATION_MODE || caseIndex == PRESENTATION_CASES.length - 1); }
    private BossSkillsClientAudit() {}

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("modern_ite.bossSkillsAudit") || phase == Phase.DONE) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (phase == Phase.TITLE) {
                require(mc.level == null && mc.getConnection() == null && mc.getSingleplayerServer() == null,
                        "Refused an existing world or connection");
                if (mc.getOverlay() != null) return;
                if (mc.screen instanceof AccessibilityOnboardingScreen onboarding) { onboarding.onClose(); return; }
                if (!(mc.screen instanceof TitleScreen)) return;
                Path directory = mc.gameDirectory.toPath().toAbsolutePath().normalize();
                require(directory.toString().replace('\\', '/').contains("/build/verification/"),
                        "Audit requires an isolated directory under build/verification");
                REPORT.addProperty("author", "NingMeng123"); REPORT.addProperty("timestamp", Instant.now().toString());
                REPORT.addProperty("scope", PRESENTATION_MODE
                        ? "Production skill timelines delivered through real clientbound packets with registered boss owners; actual renderer poses and SoundEngine sources observed. Final preview/stop case uses registered player commands."
                        : COMMAND_MODE
                        ? "Permission-2 ServerPlayer commands through the registered Brigadier dispatcher and real clientbound payload delivery; no direct renderer injection"
                        : "Production BossSkillPayload renderer replay; gameplay damage is separately covered by BossSkillsGameTests");
                REPORT.addProperty("extended_geometry_suite", Boolean.getBoolean("modern_ite.bossSkillsAuditExtended"));
                REPORT.addProperty("command_network_suite", COMMAND_MODE);
                REPORT.addProperty("presentation_suite", PRESENTATION_MODE);
                REPORT.addProperty("manual_pixel_review_required", true); REPORT.addProperty("game_directory", directory.toString());
                REPORT.add("samples", new JsonArray()); REPORT.add("failures", new JsonArray());
                REPORT.addProperty("status", "running");
                mc.options.pauseOnLostFocus = false; mc.options.hideGui = true;
                mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.renderDistance().set(6);
                mc.options.simulationDistance().set(5); mc.options.fov().set(70); mc.options.cloudStatus().set(CloudStatus.OFF);
                if (PRESENTATION_MODE) {
                    mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(1D);
                    mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.HOSTILE).set(1D);
                }
                worldName = "boss-skills-audit-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
                REPORT.addProperty("disposable_world", worldName); phase = Phase.LOADING;
                checkpoint(mc);
                mc.createWorldOpenFlows().createFreshLevel(worldName,
                        new LevelSettings(worldName, GameType.SPECTATOR, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(20261115L, false, false),
                        access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                return;
            }
            elapsed++;
            if (elapsed % 100 == 0) checkpoint(mc);
            require(elapsed < 3000, "Timed out creating or observing boss visuals");
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || mc.getOverlay() != null) return;
            require(owned(mc), "Only the newly created disposable world may be changed");
            if (mc.screen != null) {
                String screen = mc.screen.getClass().getSimpleName();
                // Only dismiss in-world notices/menus in the disposable world owned by this audit.
                // Native terrain-loading screens still finish through their ordinary lifecycle.
                boolean loading = screen.contains("ReceivingLevel") || screen.contains("LevelLoading")
                        || screen.contains("ProgressScreen") || screen.contains("LoadingScreen");
                if (!loading) {
                    REPORT.addProperty("dismissed_owned_world_screen", mc.screen.getClass().getName());
                    mc.setScreen(null); checkpoint(mc);
                }
            }
            mc.options.hideGui = true; mc.getToasts().clear();
            float yaw = PRESENTATION_MODE ? 145 : CASES[caseIndex].lowCeiling() ? 90 : 135;
            float pitch = PRESENTATION_MODE ? 7 : CASES[caseIndex].lowCeiling() ? 10 : 23;
            mc.player.setYRot(yaw); mc.player.yRotO = yaw; mc.player.setXRot(pitch); mc.player.xRotO = pitch;
            if (phase == Phase.LOADING && mc.screen == null) beginCase(mc);
            else if (phase == Phase.PREPARING && pending.isDone()) {
                pending.join(); settling = 0; phase = Phase.SETTLING;
            } else if (phase == Phase.SETTLING && ++settling >= 45 && mc.level.hasChunkAt(BlockPos.containing(CENTER))
                    && mc.player.distanceToSqr(CENTER) < 48 * 48) {
                spawnOwner(mc); phase = Phase.SPAWNING;
            } else if (phase == Phase.SPAWNING && pending.isDone()) {
                ownerId = pending.join(); settling = 0; phase = Phase.WAIT_OWNER;
            } else if (phase == Phase.WAIT_OWNER && ++settling >= 10 && mc.level.getEntity(ownerId) != null) {
                phase = Phase.BASELINE;
            } else if (phase == Phase.EXECUTING_COMMAND && commandPending.isDone()) {
                CommandResult result = commandPending.join(); started = result.tick();
                require(result.result() == COMMAND_SCRIPTS[caseIndex].expectedPackets(), "Preview command returns its complete scheduled effect count");
                current.addProperty("command_result", result.result()); current.addProperty("server_start_tick", started);
                phase = Phase.PLAYBACK;
            } else if (phase == Phase.STOPPING_COMMAND && commandPending.isDone()) {
                CommandResult result = commandPending.join(); require(result.result() == 1, "Stop command succeeds");
                stopExecutedTick = result.tick(); current.addProperty("stop_server_tick", stopExecutedTick);
                current.addProperty("stop_command_result", result.result()); phase = Phase.PLAYBACK;
            } else if (phase == Phase.PLAYBACK && COMMAND_MODE) {
                drainCommandPackets(mc);
                if (PRESENTATION_MODE) BossPresentationProbe.tick(mc, ownerId, controlId, current, playerPreview());
                CommandCase script = COMMAND_SCRIPTS[caseIndex]; long age = mc.level.getGameTime() - started;
                if (script.stopTick() >= 0 && stopExecutedTick == Long.MIN_VALUE && age >= script.stopTick()
                        && nextCommandShot == script.shots().length) {
                    executeCommand(mc, "mite boss stop"); phase = Phase.STOPPING_COMMAND;
                } else if (age > script.endTick() + 25) {
                    verifyCommandComplete(mc, script);
                    if (++caseIndex == CASES.length) finish(mc); else beginCase(mc);
                }
            } else if (phase == Phase.PLAYBACK && mc.level.getGameTime() - started > WINDUP + DURATION + 25) {
                require(warningCaptured && activeCaptured, "Both windup and active frames were captured");
                require(BossSkillVisuals.activeEffects() == 0, "Completed visuals expire without leaking instances");
                current.addProperty("lifecycle_expired", true);
                if (++caseIndex == CASES.length) finish(mc); else beginCase(mc);
            }
        } catch (Throwable failure) { fail(mc, failure); }
    }

    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean("modern_ite.bossSkillsAudit") || phase == Phase.DONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.getOverlay() != null) return;
        try {
            if (phase == Phase.BASELINE) {
                if (baseline != null) baseline.close();
                baseline = Screenshot.takeScreenshot(mc.getMainRenderTarget());
                Path folder = output().getParent().resolve(CASES[caseIndex].name()); Files.createDirectories(folder);
                baseline.writeToFile(folder.resolve("baseline.png"));
                var owner = mc.level.getEntity(ownerId); require(owner != null, "Caster is tracked on the client");
                Case sample = CASES[caseIndex]; started = mc.level.getGameTime();
                if (COMMAND_MODE) {
                    if (playerPreview()) require(ownerId == mc.player.getId() && owner.getUUID().equals(mc.player.getUUID()),
                            "The command effect owner is the executing player");
                    else require(owner instanceof ExtremeMob, "Presentation packets animate the real registered boss owner");
                    require(observerReady != null && observerReady.isDone(), "Client packet observation is installed before the command");
                    observerReady.join();
                    if (PRESENTATION_MODE) BossPresentationProbe.baseline(mc, ownerId, controlId, current, playerPreview());
                    if (!playerPreview()) {
                        current.addProperty("owner_is_registered_boss", true);
                        executeBossTimeline(mc); phase = Phase.EXECUTING_COMMAND; return;
                    }
                    String command = "mite boss preview " + COMMAND_SCRIPTS[caseIndex].skill()
                            + " at " + CENTER.x + " " + CENTER.y + " " + CENTER.z;
                    current.addProperty("command", command); current.addProperty("owner_is_executor", true);
                    executeCommand(mc, command); phase = Phase.EXECUTING_COMMAND;
                    return;
                }
                Vec3 from = effectFrom(sample), to = effectTo(sample);
                current.addProperty("from", from.toString()); current.addProperty("to", to.toString());
                BossSkillVisuals.receive(new BossSkillPayload(mc.level.dimension().location(), ownerId, owner.getUUID(),
                        9000L + caseIndex, sample.effect(), sample.theme(), from, to,
                        started, WINDUP, DURATION, sample.radius(), sample.width(), sample.gap(), false));
                phase = Phase.PLAYBACK;
            } else if (phase == Phase.PLAYBACK) {
                long age = mc.level.getGameTime() - started;
                if (COMMAND_MODE) {
                    drainCommandPackets(mc);
                    CommandCase script = COMMAND_SCRIPTS[caseIndex];
                    if (nextCommandShot < script.shots().length) {
                        Shot shot = script.shots()[nextCommandShot];
                        if (age >= shot.tick()) {
                            capture(mc, shot.name(), age, shot.warning()); nextCommandShot++;
                        }
                    }
                    if (stopExecutedTick != Long.MIN_VALUE && mc.level.getGameTime() >= stopExecutedTick + 5) {
                        require(BossSkillVisuals.activeEffects() == 0, "Stop removes current and future visual instances");
                        require(BossSkillVisuals.lastFrameVertices() == 0, "Stopped future stages must never reappear on later frames");
                        if (!stopCaptured) {
                            try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                                Path screenshot = output().getParent().resolve(CASES[caseIndex].name()).resolve("after-stop.png");
                                pixels.writeToFile(screenshot); current.addProperty("after_stop_screenshot", screenshot.toString());
                            }
                            stopCaptured = true;
                        }
                    }
                    return;
                }
                if (!warningCaptured && age >= 20) { capture(mc, "warning", age, true); warningCaptured = true; }
                else if (!activeCaptured && age >= WINDUP + Math.round(DURATION * .6)) { capture(mc, "active", age, false); activeCaptured = true; }
            }
        } catch (Throwable failure) { fail(mc, failure); }
    }

    private static void beginCase(Minecraft mc) {
        BossSkillVisuals.clear(); warningCaptured = activeCaptured = false; ownerId = -1;
        if (COMMAND_MODE) {
            PACKET_INBOX.clear(); receivedPackets.clear(); nextCommandShot = 0;
            stopExecutedTick = Long.MIN_VALUE; stopCaptured = false;
            installPacketObserver(mc);
        }
        Case sample = CASES[caseIndex]; mc.options.particles().set(sample.minimal() ? ParticleStatus.MINIMAL : ParticleStatus.ALL);
        BossVisualConfig.QUALITY.set(sample.minimal() ? 0 : 2); BossVisualConfig.SCREEN_SHAKE.set(false);
        current = new JsonObject(); current.addProperty("name", sample.name()); current.addProperty("particle_setting", sample.minimal() ? "MINIMAL" : "ALL");
        current.addProperty("effect", sample.effect().name()); current.addProperty("radius", sample.radius());
        current.addProperty("width", sample.width()); current.addProperty("low_ceiling", sample.lowCeiling());
        if (COMMAND_MODE) {
            current.add("received_packets", new JsonArray()); current.addProperty("permission_level", 2);
            current.addProperty("complete_timeline_ticks", COMMAND_SCRIPTS[caseIndex].endTick());
        }
        current.add("frames", new JsonArray()); REPORT.getAsJsonArray("samples").add(current);
        if (PRESENTATION_MODE) BossPresentationProbe.begin(current);
        UUID playerId = mc.player.getUUID(); pending = new CompletableFuture<>(); var server = mc.getSingleplayerServer();
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId); require(player != null, "Server player exists");
                var level = player.serverLevel();
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.getGameRules().getRule(GameRules.RULE_SPECTATORSGENERATECHUNKS).set(true, server);
                level.setDayTime((257L - 1) * 24_000L + 6000);
                BloodMoonInvasionData.get(level).restoreProgress(257); ExtremeWorldState.observe(level);
                level.setWeatherParameters(6000, 0, false, false);
                var arenaChunk = new net.minecraft.world.level.ChunkPos(BlockPos.containing(CENTER));
                for (int x = arenaChunk.x - 1; x <= arenaChunk.x + 1; x++) for (int z = arenaChunk.z - 1; z <= arenaChunk.z + 1; z++)
                    level.setChunkForced(x, z, true);
                for (var entity : level.getAllEntities()) if (entity instanceof ExtremeMob
                        || PRESENTATION_MODE && entity.getTags().contains("boss_presentation_control")) entity.discard();
                if (caseIndex == 0) for (int x = -22; x <= 22; x++) for (int z = -22; z <= 22; z++)
                    level.setBlock(BlockPos.containing(CENTER).offset(x, -1, z), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                    level.setBlock(BlockPos.containing(CENTER).offset(x, 2, z),
                            (sample.lowCeiling() ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
                player.setGameMode(GameType.SPECTATOR); player.setNoGravity(true); player.setInvulnerable(true);
                if (PRESENTATION_MODE) player.connection.teleport(CENTER.x + 5, CENTER.y + 1, CENTER.z + 7, 145, 7);
                else if (sample.lowCeiling()) player.connection.teleport(CENTER.x + 6, CENTER.y, CENTER.z, 90, 10);
                else player.connection.teleport(CENTER.x + 18, CENTER.y + 10, CENTER.z + 18, 135, 23);
                require(!ExtremeWorldState.graceActive(level), "The disposable visual world's normal spawn grace period has ended");
                pending.complete(-1);
            } catch (Throwable failure) { pending.completeExceptionally(failure); }
        }); phase = Phase.PREPARING; checkpoint(mc);
    }

    private static void spawnOwner(Minecraft mc) {
        Case sample = CASES[caseIndex]; UUID playerId = mc.player.getUUID(); pending = new CompletableFuture<>();
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId); require(player != null, "Server player exists after chunk settling");
                if (playerPreview()) { pending.complete(player.getId()); return; }
                var level = player.serverLevel();
                Mob boss = ExtremeMobs.type(sample.kind()).create(level); require(boss != null, "Registered boss creates");
                boss.moveTo(sample.lowCeiling() ? CENTER.add(0, -.025, 0) : CENTER);
                boss.setNoAi(true); boss.setNoGravity(true); boss.setPersistenceRequired();
                ((ExtremeMob) boss).extremeMemory().entourage = true;
                MobAbilities.initialize(boss, (ExtremeMob) boss);
                if (PRESENTATION_MODE) {
                    boss.setYRot(-35); boss.yRotO = -35; boss.yBodyRot = boss.yBodyRotO = -35; boss.yHeadRot = boss.yHeadRotO = -35;
                    boss.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
                    boss.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_LEGGINGS));
                    boss.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
                    boss.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, net.minecraft.world.item.ItemStack.EMPTY);
                    Mob ordinary = (sample.theme() == 1 || sample.theme() == 2 ? net.minecraft.world.entity.EntityType.SKELETON
                            : net.minecraft.world.entity.EntityType.ZOMBIE).create(level);
                    require(ordinary != null, "Ordinary adjacent control creates");
                    ordinary.moveTo(CENTER.add(-2.5, 0, 0)); ordinary.setNoAi(true); ordinary.setNoGravity(true); ordinary.setPersistenceRequired();
                    ordinary.setYRot(-35); ordinary.yRotO = -35; ordinary.yBodyRot = ordinary.yBodyRotO = -35; ordinary.yHeadRot = ordinary.yHeadRotO = -35;
                    ordinary.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
                    ordinary.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_LEGGINGS));
                    ordinary.addTag("boss_presentation_control");
                    net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(ordinary, level, level.getCurrentDifficultyAt(ordinary.blockPosition()),
                            net.minecraft.world.entity.MobSpawnType.SPAWN_EGG, null);
                    equipPresentationActor(ordinary);
                    require(level.addFreshEntity(ordinary), "Ordinary control enters the integrated server level"); controlId = ordinary.getId();
                }
                // Explicit egg admission is limited to this newly-created disposable audit world.
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(boss, level, level.getCurrentDifficultyAt(boss.blockPosition()),
                        net.minecraft.world.entity.MobSpawnType.SPAWN_EGG, null);
                if (PRESENTATION_MODE) equipPresentationActor(boss);
                require(level.addFreshEntity(boss), "Caster enters the integrated server level"); pending.complete(boss.getId());
            } catch (Throwable failure) { pending.completeExceptionally(failure); }
        });
    }

    private static void equipPresentationActor(Mob actor) {
        actor.setInvulnerable(true); actor.setSilent(true);
        actor.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
        actor.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
        actor.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_LEGGINGS));
        actor.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
        actor.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, net.minecraft.world.item.ItemStack.EMPTY);
    }

    private static void installPacketObserver(Minecraft mc) {
        require(mc.getConnection() != null, "A real integrated-server client connection exists");
        Channel channel = mc.getConnection().getConnection().channel();
        if (observedChannel == channel && observerReady != null) return;
        observedChannel = channel; observerReady = new CompletableFuture<>();
        CompletableFuture<Void> ready = observerReady;
        channel.eventLoop().execute(() -> {
            try {
                require(channel.pipeline().get(PACKET_OBSERVER) == null, "No stale audit packet observer is installed");
                channel.pipeline().addBefore("packet_handler", PACKET_OBSERVER, new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                        if (message instanceof ClientboundCustomPayloadPacket packet && packet.payload() instanceof BossSkillPayload payload)
                            PACKET_INBOX.add(payload);
                        super.channelRead(context, message);
                    }
                });
                ready.complete(null);
            } catch (Throwable failure) { ready.completeExceptionally(failure); }
        });
    }

    private static void executeCommand(Minecraft mc, String command) {
        UUID playerId = mc.player.getUUID(); var server = mc.getSingleplayerServer();
        commandPending = new CompletableFuture<>(); CompletableFuture<CommandResult> result = commandPending;
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                require(player != null, "The preview command has an online ServerPlayer source");
                long tick = player.serverLevel().getGameTime();
                int value = server.getCommands().getDispatcher().execute(command,
                        player.createCommandSourceStack().withPermission(2).withSuppressedOutput());
                result.complete(new CommandResult(tick, value));
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
    }

    private static void executeBossTimeline(Minecraft mc) {
        UUID playerId = mc.player.getUUID(); int casterId = ownerId; String skillId = COMMAND_SCRIPTS[caseIndex].skill();
        var server = mc.getSingleplayerServer(); commandPending = new CompletableFuture<>();
        CompletableFuture<CommandResult> result = commandPending;
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                require(player != null, "A real connected player receives the presentation timeline");
                var boss = player.serverLevel().getEntity(casterId);
                require(boss instanceof Mob && boss instanceof ExtremeMob, "Presentation owner remains a live registered boss");
                long tick = player.serverLevel().getGameTime();
                var packets = BossSkillPreview.describe(player, BossSkill.valueOf(skillId.toUpperCase(java.util.Locale.ROOT)),
                        CENTER, new Vec3(1, 0, 0), tick, 50_000L + caseIndex * 16L);
                for (var packet : packets) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new BossSkillPayload(packet.dimension(), boss.getId(), boss.getUUID(), packet.castId(), packet.effect(),
                                packet.theme(), packet.from(), packet.to(), packet.startTick(), packet.windup(), packet.duration(),
                                packet.radius(), packet.width(), packet.gapDegrees(), false));
                result.complete(new CommandResult(tick, packets.size()));
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
    }

    private static void drainCommandPackets(Minecraft mc) {
        BossSkillPayload packet;
        while ((packet = PACKET_INBOX.poll()) != null) {
            var owner = mc.level.getEntity(ownerId);
            require(owner != null && packet.ownerId() == ownerId && packet.ownerUuid().equals(owner.getUUID()),
                    "Every real clientbound skill packet belongs to the tracked expected owner");
            require(packet.dimension().equals(mc.level.dimension().location()) && packet.valid(),
                    "Every received command payload passes its production validation");
            require(packet.startTick() >= started, "No earlier case leaks packets into this preview");
            receivedPackets.add(packet);
            JsonObject observed = new JsonObject(); observed.addProperty("effect", packet.effect().name());
            observed.addProperty("cancelled", packet.cancelled()); observed.addProperty("owner_id", packet.ownerId());
            observed.addProperty("owner_uuid", packet.ownerUuid().toString()); observed.addProperty("cast_id", packet.castId());
            observed.addProperty("start_offset", packet.startTick() - started); observed.addProperty("windup", packet.windup());
            observed.addProperty("duration", packet.duration()); observed.addProperty("radius", packet.radius());
            observed.addProperty("width", packet.width()); observed.addProperty("from", packet.from().toString());
            observed.addProperty("to", packet.to().toString()); current.getAsJsonArray("received_packets").add(observed);
            if (PRESENTATION_MODE) BossPresentationProbe.packet(packet);
        }
    }

    private static void verifyCommandComplete(Minecraft mc, CommandCase script) {
        drainCommandPackets(mc);
        require(nextCommandShot == script.shots().length, "All early and late command stages produced rendered screenshots");
        List<BossSkillPayload> effects = receivedPackets.stream().filter(packet -> !packet.cancelled()).toList();
        require(effects.size() == script.expectedPackets(), "The real network delivered every scheduled command stage exactly once");
        require(effects.stream().map(BossSkillPayload::castId).distinct().count() == effects.size(),
                "Every simultaneous or delayed stage has a distinct cast ID");
        long end = effects.stream().mapToLong(packet -> packet.startTick() + packet.windup() + packet.duration()).max().orElseThrow();
        require(end - started == script.endTick(), "The preview's complete multi-stage timeline matches its declared final phase");
        require(mc.level.getGameTime() > end + 12, "Lifecycle checks run after the final delayed stage and visual fade");
        require(BossSkillVisuals.activeEffects() == 0 && BossSkillVisuals.lastFrameVertices() == 0,
                "The complete command timeline leaves no pending instances or rendered geometry");
        switch (script.skill()) {
            case "zombie_triple" -> {
                require(effects.stream().allMatch(p -> p.effect() == BossSkillPayload.Effect.RING), "All three stomp waves arrive");
                require(effects.stream().map(p -> p.startTick() - started).sorted().toList().equals(List.of(0L, 40L, 80L)),
                        "Triple-stomp warning/activation windows retain their separate start times");
            }
            case "tyrant_blades" -> require(effects.stream().allMatch(p -> p.effect() == BossSkillPayload.Effect.BLADE)
                            && effects.stream().map(BossSkillPayload::to).distinct().count() == 3,
                    "All three physical blade lanes are transmitted with distinct directions");
            case "annihilation_singularity" -> require(count(effects, BossSkillPayload.Effect.BLACK_HOLE) == 1
                            && count(effects, BossSkillPayload.Effect.MARK) == 1,
                    "Black-hole pull and its later collapse mark are both delivered");
            case "emperor_sun" -> require(count(effects, BossSkillPayload.Effect.SUN) == 1 && count(effects, BossSkillPayload.Effect.RING) == 2
                            && effects.stream().filter(p -> p.effect() == BossSkillPayload.Effect.RING)
                                    .map(p -> p.startTick() - started).sorted().toList().equals(List.of(40L, 94L)),
                    "The sun body and both delayed waves cross the real connection");
            case "annihilation_blink" -> require(count(effects, BossSkillPayload.Effect.BLINK) == 1 && count(effects, BossSkillPayload.Effect.FAN) == 1,
                    "Blink and the second-stage slash are both delivered");
            case "zombie_chain" -> require(count(effects, BossSkillPayload.Effect.CHAIN) == 1, "The chain timeline arrives");
            case "annihilation_slash" -> require(count(effects, BossSkillPayload.Effect.BEAM) == 1, "The slash timeline arrives");
            case "tyrant_command" -> require(count(effects, BossSkillPayload.Effect.COMMAND) == 1, "The command timeline arrives");
            default -> require(count(effects, BossSkillPayload.Effect.RING) == 1, "The single-stage stomp arrives");
        }
        if (script.stopTick() >= 0) {
            require(stopCaptured && stopExecutedTick != Long.MIN_VALUE, "Stop was exercised after the preview became visible");
            require(receivedPackets.stream().anyMatch(p -> p.cancelled() && p.startTick() >= stopExecutedTick),
                    "Stop crosses the clientbound network as an owner-scoped cancellation");
            current.addProperty("stop_cleared_current_and_future_stages", true);
        }
        current.addProperty("actual_effect_packet_count", effects.size());
        current.addProperty("last_stage_end_tick", end); current.addProperty("lifecycle_expired", true);
        current.addProperty("all_stage_screenshots_captured", true);
        if (PRESENTATION_MODE) BossPresentationProbe.complete(mc, ownerId, controlId, current, playerPreview(), stopExecutedTick);
        write();
    }

    private static long count(List<BossSkillPayload> packets, BossSkillPayload.Effect effect) {
        return packets.stream().filter(packet -> packet.effect() == effect).count();
    }

    private static Vec3 effectFrom(Case sample) {
        return switch (sample.effect()) {
            case BEAM, BLADE -> CENTER.add(0, .8, 0);
            case CHAIN -> CENTER.add(0, 1, 0);
            default -> CENTER;
        };
    }

    private static Vec3 effectTo(Case sample) {
        return switch (sample.effect()) {
            case BEAM, BLADE -> effectFrom(sample).add(20, 0, 0);
            case CHAIN -> effectFrom(sample).add(14, 0, 0);
            case BLINK -> CENTER.add(5, 0, 5);
            case MARK, BLACK_HOLE, SUN -> CENTER;
            default -> CENTER.add(1, 0, 0);
        };
    }

    private static void capture(Minecraft mc, String name, long age, boolean warning) throws Exception {
        boolean restored = PRESENTATION_MODE && name.equals("restored-pose");
        if (!restored) {
            require(BossSkillVisuals.activeEffects() > 0, "Production renderer retains the active effect");
            require(BossSkillVisuals.lastFrameVertices() > 0, "The effect submitted actual world vertices");
        }
        if (warning) require(BossSkillVisuals.lastFrameBoundaryVertices() > 0, "Danger boundary is rendered even at minimal particles");
        try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            long changed = 0;
            for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++) {
                int actual = pixels.getPixelRGBA(x, y), before = baseline.getPixelRGBA(x, y);
                int difference = Math.abs((actual & 255) - (before & 255)) + Math.abs(((actual >> 8) & 255) - ((before >> 8) & 255))
                        + Math.abs(((actual >> 16) & 255) - ((before >> 16) & 255));
                if (difference > 45) changed++;
            }
            if (!restored) require(changed > 100, "The captured framebuffer visibly differs from its baseline");
            Path screenshot = output().getParent().resolve(CASES[caseIndex].name()).resolve(name + ".png"); pixels.writeToFile(screenshot);
            JsonObject frame = new JsonObject(); frame.addProperty("phase", name); frame.addProperty("age", age);
            frame.addProperty("screenshot", screenshot.toString()); frame.addProperty("changed_pixels", changed);
            frame.addProperty("vertices", BossSkillVisuals.lastFrameVertices()); frame.addProperty("boundary_vertices", BossSkillVisuals.lastFrameBoundaryVertices());
            if (PRESENTATION_MODE) BossPresentationProbe.capture(mc, ownerId, controlId, frame, restored, playerPreview());
            current.getAsJsonArray("frames").add(frame); write();
        }
    }

    private static boolean owned(Minecraft mc) { return worldName != null && mc.getSingleplayerServer() != null && worldName.equals(mc.getSingleplayerServer().getWorldData().getLevelName()); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
    private static void fail(Minecraft mc, Throwable failure) {
        if (PRESENTATION_MODE) BossPresentationProbe.failure(current);
        if (!REPORT.has("failures")) REPORT.add("failures", new JsonArray()); REPORT.getAsJsonArray("failures").add(failure.toString());
        checkpoint(mc);
        if (owned(mc)) try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            Path screenshot = output().getParent().resolve("failure-screen.png"); Files.createDirectories(screenshot.getParent()); pixels.writeToFile(screenshot);
            REPORT.addProperty("failure_screenshot", screenshot.toString());
        } catch (Exception captureFailure) { REPORT.addProperty("failure_capture_error", captureFailure.toString()); }
        LogUtils.getLogger().error("MODERN_ITE_BOSS_SKILLS_AUDIT failed", failure); finish(mc);
    }
    private static void checkpoint(Minecraft mc) {
        JsonObject state = new JsonObject(); state.addProperty("phase", phase.name()); state.addProperty("elapsed_client_ticks", elapsed);
        state.addProperty("screen", mc.screen == null ? "none" : mc.screen.getClass().getName());
        state.addProperty("overlay", mc.getOverlay() == null ? "none" : mc.getOverlay().getClass().getName());
        state.addProperty("level_present", mc.level != null); state.addProperty("player_present", mc.player != null);
        state.addProperty("server_present", mc.getSingleplayerServer() != null); state.addProperty("pending_done", pending != null && pending.isDone());
        state.addProperty("case_index", caseIndex); state.addProperty("owner_id", ownerId);
        state.addProperty("owner_tracked", mc.level != null && mc.level.getEntity(ownerId) != null);
        state.addProperty("game_tick", mc.level == null ? -1 : mc.level.getGameTime());
        state.addProperty("client_player_position", mc.player == null ? "none" : mc.player.position().toString());
        state.addProperty("client_arena_chunk_loaded", mc.level != null && mc.level.hasChunkAt(BlockPos.containing(CENTER)));
        if (serverSnapshot != null) state.add("server", serverSnapshot);
        if (owned(mc) && mc.player != null) {
            UUID playerId = mc.player.getUUID(); int entityId = ownerId; var server = mc.getSingleplayerServer();
            server.execute(() -> {
                JsonObject snapshot = new JsonObject(); var player = server.getPlayerList().getPlayer(playerId);
                snapshot.addProperty("player_position", player == null ? "none" : player.position().toString());
                var level = player == null ? server.overworld() : player.serverLevel(); var owner = level.getEntity(entityId);
                snapshot.addProperty("owner_id", entityId); snapshot.addProperty("owner_present", owner != null);
                snapshot.addProperty("arena_chunk_loaded", level.hasChunkAt(BlockPos.containing(CENTER)));
                if (owner != null) {
                    snapshot.addProperty("owner_uuid", owner.getUUID().toString()); snapshot.addProperty("owner_alive", owner.isAlive());
                    snapshot.addProperty("owner_removed", owner.isRemoved()); snapshot.addProperty("owner_position", owner.position().toString());
                }
                serverSnapshot = snapshot;
            });
        }
        REPORT.add("last_checkpoint", state); write();
        LogUtils.getLogger().info("MODERN_ITE_BOSS_SKILLS_AUDIT checkpoint {}", state);
    }
    private static void finish(Minecraft mc) {
        phase = Phase.DONE; if (baseline != null) { baseline.close(); baseline = null; }
        if (observedChannel != null) {
            Channel channel = observedChannel; observedChannel = null;
            channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(PACKET_OBSERVER) != null) channel.pipeline().remove(PACKET_OBSERVER);
            });
        }
        REPORT.addProperty("status", REPORT.getAsJsonArray("failures").isEmpty() ? "passed" : "failed");
        REPORT.addProperty("safe_client_shutdown_requested", owned(mc)); write(); if (owned(mc)) mc.stop();
    }
    private static Path output() {
        String fallback = PRESENTATION_MODE ? "build/verification/boss-presentation/audit.json" : COMMAND_MODE ? "build/verification/boss-skills/command-audit.json"
                : Boolean.getBoolean("modern_ite.bossSkillsAuditExtended") ? "build/verification/boss-skills/extended-audit.json" : "build/verification/boss-skills/client-audit.json";
        return Path.of(System.getProperty("modern_ite.bossSkillsAuditOutput", fallback)).toAbsolutePath().normalize();
    }
    private static void write() {
        try { Files.createDirectories(output().getParent()); Files.writeString(output(), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT) + "\n", StandardCharsets.UTF_8); }
        catch (Exception failure) { LogUtils.getLogger().error("Cannot write boss skills audit", failure); }
    }
}
