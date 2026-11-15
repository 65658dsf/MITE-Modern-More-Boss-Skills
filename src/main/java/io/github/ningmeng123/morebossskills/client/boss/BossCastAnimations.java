package io.github.ningmeng123.morebossskills.client.boss;

import io.github.ningmeng123.mite.client.BetterCombatMobAnimations;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload;
import io.github.ningmeng123.morebossskills.entity.boss.BossSkillPayload.Effect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/** Temporary cast keyframes sampled from the authoritative spell clock. @author NingMeng123 */
public final class BossCastAnimations {
    public static final int RELEASE_TICKS = 8;
    public static final int RECOVERY_TICKS = 10;
    private static final Deque<RenderScope> SCOPES = new ArrayDeque<>();
    private static final Map<Mob, RenderSnapshot> SNAPSHOTS = new WeakHashMap<>();
    private static final boolean AUDIT_ORDINARY_MODELS = Boolean.getBoolean("modern_ite.bossPresentationAudit");
    private static final EmfAccess EMF = EmfAccess.find();
    private static final ClassValue<List<Field>> ARMOR_MODELS = new ClassValue<>() {
        @Override protected List<Field> computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Class<?> current = type; current != null; current = current.getSuperclass())
                for (Field field : current.getDeclaredFields())
                    if (!Modifier.isStatic(field.getModifiers()) && HumanoidModel.class.isAssignableFrom(field.getType())
                            && field.trySetAccessible()) fields.add(field);
            return List.copyOf(fields);
        }
    };

    private BossCastAnimations() {}
    public enum Stage { WINDUP, RELEASE, SUSTAIN, RECOVERY }
    public record CastState(Effect effect, Stage stage, float age, float progress, float weight, long castId) {}
    public record BonePose(float x, float y, float z, float xRot, float yRot, float zRot) {
        private BonePose(ModelPart part) { this(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot); }
        private void restore(ModelPart part) {
            part.setPos(x, y, z); part.setRotation(xRot, yRot, zRot);
        }
        private float difference(BonePose other) {
            return Math.max(Math.max(Math.max(Math.abs(x - other.x), Math.abs(y - other.y)), Math.abs(z - other.z)),
                    Math.max(Math.max(Math.abs(xRot - other.xRot), Math.abs(yRot - other.yRot)), Math.abs(zRot - other.zRot)));
        }
    }
    public record PoseSnapshot(BonePose head, BonePose hat, BonePose body, BonePose rightArm,
            BonePose leftArm, BonePose rightLeg, BonePose leftLeg) {
        public static PoseSnapshot capture(HumanoidModel<?> model) {
            return new PoseSnapshot(new BonePose(model.head), new BonePose(model.hat), new BonePose(model.body),
                    new BonePose(model.rightArm), new BonePose(model.leftArm), new BonePose(model.rightLeg), new BonePose(model.leftLeg));
        }
        private void restore(HumanoidModel<?> model) {
            head.restore(model.head); hat.restore(model.hat); body.restore(model.body);
            rightArm.restore(model.rightArm); leftArm.restore(model.leftArm);
            rightLeg.restore(model.rightLeg); leftLeg.restore(model.leftLeg);
        }
        public float difference(PoseSnapshot other) {
            return Math.max(Math.max(Math.max(head.difference(other.head), hat.difference(other.hat)), body.difference(other.body)),
                    Math.max(Math.max(rightArm.difference(other.rightArm), leftArm.difference(other.leftArm)),
                            Math.max(rightLeg.difference(other.rightLeg), leftLeg.difference(other.leftLeg))));
        }
    }
    /** Actual render observations, including restoration after armor and emissive passes finish. */
    public record RenderSnapshot(CastState state, PoseSnapshot before, PoseSnapshot posed, PoseSnapshot restored,
            float maxPoseDelta, float restoredError, float armorError, int armorPasses, int emfPasses, long gameTick) {}
    public static RenderSnapshot snapshot(Mob mob) { return SNAPSHOTS.get(mob); }
    public static boolean active(Mob mob, float partialTick) { return state(mob, partialTick) != null; }
    public static double activeUntil(BossSkillPayload packet) {
        return packet.windup() + (channel(packet.effect()) ? packet.duration() : RELEASE_TICKS) + RECOVERY_TICKS;
    }
    public static CastState state(Mob mob, float partialTick) {
        BossSkillPayload packet = BossSkillVisuals.animationFor(mob, partialTick);
        return packet == null ? null : sample(packet, mob.level().getGameTime() - packet.startTick() + partialTick);
    }
    private static boolean channel(Effect effect) { return effect == Effect.BLACK_HOLE || effect == Effect.SUN; }
    private static CastState sample(BossSkillPayload packet, float age) {
        if (age < 0 || age >= activeUntil(packet)) return null;
        float activeAge = age - packet.windup();
        float hold = channel(packet.effect()) ? packet.duration() : RELEASE_TICKS;
        Stage stage = age < packet.windup() ? Stage.WINDUP : activeAge < Math.min(RELEASE_TICKS, hold)
                ? Stage.RELEASE : activeAge < hold ? Stage.SUSTAIN : Stage.RECOVERY;
        float progress = switch (stage) {
            case WINDUP -> age / Math.max(1, packet.windup());
            case RELEASE -> activeAge / Math.max(1, Math.min(RELEASE_TICKS, hold));
            case SUSTAIN -> activeAge / Math.max(1, hold);
            case RECOVERY -> (activeAge - hold) / RECOVERY_TICKS;
        };
        float weight = stage == Stage.RECOVERY ? 1 - smooth(progress) : smooth(age / Math.max(1, Math.min(6, packet.windup())));
        return new CastState(packet.effect(), stage, age, Mth.clamp(progress, 0, 1), weight, packet.castId());
    }
    private static float smooth(float value) { float x = Mth.clamp(value, 0, 1); return x * x * (3 - 2 * x); }

    /** The scope is also pushed for ordinary entities, so nested draws cannot borrow a boss's pose. */
    public static RenderScope begin(EntityModel<?> model, LivingEntity entity, float partial, Iterable<?> layers) {
        RenderScope scope = new RenderScope(model, entity, partial);
        SCOPES.push(scope);
        if (scope.state == null) return scope;
        try {
            BetterCombatMobAnimations.stop(scope.mob);
            scope.preserve(scope.model);
            for (Object layer : layers) {
                if (!(layer instanceof HumanoidArmorLayer<?, ?, ?>)) continue;
                for (Field field : ARMOR_MODELS.get(layer.getClass())) {
                    try {
                        if (field.get(layer) instanceof HumanoidModel<?> armor) scope.preserve(armor);
                    } catch (IllegalAccessException failure) {
                        throw new IllegalStateException("Cannot preserve boss armor pose", failure);
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            scope.close();
            throw failure;
        }
        return scope;
    }

    /** Called after all setupAnim injectors, before the skin, armor and hand layers draw. */
    public static void apply(EntityModel<?> model, LivingEntity entity) {
        RenderScope scope = SCOPES.peek();
        if (scope == null || scope.entity != entity || scope.model != model || scope.model == null) return;
        if (scope.state == null && !AUDIT_ORDINARY_MODELS && !SNAPSHOTS.containsKey(scope.mob)) return;
        scope.before = PoseSnapshot.capture(scope.model);
        if (scope.state != null) {
            KeyPose desired = keyframe(scope.packet, scope.state);
            pose(scope.model, scope.mob, desired, scope.state.weight());
        }
        scope.posed = PoseSnapshot.capture(scope.model);
        if (scope.state != null) scope.remember(scope.model);
    }

    /** Item extensions may select a different shared armor model; save it before their animation callback. */
    public static void preserveArmor(Model model, LivingEntity entity) {
        RenderScope scope = SCOPES.peek();
        if (scope != null && scope.state != null && scope.entity == entity && model instanceof HumanoidModel<?> armor)
            scope.preserve(armor);
    }
    /** NeoForge copies into a shared replacement before setupModelAnimations; preserve its original pose first. */
    public static void preserveArmorCopy(HumanoidModel<?> original, HumanoidModel<?> replacement) {
        RenderScope scope = SCOPES.peek();
        if (scope != null && scope.state != null && scope.saved.containsKey(original.body)) scope.preserve(replacement);
    }
    public static void alignArmor(HumanoidModel<?> armor, LivingEntity entity) {
        RenderScope scope = SCOPES.peek();
        if (scope == null || scope.state == null || scope.entity != entity || scope.posed == null) return;
        scope.posed.restore(armor);
        scope.armorPasses++;
        scope.armorError = Math.max(scope.armorError, scope.posed.difference(PoseSnapshot.capture(armor)));
        scope.remember(armor);
    }

    /** Optional EMF animate TAIL, after Fresh Animations and its compatibility callbacks. */
    public static void afterEmf(Object root) {
        RenderScope scope = SCOPES.peek();
        if (scope == null || scope.state == null || EMF == null || !EMF.isEntity(scope.entity)) return;
        ModelBinding binding = scope.bindings.get(root);
        if (binding == null) return;
        binding.pose.restore(binding.model);
        // FA articulates child bones at draw time. Blend those back to their authored rest pose;
        // the standard humanoid pivots then drive the whole limb, including its pack geometry.
        if (binding.children.isEmpty()) {
            Map<ModelPart, Boolean> roots = new IdentityHashMap<>();
            parts(binding.model).forEach(part -> roots.put(part, true));
            for (ModelPart part : parts(binding.model)) part.getAllParts().forEach(child -> {
                if (roots.containsKey(child) || binding.children.containsKey(child)) return;
                var initial = child.getInitialPose();
                float weight = scope.state.weight();
                child.setPos(Mth.lerp(weight, child.x, initial.x), Mth.lerp(weight, child.y, initial.y), Mth.lerp(weight, child.z, initial.z));
                child.setRotation(Mth.lerp(weight, child.xRot, initial.xRot), Mth.lerp(weight, child.yRot, initial.yRot),
                        Mth.lerp(weight, child.zRot, initial.zRot));
                binding.children.put(child, new BonePose(child));
            });
        } else binding.children.forEach((part, pose) -> pose.restore(part));
        scope.emfPasses++;
        if (binding.model != scope.model)
            scope.armorError = Math.max(scope.armorError, scope.posed.difference(PoseSnapshot.capture(binding.model)));
    }

    private static List<ModelPart> parts(HumanoidModel<?> model) {
        return List.of(model.head, model.hat, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg);
    }
    public static final class RenderScope implements AutoCloseable {
        private final LivingEntity entity;
        private final Mob mob;
        private final HumanoidModel<?> model;
        private final BossSkillPayload packet;
        private final CastState state;
        private final Map<ModelPart, SavedPart> saved = new IdentityHashMap<>();
        private final Map<HumanoidModel<?>, ModelFlags> modelFlags = new IdentityHashMap<>();
        private final Map<Object, ModelBinding> bindings = new IdentityHashMap<>();
        private PoseSnapshot before, posed;
        private float armorError;
        private int armorPasses, emfPasses;
        private boolean closed;
        private RenderScope(EntityModel<?> model, LivingEntity entity, float partial) {
            this.entity = entity;
            this.mob = entity instanceof Mob value ? value : null;
            this.model = model instanceof HumanoidModel<?> value ? value : null;
            packet = mob == null || this.model == null ? null : BossSkillVisuals.animationFor(mob, partial);
            state = packet == null ? null : sample(packet, mob.level().getGameTime() - packet.startTick() + partial);
        }
        private void preserve(HumanoidModel<?> model) {
            modelFlags.computeIfAbsent(model, ModelFlags::new);
            for (ModelPart part : parts(model)) part.getAllParts().forEach(child -> saved.computeIfAbsent(child, SavedPart::new));
        }
        private void remember(HumanoidModel<?> model) {
            Object root = EMF == null ? null : EMF.root(model);
            if (root != null) bindings.put(root, new ModelBinding(model, PoseSnapshot.capture(model)));
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                saved.forEach((part, value) -> value.restore(part));
                modelFlags.forEach((model, flags) -> flags.restore(model));
                if (mob != null && model != null && before != null && posed != null) {
                    float restoredError = 0;
                    for (var entry : saved.entrySet()) restoredError = Math.max(restoredError, entry.getValue().difference(entry.getKey()));
                    SNAPSHOTS.put(mob, new RenderSnapshot(state, before, posed, PoseSnapshot.capture(model),
                            state == null ? 0 : before.difference(posed), restoredError, armorError, armorPasses, emfPasses, mob.level().getGameTime()));
                }
            } finally {
                if (SCOPES.peek() == this) SCOPES.pop(); else SCOPES.remove(this);
            }
        }
    }
    private record ModelFlags(float attack, boolean riding, boolean young, boolean crouching, float swim,
            HumanoidModel.ArmPose left, HumanoidModel.ArmPose right) {
        ModelFlags(HumanoidModel<?> model) {
            this(model.attackTime, model.riding, model.young, model.crouching, model.swimAmount, model.leftArmPose, model.rightArmPose);
        }
        void restore(HumanoidModel<?> model) {
            model.attackTime = attack; model.riding = riding; model.young = young;
            model.crouching = crouching; model.swimAmount = swim; model.leftArmPose = left; model.rightArmPose = right;
        }
    }
    private static final class ModelBinding {
        final HumanoidModel<?> model;
        final PoseSnapshot pose;
        final Map<ModelPart, BonePose> children = new IdentityHashMap<>();
        ModelBinding(HumanoidModel<?> model, PoseSnapshot pose) { this.model = model; this.pose = pose; }
    }
    private record SavedPart(BonePose pose, float xScale, float yScale, float zScale, boolean visible, boolean skipDraw) {
        SavedPart(ModelPart part) { this(new BonePose(part), part.xScale, part.yScale, part.zScale, part.visible, part.skipDraw); }
        void restore(ModelPart part) {
            pose.restore(part); part.xScale = xScale; part.yScale = yScale; part.zScale = zScale;
            part.visible = visible; part.skipDraw = skipDraw;
        }
        float difference(ModelPart part) {
            return Math.max(pose.difference(new BonePose(part)), Math.max(Math.max(Math.abs(xScale - part.xScale),
                    Math.abs(yScale - part.yScale)), Math.max(Math.abs(zScale - part.zScale),
                            visible == part.visible && skipDraw == part.skipDraw ? 0 : 1)));
        }
    }

    /** No optional class linkage: the companion @Pseudo mixin is skipped when EMF is absent. */
    private record EmfAccess(Method rootMethod, Method stateMethod, Method entityMethod) {
        static EmfAccess find() {
            try {
                return new EmfAccess(Class.forName("traben.entity_model_features.models.IEMFModel").getMethod("emf$getEMFRootModel"),
                        Class.forName("traben.entity_model_features.models.animation.state.EMFState").getMethod("state"),
                        Class.forName("traben.entity_model_features.models.animation.state.EMFEntityRenderState").getMethod("emfEntity"));
            } catch (ReflectiveOperationException | LinkageError ignored) { return null; }
        }
        Object root(HumanoidModel<?> model) {
            if (!rootMethod.getDeclaringClass().isInstance(model)) return null;
            try { return rootMethod.invoke(model); }
            catch (ReflectiveOperationException | LinkageError ignored) { return null; }
        }
        boolean isEntity(LivingEntity entity) {
            try { Object state = stateMethod.invoke(null); return state != null && entityMethod.invoke(state) == entity; }
            catch (ReflectiveOperationException | LinkageError ignored) { return false; }
        }
    }

    // Angles use model radians; offsets use model pixels. Main/off bones mirror for left-handed bosses.
    private record Bone(float pitch, float yaw, float roll, float lift, float forward) {
        static final Bone ZERO = new Bone(0, 0, 0, 0, 0);
        static Bone rot(float pitch, float yaw, float roll) { return new Bone(pitch, yaw, roll, 0, 0); }
        Bone mix(Bone other, float t) {
            return new Bone(Mth.lerp(t, pitch, other.pitch), Mth.lerp(t, yaw, other.yaw), Mth.lerp(t, roll, other.roll),
                    Mth.lerp(t, lift, other.lift), Mth.lerp(t, forward, other.forward));
        }
    }
    private record KeyPose(Bone body, Bone main, Bone off, Bone mainLeg, Bone offLeg, float headPitch) {
        KeyPose mix(KeyPose other, float t) {
            return new KeyPose(body.mix(other.body, t), main.mix(other.main, t), off.mix(other.off, t),
                    mainLeg.mix(other.mainLeg, t), offLeg.mix(other.offLeg, t), Mth.lerp(t, headPitch, other.headPitch));
        }
    }
    private static KeyPose keyframe(BossSkillPayload packet, CastState state) {
        KeyPose ready, release;
        switch (packet.effect()) {
            case RING -> {
                if (packet.theme() == 0) {
                    ready = new KeyPose(new Bone(.23F, -.12F, -.06F, 1.8F, 0), Bone.rot(-.8F, -.25F, -.45F),
                            Bone.rot(-.5F, .2F, .45F), new Bone(-1.15F, 0, -.12F, -2.5F, -1), Bone.rot(.18F, 0, .08F), .12F);
                    release = new KeyPose(new Bone(.48F, .08F, .04F, 2.5F, 0), Bone.rot(-.25F, .1F, -.38F),
                            Bone.rot(-.25F, -.1F, .38F), Bone.rot(.25F, 0, -.1F), Bone.rot(.2F, 0, .1F), .22F);
                } else {
                    ready = new KeyPose(Bone.rot(-.12F, -.25F, 0), Bone.rot(-2.5F, -.2F, -.3F),
                            Bone.rot(-2.2F, .3F, .35F), Bone.rot(-.2F, 0, -.12F), Bone.rot(.15F, 0, .12F), -.18F);
                    release = new KeyPose(new Bone(.35F, .2F, 0, 1.5F, 0), Bone.rot(-.65F, -.1F, -.6F),
                            Bone.rot(-.65F, .1F, .6F), Bone.rot(.2F, 0, -.1F), Bone.rot(-.2F, 0, .1F), .12F);
                }
            }
            case MARK -> {
                ready = new KeyPose(Bone.rot(-.1F, -.22F, 0), Bone.rot(-2.55F, -.1F, -.2F), Bone.rot(-.45F, .25F, .4F),
                        Bone.rot(-.12F, 0, -.08F), Bone.rot(.12F, 0, .08F), -.12F);
                release = new KeyPose(new Bone(.3F, .22F, 0, 1, 0), Bone.rot(-.65F, .12F, -.08F), Bone.rot(-.35F, .2F, .5F),
                        Bone.rot(.15F, 0, -.1F), Bone.rot(-.15F, 0, .1F), .25F);
            }
            case CHAIN -> {
                ready = new KeyPose(Bone.rot(.12F, .18F, 0), Bone.rot(-1.55F, -.12F, -.05F), Bone.rot(-.7F, .35F, .25F),
                        Bone.rot(-.25F, 0, -.1F), Bone.rot(.25F, 0, .1F), .05F);
                release = new KeyPose(Bone.rot(-.18F, -.6F, -.05F), new Bone(-.7F, -.55F, -.25F, 0, 1.5F),
                        Bone.rot(-1.1F, .4F, .3F), Bone.rot(.25F, 0, -.12F), Bone.rot(-.25F, 0, .12F), -.05F);
            }
            case BEAM, BLADE, FAN -> {
                ready = new KeyPose(Bone.rot(.1F, -.65F, -.08F), Bone.rot(-2.15F, -.9F, -.5F), Bone.rot(-.75F, .6F, .35F),
                        Bone.rot(-.3F, -.1F, -.12F), Bone.rot(.3F, .1F, .12F), -.05F);
                release = new KeyPose(Bone.rot(.22F, .65F, .1F), Bone.rot(-1.1F, 1.1F, .25F), Bone.rot(-.3F, -.15F, .5F),
                        Bone.rot(.2F, .1F, -.1F), Bone.rot(-.2F, -.1F, .1F), .1F);
            }
            case BLACK_HOLE -> {
                ready = new KeyPose(new Bone(.18F, 0, 0, 1.2F, 0), Bone.rot(-1.35F, -.42F, -.22F), Bone.rot(-1.35F, .42F, .22F),
                        Bone.rot(-.13F, 0, -.16F), Bone.rot(-.13F, 0, .16F), .1F);
                release = new KeyPose(Bone.rot(-.08F, 0, 0), Bone.rot(-1.7F, -.55F, -.6F), Bone.rot(-1.7F, .55F, .6F),
                        Bone.rot(-.08F, 0, -.16F), Bone.rot(-.08F, 0, .16F), -.1F);
            }
            case SUN -> {
                ready = new KeyPose(Bone.rot(-.12F, 0, 0), Bone.rot(-2.65F, -.2F, -.3F), Bone.rot(-2.65F, .2F, .3F),
                        Bone.rot(.08F, 0, -.15F), Bone.rot(.08F, 0, .15F), -.4F);
                release = new KeyPose(Bone.rot(-.18F, 0, 0), Bone.rot(-2.9F, -.16F, -.38F), Bone.rot(-2.9F, .16F, .38F),
                        Bone.rot(.08F, 0, -.15F), Bone.rot(.08F, 0, .15F), -.5F);
            }
            case COMMAND -> {
                ready = new KeyPose(Bone.rot(-.08F, -.2F, 0), Bone.rot(-2.6F, -.15F, -.2F), Bone.rot(-.1F, .15F, .3F),
                        Bone.rot(-.1F, 0, -.12F), Bone.rot(.1F, 0, .12F), -.1F);
                release = new KeyPose(Bone.rot(.08F, .2F, 0), Bone.rot(-1.6F, -.08F, -.1F), Bone.rot(-.2F, .2F, .3F),
                        Bone.rot(.1F, 0, -.12F), Bone.rot(-.1F, 0, .12F), .02F);
            }
            case BLINK -> {
                ready = new KeyPose(new Bone(.48F, -.2F, 0, 2, 0), Bone.rot(-1.1F, .65F, .35F), Bone.rot(-1.1F, -.65F, -.35F),
                        Bone.rot(-.5F, 0, -.1F), Bone.rot(.35F, 0, .1F), .3F);
                release = new KeyPose(Bone.rot(-.08F, .2F, 0), Bone.rot(-.85F, -.45F, -1.05F), Bone.rot(-.85F, .45F, 1.05F),
                        Bone.rot(.15F, 0, -.2F), Bone.rot(-.15F, 0, .2F), -.1F);
            }
            default -> throw new IllegalStateException("Unhandled boss pose " + packet.effect());
        }
        // A small early gathering pose grows into the full anticipation, then snaps through in four ticks.
        if (state.stage() == Stage.WINDUP) {
            KeyPose gathering = new KeyPose(ready.body.mix(Bone.ZERO, .35F), ready.main.mix(Bone.rot(-.5F, -.1F, -.12F), .35F),
                    ready.off.mix(Bone.rot(-.4F, .1F, .12F), .35F), ready.mainLeg.mix(Bone.ZERO, .5F),
                    ready.offLeg.mix(Bone.ZERO, .5F), ready.headPitch * .6F);
            return gathering.mix(ready, smooth((state.progress() - .15F) / .7F));
        }
        if (state.stage() == Stage.RELEASE) return ready.mix(release, smooth(state.progress() * 2));
        return release;
    }

    private static void pose(HumanoidModel<?> model, Mob mob, KeyPose target, float weight) {
        float mirror = mob.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        ModelPart main = mirror > 0 ? model.rightArm : model.leftArm;
        ModelPart off = mirror > 0 ? model.leftArm : model.rightArm;
        ModelPart mainLeg = mirror > 0 ? model.rightLeg : model.leftLeg;
        ModelPart offLeg = mirror > 0 ? model.leftLeg : model.rightLeg;
        set(model.body, target.body, weight, mirror, 0);
        set(main, target.main, weight, mirror, target.body.lift);
        set(off, target.off, weight, mirror, target.body.lift);
        set(mainLeg, target.mainLeg, weight, mirror, 0);
        set(offLeg, target.offLeg, weight, mirror, 0);
        model.head.y += target.body.lift * weight;
        model.head.xRot += target.headPitch * weight;
        model.head.yRot -= target.body.yaw * mirror * weight * .45F;
        model.hat.copyFrom(model.head);
        // Humanoid limbs are siblings: turn shoulder pivots with the torso so equipped hands stay attached.
        float yaw = target.body.yaw * mirror * weight;
        float rightX = model.rightArm.getInitialPose().x, leftX = model.leftArm.getInitialPose().x;
        model.rightArm.x = Mth.lerp(weight, model.rightArm.x, rightX * Mth.cos(yaw));
        model.leftArm.x = Mth.lerp(weight, model.leftArm.x, leftX * Mth.cos(yaw));
        model.rightArm.z += -rightX * Mth.sin(yaw);
        model.leftArm.z += -leftX * Mth.sin(yaw);
    }
    private static void set(ModelPart part, Bone target, float weight, float mirror, float torsoLift) {
        part.xRot = Mth.lerp(weight, part.xRot, target.pitch);
        part.yRot = Mth.lerp(weight, part.yRot, target.yaw * mirror);
        part.zRot = Mth.lerp(weight, part.zRot, target.roll * mirror);
        part.y += (target.lift + torsoLift) * weight;
        part.z += target.forward * weight;
    }
}
