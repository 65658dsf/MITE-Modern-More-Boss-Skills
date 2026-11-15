package io.github.ningmeng123.morebossskills.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.ningmeng123.morebossskills.MoreBossSkills;
import io.github.ningmeng123.morebossskills.client.boss.BossCastAnimations;
import io.github.ningmeng123.morebossskills.client.boss.BossSkillSounds;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload;
import io.github.ningmeng123.morebossskills.entity.boss.BossSoundTimeline;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;

/** Observes actual model render scopes and native sound-engine channels. @author NingMeng123 */
@EventBusSubscriber(modid = MoreBossSkills.MOD_ID, value = Dist.CLIENT)
public final class BossPresentationProbe {
    private record Heard(SoundInstance sound, long tick) {}
    private record NativeSource(SoundInstance sound, com.mojang.blaze3d.audio.Channel channel,
                                net.minecraft.client.sounds.SoundEngine engine) {}
    private record NativeState(String sound, boolean playing, boolean stopped) {}
    private static final ConcurrentLinkedQueue<Heard> REQUESTED = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Heard> PLAYING = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<NativeSource> NATIVE = new ConcurrentLinkedQueue<>();
    private static final List<BossSkillPayload> PACKETS = new ArrayList<>();
    private static final Gson JSON = new Gson();
    private static volatile boolean running;
    private static boolean armChanged, legChanged, stoppedSilence;
    private static boolean customArmorCopyVerified;
    private static BossSkillSounds.Diagnostics initialSounds;
    private static CompletableFuture<List<NativeState>> stoppedChannels;
    private static int renderChecks;
    private BossPresentationProbe() {}

    @SubscribeEvent public static void requested(PlaySoundEvent event) {
        if (!running || !enabled() || event.getSound() == null || !BossSkillSounds.owns(event.getSound())) return;
        REQUESTED.add(new Heard(event.getSound(), now()));
    }

    @SubscribeEvent public static void playing(PlaySoundSourceEvent event) {
        if (!running || !enabled() || !BossSkillSounds.owns(event.getSound())) return;
        PLAYING.add(new Heard(event.getSound(), now()));
        NATIVE.add(new NativeSource(event.getSound(), event.getChannel(), event.getEngine()));
    }

    public static void begin(JsonObject sample) {
        running = true; REQUESTED.clear(); PLAYING.clear(); NATIVE.clear(); PACKETS.clear(); renderChecks = 0;
        stoppedChannels = null;
        initialSounds = BossSkillSounds.diagnostics(); stoppedSilence = false;
        sample.addProperty("sound_evidence", "PlaySoundEvent scheduling and PlaySoundSourceEvent actual non-streaming audio channels");
        sample.addProperty("pose_evidence", "Actual humanoid render-scope before/posed/restored model snapshots, including armor passes");
    }

    public static void packet(BossSkillPayload packet) { PACKETS.add(packet); }

    public static void baseline(Minecraft mc, int ownerId, int controlId, JsonObject sample, boolean preview) {
        if (preview) return;
        Mob boss = mob(mc, ownerId), control = mob(mc, controlId);
        var pose = BossCastAnimations.snapshot(boss);
        var ordinary = BossCastAnimations.snapshot(control);
        require(pose != null && ordinary != null, "Both the registered boss and ordinary armored control were actually rendered");
        require(pose.state() == null && ordinary.state() == null, "Baseline actors have no cast pose");
        sample.add("baseline_boss_pose", JSON.toJsonTree(pose));
        sample.add("baseline_control_pose", JSON.toJsonTree(ordinary));
    }

