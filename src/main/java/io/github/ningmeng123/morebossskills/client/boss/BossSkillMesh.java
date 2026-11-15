package io.github.ningmeng123.morebossskills.client.boss;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

/** Small untextured world meshes: no external assets, entities or block mutations. @author NingMeng123 */
final class BossSkillMesh {
    static final int MAX_VERTICES = 180_000;
    private final BufferBuilder buffer;
    private final Vec3 camera;
    final boolean solid;
    int vertices, boundaryVertices;
    boolean boundary;
    BossSkillMesh(BufferBuilder buffer, Vec3 camera, boolean solid) {
        this.buffer = buffer; this.camera = camera; this.solid = solid;
    }
    void triangle(Vec3 a, Vec3 b, Vec3 c, int color, float alpha) {
        if (vertices + 3 > MAX_VERTICES || alpha <= .001F || !finite(a) || !finite(b) || !finite(c)) return;
        vertex(a, color, alpha); vertex(b, color, alpha); vertex(c, color, alpha);
        vertices += 3; if (boundary) boundaryVertices += 3;
    }
    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
    void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, float alpha) {
        triangle(a, b, c, color, alpha); triangle(a, c, d, color, alpha);
    }
    private void vertex(Vec3 position, int color, float alpha) {
        buffer.addVertex((float)(position.x - camera.x), (float)(position.y - camera.y), (float)(position.z - camera.z))
                .setColor(color >> 16 & 255, color >> 8 & 255, color & 255, Math.min(255, (int)(alpha * 255)));
    }
    void ribbon(Vec3 from, Vec3 to, double width, int color, float alpha) {
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1E-8) return;
        Vec3 normal = axis.cross(camera.subtract(from)).normalize().scale(width * .5);
        if (normal.lengthSqr() < 1E-8) normal = new Vec3(width * .5, 0, 0);
        quad(from.subtract(normal), from.add(normal), to.add(normal), to.subtract(normal), color, alpha);
    }
    void ring(Vec3 center, Vec3 axisA, Vec3 axisB, double radius, double thickness,
            double start, double sweep, int segments, int color, float alpha) {
        double inner = Math.max(0, radius - thickness * .5), outer = radius + thickness * .5;
        for (int i = 0; i < segments; i++) {
            double a = start + sweep * i / segments, b = start + sweep * (i + 1D) / segments;
            quad(point(center, axisA, axisB, inner, a), point(center, axisA, axisB, outer, a),
                    point(center, axisA, axisB, outer, b), point(center, axisA, axisB, inner, b), color, alpha);
        }
    }
    static Vec3 point(Vec3 center, Vec3 axisA, Vec3 axisB, double radius, double angle) {
        return center.add(axisA.scale(Math.cos(angle) * radius)).add(axisB.scale(Math.sin(angle) * radius));
    }
    void spike(Vec3 base, Vec3 tip, double radius, int color, float alpha) {
        Vec3 direction = tip.subtract(base).normalize();
        Vec3 side = direction.cross(Math.abs(direction.y) > .9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 cross = direction.cross(side).normalize();
        for (int face = 0; face < 5; face++) {
            Vec3 a = point(base, side, cross, radius, Math.PI * 2 * face / 5);
            Vec3 b = point(base, side, cross, radius, Math.PI * 2 * (face + 1) / 5);
            triangle(a, b, tip, shade(color, .55 + .45 * face / 4), alpha);
        }
    }
    void sphere(Vec3 center, double radius, int color, int slices, int stacks) {
        for (int y = 0; y < stacks; y++) {
            double v0 = Math.PI * y / stacks, v1 = Math.PI * (y + 1D) / stacks;
            for (int x = 0; x < slices; x++) {
                double u0 = Math.PI * 2 * x / slices, u1 = Math.PI * 2 * (x + 1D) / slices;
                quad(spherePoint(center, radius, u0, v0), spherePoint(center, radius, u1, v0),
                        spherePoint(center, radius, u1, v1), spherePoint(center, radius, u0, v1),
                        shade(color, .5 + .5 * Math.sin(v0)), 1);
            }
        }
    }
    private static Vec3 spherePoint(Vec3 center, double radius, double yaw, double pitch) {
        return center.add(radius * Math.sin(pitch) * Math.cos(yaw), radius * Math.cos(pitch),
                radius * Math.sin(pitch) * Math.sin(yaw));
    }
    static int shade(int color, double factor) {
        return Math.min(255, (int)((color >> 16 & 255) * factor)) << 16
                | Math.min(255, (int)((color >> 8 & 255) * factor)) << 8
                | Math.min(255, (int)((color & 255) * factor));
    }
    static final class Types extends RenderType {
        static final RenderType SOLID = create("more_boss_skills_solid", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.TRIANGLES, 65536, false, false,
                CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setCullState(NO_CULL)
                        .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_DEPTH_WRITE)
                        .setOutputState(PARTICLES_TARGET).createCompositeState(false));
        static final RenderType GLOW = create("more_boss_skills_glow", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.TRIANGLES, 65536, false, false,
                CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setCullState(NO_CULL)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_WRITE).setOutputState(PARTICLES_TARGET).createCompositeState(false));
        private Types(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                boolean crumbling, boolean sorting, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sorting, setup, clear);
        }
    }
}
