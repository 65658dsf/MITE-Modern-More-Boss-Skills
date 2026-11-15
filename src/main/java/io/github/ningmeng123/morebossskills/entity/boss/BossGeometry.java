package io.github.ningmeng123.morebossskills.entity.boss;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Read-only terrain sampling shared by telegraphs, hits and safe escapes. @author NingMeng123 */
public final class BossGeometry {
    private BossGeometry() {}

    public static Vec3 horizontalDirection(Vec3 from, Vec3 to) {
        Vec3 direction = to.subtract(from).multiply(1, 0, 1);
        return direction.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : direction.normalize();
    }

    public static double horizontalDistanceSquared(Vec3 first, Vec3 second) {
        double x = first.x - second.x, z = first.z - second.z;
        return x * x + z * z;
    }

    public static double distanceToSegment(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from).multiply(1, 0, 1);
        double length = delta.lengthSqr();
        double progress = length < 1.0E-6 ? 0 : Math.clamp(point.subtract(from).dot(delta) / length, 0, 1);
        return Math.sqrt(horizontalDistanceSquared(point, from.add(delta.scale(progress))));
    }

    /** Start beside the feet, never above a tunnel's roof or a higher floor. */
    public static Optional<Vec3> ground(ServerLevel level, Mob caster, Vec3 near) {
        return localFloor(level,caster,near);
    }

    /** Identical client/server sampling for stepped local floors; never a world heightmap. */
    public static Optional<Vec3> localFloor(Level level, Entity caster, Vec3 near) {
        BlockPos column = BlockPos.containing(near);
        if (!level.hasChunkAt(column)) return Optional.empty();
        Vec3 start = near.add(0, .6, 0);
        var hit = level.clip(new ClipContext(start, near.add(0, -2, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != net.minecraft.core.Direction.UP)
            return Optional.empty();
        Vec3 surface = hit.getLocation().add(0, .025, 0);
        if (!level.noCollision(new AABB(surface.x - .08, surface.y, surface.z - .08,
                surface.x + .08, surface.y + .12, surface.z + .08))) return Optional.empty();
        return Optional.of(surface);
    }

    public static Vec3 clippedEnd(ServerLevel level, Mob caster, Vec3 from, Vec3 wanted) {
        var hit = level.clip(new ClipContext(from, wanted, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        return hit.getType() == HitResult.Type.MISS ? wanted : hit.getLocation();
    }

    public static boolean inRing(Vec3 feet, Vec3 center, Vec3 gapDirection, double radius,
                                 double width, double gapDegrees) {
        double distance = Math.sqrt(horizontalDistanceSquared(feet, center));
        if (Math.abs(distance - radius) > width * .5 || Math.abs(feet.y - center.y) > 1.6) return false;
        if (gapDegrees <= 0 || distance < .2) return true;
        Vec3 direction = horizontalDirection(center, feet);
        return direction.dot(gapDirection) < Math.cos(Math.toRadians(gapDegrees * .5));
    }

    public static boolean safeLanding(ServerLevel level, Mob caster, Vec3 position, Vec3 target) {
        if (horizontalDistanceSquared(position, target) < 9 || Math.abs(position.y - target.y) > 4) return false;
        BlockPos feet = BlockPos.containing(position);
        if (!level.hasChunkAt(feet) || !level.getWorldBorder().isWithinBounds(feet)) return false;
        AABB box = caster.getBoundingBox().move(position.subtract(caster.position())).deflate(.01);
        if (!level.noCollision(caster, box) || level.containsAnyLiquid(box)) return false;
        // Test a shallow support slice instead of accepting an air column below the feet.
        if (level.noCollision(new AABB(box.minX, box.minY - .12, box.minZ, box.maxX, box.minY, box.maxZ))) return false;
        for (BlockPos check : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 1, 1))) {
            var state = level.getBlockState(check);
            if (!state.getFluidState().isEmpty() || state.is(BlockTags.FIRE) || state.is(Blocks.CACTUS)
                    || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                    || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) return false;
        }
        return level.getEntities(caster, box, entity -> entity.isAlive() && entity.isPickable()).isEmpty();
    }

    public static Optional<Vec3> escapeLanding(ServerLevel level, Mob caster, Vec3 target) {
        Vec3 away = horizontalDirection(target, caster.position());
        double baseAngle = Math.atan2(away.z, away.x);
        for (int ring = 0; ring < 3; ring++) {
            double radius = 3.5 + ring * 1.5;
            for (int step = 0; step < 16; step++) {
                double angle = baseAngle + step * Math.PI / 8;
                Vec3 candidate = new Vec3(target.x + Math.cos(angle) * radius, target.y,
                        target.z + Math.sin(angle) * radius);
                var floor = ground(level, caster, candidate);
                if (floor.isPresent() && safeLanding(level, caster, floor.get(), target)) return floor;
            }
        }
        return Optional.empty();
    }
}
