package io.github.ningmeng123.morebossskills.client.boss;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.Tesselator;
import io.github.ningmeng123.morebossskills.MoreBossSkills;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload.Effect;
import io.github.ningmeng123.morebossskills.entity.boss.BossGeometry;
import io.github.ningmeng123.modernite.entity.ExtremeMob;
import io.github.ningmeng123.morebossskills.entity.boss.BossAbilities;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Readable timed ground markings and sculpted spell meshes, independent of particle density. @author NingMeng123 */
@EventBusSubscriber(modid = MoreBossSkills.MOD_ID, value = Dist.CLIENT)
public final class BossSkillVisuals {
    public static final int MAX_EFFECTS = 128;
    private static final int END_FADE = 12;
    private static final double TAU = Math.PI * 2;
    private static final Vec3 X = new Vec3(1, 0, 0), Y = new Vec3(0, 1, 0), Z = new Vec3(0, 0, 1);
    private static final List<Spell> SPELLS = new ArrayList<>();
    private static final LinkedHashMap<Key, Long> SEEN = new LinkedHashMap<>();
    private static final Map<UUID, Long> CANCELLED = new HashMap<>();
    private static ClientLevel world;
    private static int frameVertices, frameBoundaryVertices;
    private static final Palette[] THEMES = {
            new Palette(0x8EB694, 0xD8F9FF, 0x354D48, 0x64816A),
            new Palette(0xB779FF, 0xF0E9FF, 0x100B1B, 0x7855BA),
            new Palette(0x7BDEFF, 0xF6ECD6, 0x343B46, 0xA7C3CC),
            new Palette(0xE9B953, 0xFFE6AC, 0x190B12, 0x9E293E)
    };
    private BossSkillVisuals() {}
    public static int activeEffects() { return SPELLS.size(); }
    public static int lastFrameVertices() { return frameVertices; }
    public static int lastFrameBoundaryVertices() { return frameBoundaryVertices; }
    /** Sample the same announced timeline as the ground effects; pure player previews have no mob pose. */
    public static BossSkillPayload animationFor(Mob mob, float partialTick) {
        if (world == null || mob.level() != world || !mob.isAlive() || mob.isRemoved()
                || !(mob instanceof ExtremeMob extreme) || !BossAbilities.handles(extreme.extremeKind())) return null;
        BossSkillPayload selected = null;
        int selectedPriority = -1;
        for (Spell spell : SPELLS) {
            BossSkillPayload packet = spell.packet;
            if (packet.ownerId() != mob.getId() || !packet.ownerUuid().equals(mob.getUUID())) continue;
            double age = spell.age(partialTick);
            if (age < 0 || age > BossCastAnimations.activeUntil(packet)) continue;
            // A commander's follower projectiles retain damage ownership, but are not its own weapon swing.
            if (packet.effect() == Effect.BLADE && packet.theme() == 2 && packet.width() < 1.3F) continue;
            boolean channel = packet.effect() == Effect.BLACK_HOLE || packet.effect() == Effect.SUN;
            // Pulses and the collapse marker are parts of the held ritual, not fresh body windups.
            // Keeping its pose avoids dropping both arms whenever a delayed sub-effect begins.
            int priority = channel ? 4 : age <= packet.windup() + 8 ? 3 : 1;
            if (priority > selectedPriority || priority == selectedPriority
                    && (selected == null || packet.startTick() > selected.startTick())) {
                selected = packet;
                selectedPriority = priority;
            }
        }
        return selected;
    }
    public static void clear() {
        BossSkillSounds.clear();
        SPELLS.clear(); SEEN.clear(); CANCELLED.clear(); world = null;
        frameVertices = 0; frameBoundaryVertices = 0;
    }
    public static void receive(BossSkillPayload payload) {
        var mc = Minecraft.getInstance();
        if (!payload.valid() || mc.level == null || !mc.level.dimension().location().equals(payload.dimension())) return;
        if (world != mc.level) { clear(); world = mc.level; }
        if (payload.cancelled()) {
            SPELLS.removeIf(spell -> spell.packet.ownerUuid().equals(payload.ownerUuid()));
            BossSkillSounds.cancel(payload.ownerUuid());
            CANCELLED.put(payload.ownerUuid(), payload.startTick());
            return;
        }
        // This ordered channel may legitimately cancel and start a new cast in the same server tick.
        if (payload.startTick() < CANCELLED.getOrDefault(payload.ownerUuid(), Long.MIN_VALUE)) return;
        long age = world.getGameTime() - payload.startTick();
        if (age > lifetime(payload) || age < -200) return;
        Key key = new Key(payload.ownerUuid(), payload.ownerId(), payload.castId());
        if (SEEN.containsKey(key)) return;
        SEEN.put(key, world.getGameTime());
        while (SEEN.size() > 1024) SEEN.remove(SEEN.keySet().iterator().next());
        if (SPELLS.size() >= MAX_EFFECTS) {
            // Completed residuals go first. The bounded emergency fallback removes the farthest spell.
            Spell discard = SPELLS.stream().filter(s -> s.age(0) >= s.packet.windup() + s.packet.duration())
                    .findFirst().orElseGet(() -> SPELLS.stream().max(Comparator.comparingDouble(s ->
                            mc.player == null ? s.age(0) : mc.player.distanceToSqr(s.packet.from()))).orElseThrow());
            SPELLS.remove(discard);
        }
        SPELLS.add(new Spell(payload));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (world != mc.level || mc.level == null) { clear(); return; }
        if (mc.isPaused()) return;
        Iterator<Spell> iterator = SPELLS.iterator();
        while (iterator.hasNext()) {
            Spell spell = iterator.next();
            var owner = world.getEntity(spell.packet.ownerId());
            if (owner != null) spell.seenOwner = true;
            boolean invalidOwner = owner != null && (!owner.getUUID().equals(spell.packet.ownerUuid())
                    || owner.isRemoved() || owner instanceof LivingEntity living && !living.isAlive());
            if (invalidOwner || owner == null && spell.seenOwner
                    || spell.age(0) > lifetime(spell.packet)) iterator.remove();
        }
        long now = world.getGameTime();
        SEEN.entrySet().removeIf(entry -> now - entry.getValue() > 3000);
        CANCELLED.entrySet().removeIf(entry -> now - entry.getValue() > 3000);
        BossSkillSounds.update(SPELLS.stream().map(spell -> spell.packet).toList());
    }
    private static double lifetime(BossSkillPayload packet) {
        return Math.max(packet.windup() + packet.duration() + END_FADE, BossCastAnimations.activeUntil(packet));
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        frameVertices = 0; frameBoundaryVertices = 0;
        var mc = Minecraft.getInstance();
        if (SPELLS.isEmpty() || mc.level == null || world != mc.level) return;
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        List<Spell> visible = SPELLS.stream().filter(spell -> spell.age(partial) >= 0
                && spell.bounds.distanceToSqr(camera) < 144D * 144D && event.getFrustum().isVisible(spell.bounds))
                .sorted(Comparator.comparingDouble((Spell spell) -> camera.distanceToSqr(spell.packet.from())).reversed()).toList();
        if (visible.isEmpty()) return;
        int detail = mc.options.particles().get() == ParticleStatus.MINIMAL ? 0
                : mc.options.particles().get() == ParticleStatus.DECREASED ? 1 : 2;
        detail = Math.min(detail, BossVisualConfig.QUALITY.get());
        var matrices = RenderSystem.getModelViewStack();
        matrices.pushMatrix();
        try {
            matrices.set(event.getModelViewMatrix());
            RenderSystem.applyModelViewMatrix();
            draw(visible, camera, partial, detail, true);
            draw(visible, camera, partial, detail, false);
        } finally {
            matrices.popMatrix(); RenderSystem.applyModelViewMatrix();
        }
    }
    @SubscribeEvent public static void camera(ViewportEvent.ComputeCameraAngles event) {
        var mc = Minecraft.getInstance();
        if (SPELLS.isEmpty() || world == null || world != mc.level || mc.player == null || mc.isPaused()
                || !BossVisualConfig.SCREEN_SHAKE.get()) return;
        double strength = 0;
        for (Spell spell : SPELLS) {
            Effect type = spell.packet.effect();
            if (type != Effect.RING && type != Effect.MARK && type != Effect.FAN && type != Effect.BLACK_HOLE) continue;
            double elapsed = spell.age((float)event.getPartialTick()) - spell.packet.windup();
            if (type == Effect.BLACK_HOLE) elapsed -= spell.packet.duration() - 3;
            if (elapsed < 0 || elapsed > 8) continue;
            double distance = mc.player.position().distanceTo(spell.packet.from());
            strength += Math.max(0, 1 - distance / 18) * (1 - elapsed / 8) * .28;
        }
        strength = Math.min(.45, strength);
        double time = world.getGameTime() + event.getPartialTick();
        event.setPitch(event.getPitch() + (float)(Math.sin(time * 2.1) * strength));
        event.setRoll(event.getRoll() + (float)(Math.cos(time * 1.7) * strength * .55));
    }
    private static void draw(List<Spell> visible, Vec3 camera, float partial, int detail, boolean solid) {
        RenderType type = solid ? BossSkillMesh.Types.SOLID : BossSkillMesh.Types.GLOW;
        BufferBuilder buffer = Tesselator.getInstance().begin(type.mode(), type.format());
        BossSkillMesh mesh = new BossSkillMesh(buffer, camera, solid);
        if (!solid) {
            mesh.boundary = true;
            // Essential attack outlines are submitted before any decorative geometry can consume the budget.
            for (Spell spell : visible) spell.boundary(mesh, partial);
            mesh.boundary = false;
        }
        for (Spell spell : visible) spell.visual(mesh, partial, detail);
        var data = buffer.build();
        if (data != null) {
            type.setupRenderState();
            try { BufferUploader.drawWithShader(data); }
            finally { type.clearRenderState(); }
        }
        frameVertices += mesh.vertices; frameBoundaryVertices += mesh.boundaryVertices;
    }
    // A reloaded saved mob retains its UUID but receives a fresh runtime entity ID and cast counter.
    private record Key(UUID owner, int entityId, long cast) {}
    private record Palette(int color, int light, int dark, int secondary) {}
    private static final class Spell {
        final BossSkillPayload packet;
        final Palette palette;
        final AABB bounds;
        final double angle;
        final double freeHeight;
        final Map<Long, Double> heights = new HashMap<>();
        boolean seenOwner;
        Spell(BossSkillPayload packet) {
            this.packet = packet; palette = THEMES[packet.theme()];
            angle = Math.atan2(packet.to().z - packet.from().z, packet.to().x - packet.from().x);
            bounds = new AABB(packet.from(), packet.to()).inflate(Math.max(2, packet.radius()) + 3, 12,
                    Math.max(2, packet.radius()) + 3);
            var owner = world.getEntity(packet.ownerId());
            seenOwner = owner != null && owner.getUUID().equals(packet.ownerUuid());
            var ceiling = world.clip(new ClipContext(packet.from().add(0, .1, 0), packet.from().add(0, 9, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, Minecraft.getInstance().player));
            freeHeight = ceiling.getType() == HitResult.Type.BLOCK
                    ? Mth.clamp(ceiling.getLocation().y - packet.from().y - .15, .5, 8) : 8;
        }
        double age(float partial) { return world.getGameTime() - packet.startTick() + partial; }
        double progress(float partial) { return Mth.clamp((age(partial) - packet.windup()) / packet.duration(), 0, 1); }
        float tail(float partial) {
            return (float)Mth.clamp(1 - (age(partial) - packet.windup() - packet.duration()) / END_FADE, 0, 1);
        }
        boolean warning(float partial) { return age(partial) < packet.windup(); }
        double ground(double x, double z, double reference) {
            // Sample only this local floor, never the heightmap/roof of a multi-level dungeon.
            int bx = Mth.floor(x * 4), bz = Mth.floor(z * 4), by = Mth.floor(reference * 4);
            long key = net.minecraft.core.BlockPos.asLong(bx, by, bz);
            Double known = heights.get(key);
            if (known != null) return known;
            if (heights.size() >= 8192) return Double.NaN;
            Vec3 sample = new Vec3(bx * .25 + .125, reference, bz * .25 + .125);
            var surface = BossGeometry.localFloor(world, Minecraft.getInstance().player, sample);
            // An unsupported/missing floor is a hole, not a glowing bridge to an unrelated cave layer.
            double value = surface.map(point -> point.y + .02).orElse(Double.NaN);
            heights.put(key, value);
            return value;
        }
        Vec3 floor(Vec3 point) {
            // The three ray attacks carry their real flight height (.8–1 block above the feet).
            // Project their warning from the local floor, not from a point inside a low roof.
            boolean airborne = packet.effect() == Effect.BEAM || packet.effect() == Effect.CHAIN || packet.effect() == Effect.BLADE;
            double reference = point.y - (airborne ? .9 : 0);
            return new Vec3(point.x, ground(point.x, point.z, reference), point.z);
        }
        Vec3 polar(Vec3 center, double radius, double theta) {
            return floor(center.add(Math.cos(theta) * radius, 0, Math.sin(theta) * radius));
        }
        void groundRing(BossSkillMesh mesh, Vec3 center, double radius, double thickness,
                double start, double sweep, int color, float alpha) {
            if (radius <= .001 || sweep <= .001) return;
            int segments = Math.max(12, (int)Math.ceil(72 * sweep / TAU));
            for (int i = 0; i < segments; i++) {
                double a = start + sweep * i / segments, b = start + sweep * (i + 1D) / segments;
                mesh.quad(polar(center, Math.max(0, radius - thickness * .5), a), polar(center, radius + thickness * .5, a),
                        polar(center, radius + thickness * .5, b), polar(center, Math.max(0, radius - thickness * .5), b), color, alpha);
            }
        }
        void lineFloor(BossSkillMesh mesh, Vec3 from, Vec3 to, double thickness, int color, float alpha) {
            Vec3 delta = to.subtract(from), side = new Vec3(-delta.z, 0, delta.x).normalize().scale(thickness * .5);
            int pieces = Math.max(1, (int)Math.ceil(delta.length()));
            for (int i = 0; i < pieces; i++) {
                Vec3 a = from.lerp(to, i / (double)pieces), b = from.lerp(to, (i + 1D) / pieces);
                mesh.quad(floor(a.subtract(side)), floor(a.add(side)), floor(b.add(side)), floor(b.subtract(side)), color, alpha);
            }
        }
        void boundary(BossSkillMesh mesh, float partial) {
            double age = age(partial), p = progress(partial);
            boolean warning = warning(partial);
            float alpha = tail(partial) * (warning ? .72F + .2F * (float)Math.sin(age * .4) : .65F);
            Vec3 from = packet.from(), to = packet.to();
            double radius = packet.radius();
            switch (packet.effect()) {
                case RING -> {
                    double gap = Math.toRadians(packet.gapDegrees()), start = angle + gap * .5, sweep = TAU - gap;
                    groundRing(mesh, from, radius, .13, start, sweep, palette.color, alpha * (warning ? 1 : .25F));
                    if (gap > 0) for (double edge : new double[]{start, start + sweep}) {
                        lineFloor(mesh, from, from.add(Math.cos(edge) * radius, 0, Math.sin(edge) * radius), .1, palette.light, alpha);
                        Vec3 tip = polar(from, radius, edge);
                        mesh.ribbon(tip, tip.add(0, .75, 0), .12, palette.light, alpha);
                    }
                    if (!warning) groundRing(mesh, from, radius * p, Math.max(.12, packet.width()),
                            start, sweep, palette.light, .88F * tail(partial));
                }
                case MARK, COMMAND, BLACK_HOLE, SUN -> {
                    groundRing(mesh, from, radius, .13, 0, TAU, palette.light, alpha);
                    if (warning) {
                        double countdown = Mth.clamp(age / Math.max(1, packet.windup()), 0, 1);
                        groundRing(mesh, from, radius * .94, .07, -Math.PI * .5, TAU * countdown, palette.color, alpha);
                    }
                    if (packet.effect() == Effect.BLACK_HOLE && packet.width() > 0)
                        groundRing(mesh, from, packet.width(), .15, 0, TAU, palette.secondary,
                                tail(partial) * (p > .72 ? .95F : .45F));
                    if (packet.effect() == Effect.MARK) {
                        lineFloor(mesh, from.add(-radius * .65, 0, 0), from.add(radius * .65, 0, 0), .08, palette.color, alpha);
                        lineFloor(mesh, from.add(0, 0, -radius * .65), from.add(0, 0, radius * .65), .08, palette.color, alpha);
                    }
                }
                case BEAM, CHAIN, BLADE -> {
                    Vec3 side = new Vec3(-(to.z - from.z), 0, to.x - from.x).normalize().scale(packet.width() * .5);
                    lineFloor(mesh, from.add(side), to.add(side), .13, palette.light, alpha);
                    lineFloor(mesh, from.subtract(side), to.subtract(side), .13, palette.light, alpha);
                    lineFloor(mesh, to.subtract(side), to.add(side), .1, palette.color, alpha);
                    if (warning) lineFloor(mesh, from, to, .07, palette.color, alpha * .8F);
                }
                case BLINK -> {
                    groundRing(mesh, from, Math.max(1, radius), .14, 0, TAU, palette.color, alpha);
                    groundRing(mesh, to, Math.max(1, radius), .14, 0, TAU, palette.light, alpha);
                }
                case FAN -> {
                    double sweep = Math.toRadians(packet.width()), start = angle - sweep * .5;
                    groundRing(mesh, from, radius, .16, start, sweep, palette.light, alpha);
                    lineFloor(mesh, from, from.add(Math.cos(start) * radius, 0, Math.sin(start) * radius), .12, palette.light, alpha);
                    lineFloor(mesh, from, from.add(Math.cos(start + sweep) * radius, 0, Math.sin(start + sweep) * radius), .12, palette.light, alpha);
                    Vec3 center = floor(from).add(0, .005, 0);
                    for (int i = 0; i < 36; i++) mesh.triangle(center,
                            polar(from, radius, start + sweep * i / 36), polar(from, radius, start + sweep * (i + 1D) / 36),
                            palette.color, alpha * .12F);
                }
            }
        }
        void visual(BossSkillMesh mesh, float partial, int detail) {
            double age = age(partial), p = progress(partial), activeAge = age - packet.windup();
            boolean warning = warning(partial);
            Vec3 from = packet.from(), to = packet.to();
            float fade = tail(partial);
            if (warning) {
                if (!mesh.solid) charge(mesh, age, detail);
                if (packet.effect() != Effect.BLACK_HOLE && packet.effect() != Effect.SUN) return;
            }
            switch (packet.effect()) {
                case RING -> wave(mesh, p, fade, detail);
                case MARK -> eruption(mesh, activeAge, p, fade, detail);
                case BEAM -> {
                    if (mesh.solid) return;
                    float beamFade = fade * (float)(1 - p * .75);
                    mesh.ribbon(from, to, Math.max(.15, packet.width()), palette.color, beamFade * .45F);
                    mesh.ribbon(from, to, Math.max(.08, packet.width() * .22), palette.light, beamFade);
                    helix(mesh, from, to, Math.max(.2, packet.width() * .55), age * .2, detail == 0 ? 12 : 24,
                            palette.color, beamFade * .8F);
                }
                case CHAIN -> chains(mesh, p, fade, detail);
                case BLINK -> {
                    if (mesh.solid) return;
                    for (Vec3 end : new Vec3[]{from, to}) for (int j = 0; j < 3; j++)
                        mesh.ring(end.add(0, .3 + j * .65 + p, 0), X, Z, (1 - p) * (1.1 + j * .18), .12,
                                age * .1, TAU, 32, palette.color, fade * (float)(1 - p));
                    if (detail > 0) for (int i = 1; i <= 5; i++) {
                        Vec3 echo = from.lerp(to, i / 6D);
                        mesh.ribbon(echo.add(0, .1, 0), echo.add(0, 1.9, 0), .45, palette.light,
                                fade * (float)(1 - p) * .17F);
                    }
                }
                case BLADE -> blade(mesh, p, fade, detail);
                case COMMAND -> command(mesh, age, p, fade, detail);
                case BLACK_HOLE -> singularity(mesh, age, p, fade, detail, false, warning);
                case SUN -> singularity(mesh, age, p, fade, detail, true, warning);
                case FAN -> {
                    if (mesh.solid) return;
                    double sweep = Math.toRadians(packet.width()), start = angle - sweep * .5;
                    double radius = packet.radius();
                    groundRing(mesh, from, radius, .45, start, sweep, palette.color, fade * (float)(1 - p));
                    mesh.ring(from.add(0, .8, 0), X, Z, radius, .16, start, sweep, 48,
                            palette.light, fade * (float)(1 - p));
                }
            }
        }
        void charge(BossSkillMesh mesh, double age, int detail) {
            Vec3 from = packet.from();
            double charge = Mth.clamp(age / Math.max(1, packet.windup()), 0, 1);
            int marks = detail == 0 ? 4 : 8;
            double radius = Math.max(.5, packet.radius());
            for (int i = 0; i < marks; i++) {
                double theta = TAU * i / marks;
                Vec3 outer = polar(from, radius * .83, theta), inner = polar(from, radius * .7, theta);
                mesh.ribbon(outer, inner, .07, palette.light, .55F);
            }
            if (detail > 0 && packet.effect() != Effect.BEAM && packet.effect() != Effect.CHAIN && packet.effect() != Effect.BLADE) {
                double r = Math.max(.25, Math.min(radius, 2.5) * (1 - charge));
                mesh.ring(from.add(0, .1 + charge * 1.4, 0), X, Z, r, .045, age * .06, TAU, 32,
                        palette.color, .5F);
            }
        }
        void wave(BossSkillMesh mesh, double progress, float fade, int detail) {
            double radius = packet.radius() * progress;
            if (radius < .2) return;
            double gap = Math.toRadians(packet.gapDegrees()), start = angle + gap * .5, sweep = TAU - gap;
            int pieces = detail == 0 ? 28 : detail == 1 ? 40 : 56;
            if (!mesh.solid) {
                // A low translucent wall follows the same moving annulus used for server hit checks.
                for (int i = 0; i < pieces; i++) {
                    double a = start + sweep * i / pieces, b = start + sweep * (i + 1D) / pieces;
                    Vec3 pa = polar(packet.from(), radius, a), pb = polar(packet.from(), radius, b);
                    mesh.quad(pa, pb, pb.add(0, .48, 0), pa.add(0, .48, 0), palette.color, fade * .32F);
                }
                if (detail > 0) groundRing(mesh, packet.from(), Math.max(.1, radius - .3), .1,
                        start, sweep, palette.color, fade * .5F);
            } else if (packet.theme() == 0 || packet.theme() == 2) {
                int count = detail == 0 ? 12 : 22;
                for (int i = 0; i < count; i++) {
                    double a = start + sweep * (i + .5) / count;
                    Vec3 base = polar(packet.from(), radius, a);
                    double height = packet.theme() == 2 ? .6 : .28;
                    mesh.spike(base, base.add(Math.cos(a) * .22, height, Math.sin(a) * .22),
                            packet.theme() == 2 ? .14 : .24, palette.secondary, 1);
                }
            }
        }
        void eruption(BossSkillMesh mesh, double activeAge, double progress, float fade, int detail) {
            double rise = Math.min(1, Math.max(0, activeAge / 4)) * Math.min(1, (1 - progress) * 4);
            Vec3 base = floor(packet.from());
            if (packet.theme() == 2 || packet.theme() == 1) {
                if (mesh.solid) {
                    int spikes = detail == 0 ? 5 : 9;
                    for (int i = 0; i < spikes; i++) {
                        double a = TAU * i / spikes, r = i == 0 ? 0 : packet.radius() * .55;
                        Vec3 root = polar(packet.from(), r, a);
                        double height = (i == 0 ? 3.3 : 1.5 + .6 * Math.sin(i * 2.1)) * rise;
                        mesh.spike(root, root.add(Math.cos(a) * .28, height, Math.sin(a) * .28),
                                .24 + .1 * rise, packet.theme() == 2 ? palette.light : palette.dark, 1);
                    }
                } else {
                    mesh.ribbon(base, base.add(0, 3.4 * rise, 0), .14, palette.light, fade * .85F);
                    for (int i = 0; i < 5; i++) {
                        double a = TAU * i / 5 + packet.castId() % 12;
                        lineFloor(mesh, packet.from(), packet.from().add(Math.cos(a) * packet.radius(), 0,
                                Math.sin(a) * packet.radius()), .12, palette.color, fade * .8F);
                    }
                }
            } else if (!mesh.solid) {
                if (packet.theme() == 0) {
                    double columnHeight = Math.min(5, freeHeight);
                    Vec3 top = base.add(0, columnHeight, 0), previous = top;
                    for (int i = 1; i <= 7; i++) {
                        Vec3 next = base.add(Math.sin(i * 2.4 + packet.castId()) * .22,
                                columnHeight * (1 - i / 7D), Math.cos(i * 1.8) * .22);
                        mesh.ribbon(previous, next, .36, palette.color, fade * .45F);
                        mesh.ribbon(previous, next, .09, palette.light, fade);
                        previous = next;
                    }
                } else {
                    for (int i = 0; i < (detail == 0 ? 4 : 8); i++) {
                        double a = TAU * i / 8 + activeAge * .045;
                        Vec3 bottom = polar(packet.from(), packet.radius() * .65, a);
                        mesh.ribbon(bottom, base.add(Math.cos(a + .8) * .2, (2.6 + Math.sin(i)) * rise,
                                Math.sin(a + .8) * .2), .42, i % 2 == 0 ? palette.color : palette.secondary, fade * .75F);
                    }
                }
                groundRing(mesh, packet.from(), packet.radius() * Math.min(1, activeAge / 8), .22,
                        0, TAU, palette.light, fade);
            }
        }
        void helix(BossSkillMesh mesh, Vec3 from, Vec3 to, double radius, double phase, int steps, int color, float alpha) {
            Vec3 axis = to.subtract(from).normalize();
            Vec3 u = axis.cross(Math.abs(axis.y) > .9 ? X : Y).normalize(), v = axis.cross(u).normalize();
            for (int arm = 0; arm < 2; arm++) {
                Vec3 previous = null;
                for (int i = 0; i <= steps; i++) {
                    double q = i / (double)steps, theta = phase + q * TAU * 2 + arm * Math.PI;
                    Vec3 at = from.lerp(to, q).add(u.scale(Math.cos(theta) * radius)).add(v.scale(Math.sin(theta) * radius));
                    if (previous != null) mesh.ribbon(previous, at, .055, color, alpha);
                    previous = at;
                }
            }
        }
        void chains(BossSkillMesh mesh, double progress, float fade, int detail) {
            if (mesh.solid) return;
            Vec3 from = packet.from(), to = packet.to(), axis = to.subtract(from).normalize();
            Vec3 u = axis.cross(Math.abs(axis.y) > .9 ? X : Y).normalize(), v = axis.cross(u).normalize();
            // The server resolves the entire chain at activation; the visible links do too.
            double reach = 1;
            int links = Math.min(56, Math.max(4, (int)(from.distanceTo(to) / .45)));
            for (int i = 0; i < links * reach; i++) {
                double q = (i + .5) / links;
                Vec3 center = from.lerp(to, q).add(0, -.2 * Math.sin(Math.PI * q), 0);
                Vec3 across = (i & 1) == 0 ? u : v;
                mesh.ring(center, axis, across, .21, .055, 0, TAU, detail == 0 ? 8 : 12, palette.light, fade * .9F);
            }
            mesh.ribbon(from, from.lerp(to, reach), .035, palette.color, fade);
        }
        void blade(BossSkillMesh mesh, double progress, float fade, int detail) {
            Vec3 center = packet.from().lerp(packet.to(), progress), direction = packet.to().subtract(packet.from()).normalize();
            Vec3 side = new Vec3(-direction.z, 0, direction.x).normalize();
            if (side.lengthSqr() < .1) side = X;
            double half = Math.max(.4, packet.width() * .5);
            if (mesh.solid) {
                Vec3 left = center.add(side.scale(half)), right = center.subtract(side.scale(half));
                Vec3 tip = center.add(direction.scale(.9)), back = center.subtract(direction.scale(.2));
                mesh.quad(left, tip, right, back, palette.light, 1);
                mesh.quad(left.add(0, -.07, 0), back.add(0, -.07, 0), right.add(0, -.07, 0), tip.add(0, -.07, 0), palette.secondary, 1);
            } else {
                mesh.ribbon(center.add(side.scale(half)), center.subtract(side.scale(half)), .18, palette.color, fade);
                int trails = detail == 0 ? 2 : 5;
                for (int i = 1; i <= trails; i++) {
                    Vec3 echo = packet.from().lerp(packet.to(), Math.max(0, progress - i * .018));
                    mesh.ribbon(echo.add(side.scale(half)), echo.subtract(side.scale(half)), .08,
                            palette.color, fade * (trails + 1 - i) / (trails + 1F) * .55F);
                }
            }
        }
        void command(BossSkillMesh mesh, double age, double progress, float fade, int detail) {
            if (mesh.solid) return;
            Vec3 center = packet.from().add(0, 2.6, 0);
            mesh.ring(center, X, Z, 1.1, .16, age * .02, TAU, 40, palette.color, fade);
            for (int i = 0; i < 7; i++) {
                double a = TAU * i / 7 + age * .02;
                Vec3 foot = center.add(Math.cos(a) * 1.1, 0, Math.sin(a) * 1.1);
                Vec3 tip = center.add(Math.cos(a) * 1.25, .7, Math.sin(a) * 1.25);
                mesh.ribbon(foot, tip, .11, palette.light, fade);
            }
            if (detail > 0) {
                groundRing(mesh, packet.from(), packet.radius() * ((age % 30) / 30), .1, 0, TAU, palette.color, fade * .5F);
                for (int i = 0; i < 6; i++) {
                    double a = TAU * i / 6;
                    Vec3 mark = polar(packet.from(), packet.radius() * .75, a);
                    mesh.ribbon(mark, mark.add(0, .7 + .2 * Math.sin(age * .12), 0), .08, palette.light, fade * .5F);
                }
            }
        }
        void singularity(BossSkillMesh mesh, double age, double progress, float fade, int detail, boolean sun, boolean warning) {
            double build = warning ? Math.max(.05, age / Math.max(1, packet.windup())) : 1;
            double collapse = progress > .87 ? Math.max(.03, (1 - progress) / .13) : 1;
            double radius = Math.min(sun ? 1.6 : 1.05, freeHeight * .31) * build * collapse;
            Vec3 center = packet.from().add(0, Math.min(sun ? 4.2 : 1.8, freeHeight * .56), 0);
            if (mesh.solid) {
                mesh.sphere(center, radius, palette.dark, detail == 0 ? 16 : 28, detail == 0 ? 8 : 12);
                return;
            }
            Vec3 inclinedA = new Vec3(1, .32, 0).normalize(), inclinedB = new Vec3(0, -.4, 1).normalize();
            int segments = detail == 0 ? 32 : 64;
            mesh.ring(center, X, Z, radius * 1.65, .18, age * .05, TAU, segments, palette.color, fade * .9F);
            mesh.ring(center, inclinedA, inclinedB, radius * 1.85, .09, -age * .035, TAU, segments, palette.light, fade * .8F);
            // Vertical meridians and short radial rays make the dark core legible from both above and below.
            mesh.ring(center, X, Y, radius * 1.03, .06, 0, TAU, segments, palette.color, fade);
            mesh.ring(center, Z, Y, radius * 1.03, .06, 0, TAU, segments, palette.secondary, fade);
            int arms = detail == 0 ? 4 : sun ? 12 : 8;
            for (int arm = 0; arm < arms; arm++) {
                if (sun) {
                    double a = TAU * arm / arms + age * .012;
                    Vec3 inner = center.add(Math.cos(a) * radius * 1.12, Math.sin(a) * radius * 1.12, 0);
                    Vec3 outer = center.add(Math.cos(a) * radius * (1.5 + .15 * Math.sin(age * .2 + arm)),
                            Math.sin(a) * radius * (1.5 + .15 * Math.sin(age * .2 + arm)), 0);
                    mesh.ribbon(inner, outer, .13, palette.color, fade * .8F);
                }
                Vec3 previous = null;
                for (int i = 0; i <= 12; i++) {
                    double t = i / 12D, r = radius * 1.4 + (packet.radius() - radius * 1.4) * t;
                    double a = TAU * arm / arms + t * 2.2 - age * .065;
                    Vec3 at = packet.from().add(Math.cos(a) * r, (center.y - packet.from().y) * (1 - t) + .15 * t, Math.sin(a) * r);
                    if (previous != null) mesh.ribbon(previous, at, .035 + .02 * (1 - t),
                            arm % 2 == 0 ? palette.color : palette.secondary, fade * (float)(.5 * (1 - t) + .1));
                    previous = at;
                }
            }
            if (!warning && progress > .87) {
                double flashRadius = Math.max(radius, packet.width()) * (progress - .87) / .13;
                groundRing(mesh, packet.from(), flashRadius, .24, 0, TAU, palette.light, fade);
            }
        }
    }
}