    public static void tick(Minecraft mc, int ownerId, int controlId, JsonObject sample, boolean preview) {
        if (preview) {
            long first = PACKETS.stream().filter(p -> !p.cancelled()).mapToLong(BossSkillPayload::startTick).min().orElse(Long.MAX_VALUE);
            long cancelled = PACKETS.stream().filter(p -> p.cancelled() && p.startTick() > first)
                    .mapToLong(BossSkillPayload::startTick).max().orElse(Long.MAX_VALUE);
            if (cancelled != Long.MAX_VALUE && mc.level.getGameTime() >= cancelled + 2) {
                require(BossSkillSounds.activeSounds().isEmpty(), "Stop immediately clears the currently playing preview voices");
                // SoundManager.isActive retains stopped entries for MIN_SOURCE_LIFETIME (20 ticks).
                // Query the actual OpenAL channels on their owning executor instead of treating
                // that bookkeeping lifetime as proof that an already-stopped sound is audible.
                if (stoppedChannels == null) stoppedChannels = nativeStates();
                if (stoppedChannels.isDone()) {
                    var states = stoppedChannels.join();
                    require(!states.isEmpty() && states.stream().allMatch(state -> state.stopped() && !state.playing()),
                            "The cancelled preview's actual native audio channels stop before their natural expiry");
                    sample.add("native_channels_immediately_after_stop", JSON.toJsonTree(states));
                    if (mc.level.getGameTime() <= cancelled + 6) stoppedSilence = true;
                } else require(mc.level.getGameTime() <= cancelled + 6, "Native stop observation completes promptly on the sound executor");
            }
            return;
        }
        var ordinary = BossCastAnimations.snapshot(mob(mc, controlId));
        require(ordinary != null && ordinary.state() == null && ordinary.maxPoseDelta() < 1e-6,
                "An adjacent ordinary mob never acquires the boss pose");
        var pose = BossCastAnimations.snapshot(mob(mc, ownerId));
        if (pose != null && pose.state() != null) {
            require(pose.restoredError() < 1e-5, "Every animated render restores borrowed model transforms before another entity renders");
            require(pose.armorError() < 1e-5, "Armor receives exactly the intended cast pose without a second transform");
            for (var packet : PACKETS) if (!packet.cancelled()
                    && (packet.effect() == BossSkillPayload.Effect.SUN || packet.effect() == BossSkillPayload.Effect.BLACK_HOLE)) {
                long age = pose.gameTick() - packet.startTick();
                if (age >= 0 && age < BossCastAnimations.activeUntil(packet) - 1) {
                    require(pose.state().castId() == packet.castId() && pose.state().effect() == packet.effect(),
                            "A sustained ritual keeps its existing pose clock when later ring or collapse warnings appear");
                    sample.addProperty("channel_pose_kept_its_cast_id", true);
                }
            }
            renderChecks++;
        }
        sample.addProperty("checked_animated_frames", renderChecks);
    }

    public static void capture(Minecraft mc, int ownerId, int controlId, JsonObject frame, boolean restored, boolean preview) {
        frame.addProperty("sound_requests_so_far", REQUESTED.size());
        frame.addProperty("sound_channels_started_so_far", PLAYING.size());
        if (preview) return;
        var pose = BossCastAnimations.snapshot(mob(mc, ownerId));
        var ordinary = BossCastAnimations.snapshot(mob(mc, controlId));
        require(pose != null && ordinary != null && mc.level.getGameTime() - pose.gameTick() <= 3,
                "The screenshot contains fresh real model-render evidence");
        if (restored) require(pose.state() == null && pose.maxPoseDelta() < 1e-6,
                "After the timeline the boss returns to its ordinary model pose");
        else {
            require(pose.state() != null && pose.maxPoseDelta() > .025, "The warning/release visibly changes the boss model pose");
            require(pose.armorPasses() > 0 && pose.armorError() < 1e-5, "The visible chest and leg armor share the animated pose");
            double arms = Math.max(rotationDelta(pose.before().rightArm(), pose.posed().rightArm()),
                    rotationDelta(pose.before().leftArm(), pose.posed().leftArm()));
            double legs = Math.max(rotationDelta(pose.before().rightLeg(), pose.posed().rightLeg()),
                    rotationDelta(pose.before().leftLeg(), pose.posed().leftLeg()));
            armChanged |= arms > .025; legChanged |= legs > .025;
            frame.addProperty("arm_rotation_delta", arms); frame.addProperty("leg_rotation_delta", legs);
        }
        require(pose.restoredError() < 1e-5 && ordinary.state() == null && ordinary.maxPoseDelta() < 1e-6,
                "The boss's borrowed model transforms are restored and its ordinary neighbor stays unchanged");
        frame.add("boss_render", JSON.toJsonTree(pose)); frame.add("ordinary_control_render", JSON.toJsonTree(ordinary));
        if (!restored && !customArmorCopyVerified) {
            verifySharedCustomArmor(mc, mob(mc, ownerId), mob(mc, controlId), frame);
            customArmorCopyVerified = true;
        }
    }

