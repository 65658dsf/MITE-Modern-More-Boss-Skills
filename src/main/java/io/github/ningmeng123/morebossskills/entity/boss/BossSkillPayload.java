package io.github.ningmeng123.morebossskills.entity.boss;

import io.github.ningmeng123.morebossskills.MoreBossSkills;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** One authoritative cast description; clients animate it without per-particle traffic. @author NingMeng123 */
@EventBusSubscriber(modid = MoreBossSkills.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public record BossSkillPayload(ResourceLocation dimension, int ownerId, UUID ownerUuid, long castId,
        Effect effect, int theme, Vec3 from, Vec3 to, long startTick, int windup, int duration,
        float radius, float width, float gapDegrees, boolean cancelled) implements CustomPacketPayload {
    public enum Effect { RING, MARK, BEAM, CHAIN, BLINK, BLADE, COMMAND, BLACK_HOLE, SUN, FAN }
    public static final Type<BossSkillPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MoreBossSkills.MOD_ID, "boss_skill"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BossSkillPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public BossSkillPayload decode(RegistryFriendlyByteBuf buffer) {
            return new BossSkillPayload(buffer.readResourceLocation(), buffer.readVarInt(), buffer.readUUID(),
                    buffer.readLong(), buffer.readEnum(Effect.class), buffer.readVarInt(), readVector(buffer),
                    readVector(buffer), buffer.readLong(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readBoolean());
        }
        @Override public void encode(RegistryFriendlyByteBuf buffer, BossSkillPayload value) {
            buffer.writeResourceLocation(value.dimension()); buffer.writeVarInt(value.ownerId());
            buffer.writeUUID(value.ownerUuid()); buffer.writeLong(value.castId()); buffer.writeEnum(value.effect());
            buffer.writeVarInt(value.theme()); writeVector(buffer, value.from()); writeVector(buffer, value.to());
            buffer.writeLong(value.startTick()); buffer.writeVarInt(value.windup()); buffer.writeVarInt(value.duration());
            buffer.writeFloat(value.radius()); buffer.writeFloat(value.width()); buffer.writeFloat(value.gapDegrees());
            buffer.writeBoolean(value.cancelled());
        }
    };
    private static Vec3 readVector(RegistryFriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
    private static void writeVector(RegistryFriendlyByteBuf buffer, Vec3 vector) {
        buffer.writeDouble(vector.x); buffer.writeDouble(vector.y); buffer.writeDouble(vector.z);
    }
    @Override public Type<BossSkillPayload> type() { return TYPE; }
    public boolean valid() {
        return ownerId >= 0 && theme >= 0 && theme <= 3 && finite(from) && finite(to)
                && from.distanceToSqr(to) <= 256D * 256D && windup >= 0 && windup <= 1200
                && duration > 0 && duration <= 2400 && Float.isFinite(radius) && radius >= 0 && radius <= 96
                && Float.isFinite(width) && width >= 0 && width <= (effect == Effect.FAN ? 360 : 96)
                && Float.isFinite(gapDegrees) && gapDegrees >= 0 && gapDegrees <= 360;
    }
    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z)
                && Math.abs(point.x) <= 3.1E7 && Math.abs(point.y) <= 3.1E7 && Math.abs(point.z) <= 3.1E7;
    }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(TYPE, STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (payload.valid()) io.github.ningmeng123.morebossskills.client.boss.BossSkillVisuals.receive(payload);
        }));
    }
    public static void send(ServerLevel level, Mob caster, long castId, Effect effect, int theme,
            Vec3 from, Vec3 to, int windup, int duration, float radius, float width, float gapDegrees) {
        var payload = new BossSkillPayload(level.dimension().location(), caster.getId(), caster.getUUID(), castId,
                effect, theme, from, to, level.getGameTime(), windup, duration, radius, width, gapDegrees, false);
        if (!payload.valid()) return;
        for (var player : level.players()) {
            if (player.connection != null && player.connection.hasChannel(TYPE)
                    && Math.min(player.distanceToSqr(from), player.distanceToSqr(to)) <= 128D * 128D)
                PacketDistributor.sendToPlayer(player, payload);
        }
    }
    public static void sendCancel(ServerLevel level, Mob caster) {
        var payload = new BossSkillPayload(level.dimension().location(), caster.getId(), caster.getUUID(), -1,
                Effect.RING, 0, caster.position(), caster.position(), level.getGameTime(), 0, 1, 0, 0, 0, true);
        for (var player : level.players())
            if (player.connection != null && player.connection.hasChannel(TYPE)) PacketDistributor.sendToPlayer(player, payload);
    }
}