    public static void complete(Minecraft mc, int ownerId, int controlId, JsonObject sample, boolean preview, long stopped) {
        require(!REQUESTED.isEmpty() && !PLAYING.isEmpty(), "Skill sounds actually reached both the SoundManager and native audio channels");
        var identities = new HashSet<SoundInstance>();
        for (Heard request : REQUESTED) {
            require(request.sound().getSource() == SoundSource.HOSTILE && !request.sound().isRelative(),
                    "Every skill sound obeys the hostile-creature volume category and world-space attenuation");
            require(!mc.getSoundManager().isActive(request.sound()), "Completed/cancelled skill sounds do not remain active");
            identities.add(request.sound());
        }
        for (Heard source : PLAYING) require(identities.contains(source.sound()), "Each native channel belongs to an observed production sound request");
        require(BossSkillSounds.activeSounds().isEmpty(), "No tracked skill voice survives the completed or stopped timeline");
        var effects = PACKETS.stream().filter(p -> !p.cancelled()).toList();
        require(!effects.isEmpty(), "Sound checks are anchored to real received packets");
        for (Heard heard : REQUESTED) {
            var voice = (BossSkillSounds.OwnedSound) heard.sound();
            var packet = effects.stream().filter(p -> p.castId() == voice.castId() && p.ownerUuid().equals(voice.owner())).findFirst().orElseThrow();
            require(BossSoundTimeline.schedule(packet.effect(), packet.theme(), packet.windup(), packet.duration()).stream()
                            .anyMatch(cue -> cue.phase() == voice.phase() && cue.soundId().equals(voice.getLocation().toString())
                                    && Math.abs(heard.tick() - (packet.startTick() + cue.tick())) <= 3),
                    "Every actual submitted sound matches a defined phase and scheduled tick in the shared production timeline");
        }
        long first = effects.stream().mapToLong(BossSkillPayload::startTick).min().orElseThrow();
        int windup = effects.getFirst().windup();
        require(REQUESTED.stream().anyMatch(sound -> sound.tick() >= first && sound.tick() < first + windup),
                "The skill has an audible warning before its release");
        require(REQUESTED.stream().anyMatch(sound -> Math.abs(sound.tick() - (first + windup)) <= 3),
                "The release cue enters the sound engine on the effect's scheduled release tick");
        if (effects.size() == 3 && effects.stream().allMatch(p -> p.effect() == BossSkillPayload.Effect.BLADE)) {
            var once = new HashSet<String>();
            for (Heard sound : REQUESTED) require(once.add(sound.tick() + ":" + sound.sound().getLocation()),
                    "Three simultaneous bone blades do not triple the same audible cue");
            require(BossSkillSounds.diagnostics().deduplicated() - initialSounds.deduplicated() >= 2,
                    "The sound scheduler actually suppressed duplicate blade voices");
            sample.addProperty("three_blade_sound_deduplicated", true);
        }
        if (stopped != Long.MIN_VALUE) {
            require(PACKETS.stream().anyMatch(BossSkillPayload::cancelled), "The preview stop arrived over the real connection");
            require(REQUESTED.stream().noneMatch(sound -> sound.tick() > stopped + 2),
                    "Stop interrupts current voices and no scheduled future wave starts a sound later");
            require(stoppedSilence, "Silence was observed immediately after stop, before the sun release sample would naturally expire");
            require(armChanged && legChanged, "The complete boss suite observed both arm and leg joint animation");
            sample.addProperty("stop_prevented_all_future_audio", true);
        } else require(renderChecks > 0, "Real animated model scopes were checked during this boss timeline");
        sample.add("sound_requests", soundJson(REQUESTED)); sample.add("sound_channels", soundJson(PLAYING));
        sample.add("sound_diagnostics", JSON.toJsonTree(BossSkillSounds.diagnostics()));
        sample.add("sound_diagnostics_before", JSON.toJsonTree(initialSounds));
        require(BossSkillSounds.diagnostics().peakVoices() <= BossSkillSounds.MAX_VOICES, "The complete audit obeys the production sound voice budget");
        sample.addProperty("audio_and_pose_verified", true); running = false;
    }

    public static void failure(JsonObject sample) {
        if (sample == null) return;
        sample.add("sound_requests_at_failure", soundJson(REQUESTED)); sample.add("sound_channels_at_failure", soundJson(PLAYING));
        sample.add("sound_diagnostics_at_failure", JSON.toJsonTree(BossSkillSounds.diagnostics()));
        running = false;
    }

    private static double rotationDelta(BossCastAnimations.BonePose a, BossCastAnimations.BonePose b) {
        return Math.max(Math.abs(a.xRot() - b.xRot()), Math.max(Math.abs(a.yRot() - b.yRot()), Math.abs(a.zRot() - b.zRot())));
    }

    private static JsonArray soundJson(Iterable<Heard> sounds) {
        JsonArray result = new JsonArray();
        for (Heard heard : sounds) {
            JsonObject item = new JsonObject(); item.addProperty("tick", heard.tick());
            item.addProperty("sound", heard.sound().getLocation().toString()); item.addProperty("category", heard.sound().getSource().name());
            item.addProperty("volume", heard.sound().getVolume()); item.addProperty("pitch", heard.sound().getPitch());
            item.addProperty("x", heard.sound().getX()); item.addProperty("y", heard.sound().getY()); item.addProperty("z", heard.sound().getZ());
            result.add(item);
        }
        return result;
    }

    private static CompletableFuture<List<NativeState>> nativeStates() {
        List<NativeSource> sources = List.copyOf(NATIVE);
        require(!sources.isEmpty(), "Native sound sources were observed before cancellation");
        try {
            var field = net.minecraft.client.sounds.SoundEngine.class.getDeclaredField("executor");
            field.setAccessible(true);
            Executor executor = (Executor) field.get(sources.getFirst().engine());
            return CompletableFuture.supplyAsync(() -> sources.stream().map(source -> new NativeState(
                    source.sound().getLocation().toString(), source.channel().playing(), source.channel().stopped())).toList(), executor);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot observe native sound state on its own executor", failure);
        }
    }

    private static void verifySharedCustomArmor(Minecraft mc, Mob boss, Mob ordinary, JsonObject frame) {
        var layer = net.minecraft.client.model.geom.ModelLayers.PLAYER_INNER_ARMOR;
        var original = new net.minecraft.client.model.HumanoidModel<net.minecraft.world.entity.LivingEntity>(mc.getEntityModels().bakeLayer(layer));
        var shared = new net.minecraft.client.model.HumanoidModel<net.minecraft.world.entity.LivingEntity>(mc.getEntityModels().bakeLayer(layer));
        shared.rightArm.setRotation(.37F, -.21F, .13F); shared.leftLeg.setRotation(-.29F, .17F, -.11F);
        shared.body.setPos(.2F, .4F, -.3F);
        var prior = BossCastAnimations.PoseSnapshot.capture(shared);
        BossCastAnimations.PoseSnapshot cast;
        try (var scope = BossCastAnimations.begin(original, boss, 0, List.of())) {
            BossCastAnimations.apply(original, boss);
            cast = BossCastAnimations.PoseSnapshot.capture(original);
            require(cast.difference(prior) > .05, "The shared custom armor fixture has a distinct preexisting pose");
            // This is the actual extension helper whose copy happens before the custom armor render callback.
            net.neoforged.neoforge.client.ClientHooks.copyModelProperties(original, shared);
            require(BossCastAnimations.PoseSnapshot.capture(shared).difference(cast) < 1e-5,
                    "ClientHooks copies the intended boss pose into the shared custom armor model");
        }
        float restoredError = BossCastAnimations.PoseSnapshot.capture(shared).difference(prior);
        require(restoredError < 1e-5, "The real armor-copy hook preserves the shared replacement before copying and restores it at scope exit");
        original.setupAnim(ordinary, 0, 0, ordinary.tickCount, 0, 0);
        var ordinaryPose = BossCastAnimations.PoseSnapshot.capture(original);
        try (var scope = BossCastAnimations.begin(original, ordinary, 0, List.of())) {
            BossCastAnimations.apply(original, ordinary);
            net.neoforged.neoforge.client.ClientHooks.copyModelProperties(original, shared);
            require(BossCastAnimations.PoseSnapshot.capture(shared).difference(ordinaryPose) < 1e-5,
                    "The next ordinary mob copies only its own pose into the same shared custom armor");
        }
        JsonObject result = new JsonObject(); result.addProperty("actual_clienthooks_copy_invoked", true);
        result.addProperty("replacement_restore_error", restoredError); result.addProperty("next_ordinary_mob_clean", true);
        frame.add("shared_custom_armor_copy_regression", result);
    }

    private static Mob mob(Minecraft mc, int id) {
        require(mc.level != null && mc.level.getEntity(id) instanceof Mob, "Expected tracked model owner " + id);
        return (Mob) mc.level.getEntity(id);
    }
    private static long now() { var level = Minecraft.getInstance().level; return level == null ? -1 : level.getGameTime(); }
    private static boolean enabled() { return Boolean.getBoolean("modern_ite.bossPresentationAudit"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
