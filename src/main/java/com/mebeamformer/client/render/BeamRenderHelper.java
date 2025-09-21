package com.mebeamformer.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public final class BeamRenderHelper {
    private static final float MIN_THICKNESS = 0.15f;
    // 使用纯白纹理，配合顶点颜色实现纯色光束
    private static final ResourceLocation BEAM_TEX = ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    // 颜色调优：提高亮度与对比度（可按需微调）
    private static final float COLOR_BRIGHTNESS = 1.30f; // >1 提亮，<1 变暗
    private static final float COLOR_CONTRAST   = 1.05f; // 适度对比以避免去饱和发灰
    // 饱和度增强，防止 mediumVariant 偏灰
    private static final float COLOR_SAT_BOOST  = 1.40f; // >1 提升饱和度
    private static final float COLOR_SAT_MIN    = 0.60f; // 最低饱和度下限

    // 将颜色通道限定在 0..1，并施加对比度/亮度曲线
    private static float adjustChannel(float c) {
        // 对比度：以 0.5 为中心扩展/压缩
        c = (c - 0.5f) * COLOR_CONTRAST + 0.5f;
        // 亮度：整体增益
        c = c * COLOR_BRIGHTNESS;
        // 裁剪
        if (c < 0f) c = 0f;
        if (c > 1f) c = 1f;
        return c;
    }

    private BeamRenderHelper() {}

    public static void renderColoredBeam(PoseStack poseStack,
                                         MultiBufferSource buffers,
                                         Direction dir,
                                         double length,
                                         float r, float g, float b,
                                         int light, int overlay) {
        if (length <= 0) return;

        // Render a beacon-style translucent beam with animated texture.
        // Use small square cross-section around the center and extend along dir.
        final float half = MIN_THICKNESS / 2f;
        double dx = 0, dy = 0, dz = 0;
        switch (dir) {
            case NORTH -> dz = -length;
            case SOUTH -> dz =  length;
            case WEST  -> dx = -length;
            case EAST  -> dx =  length;
            case DOWN  -> dy = -length;
            case UP    -> dy =  length;
        }

        // 方案A：HSV 提亮 — 保留原有色相与饱和度，只将明度 V 拉满为 1.0，确保颜色与线缆一致但更亮
        {
            float[] hsv = rgbToHsv(r, g, b);
            float h = hsv[0], s = hsv[1];
            // 检测近似无色（白/灰），避免被强制上饱和
            boolean isAchromatic = s < 0.12f || (Math.abs(r - g) < 0.05f && Math.abs(g - b) < 0.05f);
            if (isAchromatic) {
                // 对白色/灰色：直接使用纯白，获得干净的白色光束
                r = g = b = 1.0f;
            } else {
                // 彩色：提升饱和度，并设置下限，避免偏灰
                s = Math.min(1.0f, Math.max(s * COLOR_SAT_BOOST, COLOR_SAT_MIN));
                float v = 1.0f; // 拉满明度
                float[] rgb = hsvToRgb(h, s, v);
                r = rgb[0];
                g = rgb[1];
                b = rgb[2];
                // 进一步提升亮度与对比度
                r = adjustChannel(r);
                g = adjustChannel(g);
                b = adjustChannel(b);
            }
            // 将 achromatic 标志在后续着色时使用
            // 通过局部 final 变量捕获
            final boolean ACHRO = isAchromatic;

            poseStack.pushPose();
            poseStack.translate(0.5, 0.5, 0.5);

            // 使用发光渲染类型以获得更“霓虹”的明亮视觉
            VertexConsumer vcEmissive = buffers.getBuffer(RenderType.entityTranslucentEmissive(BEAM_TEX));
            VertexConsumer vcTranslucent = buffers.getBuffer(RenderType.entityTranslucent(BEAM_TEX));

            var last = poseStack.last();
            Matrix4f pose = last.pose();
            var normalMat = last.normal();

            // 固定 UV，取消滚动动画，避免出现暗条纹；UV 覆盖整个面即可
            float v0 = 0f;
            float v1 = 1f;

            float aOuter = 0.40f; // legacy param (kept for reference)
            float aInner = 1.00f;  // core opacity

        // Build a cylindrical strip: more segments -> smoother cylinder
        final int SEGMENTS = 20;
        // Neon look: [outer halo, inner halo, colored core, white hot core, tiny white spark]
        final float[] SHELL_SCALE = new float[] { 2.6f, 1.9f, 1.20f, 0.95f, 0.60f };
        // 外圈稍淡，核心更实，整体对比更强
        final float[] SHELL_ALPHA = new float[] { 0.04f, 0.10f, 0.95f, 1.00f, 1.00f };
        // Axis vector
        float ax = (float) dx;
        float ay = (float) dy;
        float az = (float) dz;

        // Choose two perpendicular unit vectors (u, v) forming the cross-section basis
        float ux, uy, uz, vx, vy, vz;
        switch (dir) {
            case UP, DOWN -> { ux = 1f; uy = 0f; uz = 0f; vx = 0f; vy = 0f; vz = 1f; }
            case NORTH, SOUTH -> { ux = 1f; uy = 0f; uz = 0f; vx = 0f; vy = 1f; vz = 0f; }
            case WEST, EAST -> { ux = 0f; uy = 1f; uz = 0f; vx = 0f; vy = 0f; vz = 1f; }
            default -> { ux = 1f; uy = 0f; uz = 0f; vx = 0f; vy = 1f; vz = 0f; }
        }

        // force fullbright for pure color visuals
        int fullLight = 0xF000F0;

        // time-based subtle pulsation for neon sparkle (outer halos only)
        long gt = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0L;
        float flicker = 0.80f + 0.20f * (float) Math.sin(gt * 0.45);

        for (int shell = 0; shell < SHELL_SCALE.length; shell++) {
            float radius = half * SHELL_SCALE[shell];
            float alpha = SHELL_ALPHA[shell];
            // pulsate only for halos (shell=0,1)
            if (shell <= 1) alpha *= flicker;

            for (int i = 0; i < SEGMENTS; i++) {
                double a0 = (2 * Math.PI * i) / SEGMENTS;
                double a1 = (2 * Math.PI * (i + 1)) / SEGMENTS;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
                float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

                // ring positions at start (center 0,0,0) and end (shifted by axis)
                float sx0 = ux * c0 * radius + vx * s0 * radius;
                float sy0 = uy * c0 * radius + vy * s0 * radius;
                float sz0 = uz * c0 * radius + vz * s0 * radius;
                float sx1 = ux * c1 * radius + vx * s1 * radius;
                float sy1 = uy * c1 * radius + vy * s1 * radius;
                float sz1 = uz * c1 * radius + vz * s1 * radius;

                // end points
                float ex0 = sx0 + ax;
                float ey0 = sy0 + ay;
                float ez0 = sz0 + az;
                float ex1 = sx1 + ax;
                float ey1 = sy1 + ay;
                float ez1 = sz1 + az;

                // pure color: avoid lighting variation by using zero normals
                float nx = 0f, ny = 0f, nz = 0f;

                // 固定 U，整个条带统一 UV，配合纯白纹理与颜色实现纯色
                float u0 = 0f;
                float u1 = 1f;

                // emissive for all shells to keep bright
                VertexConsumer targetVc = vcEmissive;

                // white hot cores (shell==3, shell==4) use white; colored core (shell==2) is slightly desaturated toward white for a "bright to white" gradient
                float cr, cg, cb;
                if (ACHRO) {
                    // 白/灰输入：所有壳层使用纯白，保证最终视觉为白色
                    cr = 1f; cg = 1f; cb = 1f;
                } else if (shell >= 3) {
                    cr = 1f; cg = 1f; cb = 1f;
                } else if (shell == 2) {
                    // mix color with white by 30%
                    float mix = 0.25f; // 降低向白混合，保持颜色饱和
                    cr = r * (1f - mix) + 1f * mix;
                    cg = g * (1f - mix) + 1f * mix;
                    cb = b * (1f - mix) + 1f * mix;
                } else {
                    cr = r; cg = g; cb = b;
                }

                quadBothSides(pose, normalMat, targetVc,
                        sx0, sy0, sz0,
                        sx1, sy1, sz1,
                        ex1, ey1, ez1,
                        ex0, ey0, ez0,
                        cr, cg, cb, alpha,
                        u0, v0, u1, v1,
                        fullLight, overlay, nx, ny, nz);
            }
        }

        poseStack.popPose();
    }
    }

    private static void quad(Matrix4f pose, org.joml.Matrix3f normalMat, VertexConsumer vc,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             float r, float g, float b, float a,
                             float u0, float v0, float u1, float v1,
                             int light, int overlay,
                             float nx, float ny, float nz) {
        // Single-sided quads are fine for beam; texture scrolls along the length.
        vc.vertex(pose, x1, y1, z1).color(r, g, b, a).uv(u0, v0).overlayCoords(overlay).uv2(light).normal(normalMat, nx, ny, nz).endVertex();
        vc.vertex(pose, x2, y2, z2).color(r, g, b, a).uv(u1, v0).overlayCoords(overlay).uv2(light).normal(normalMat, nx, ny, nz).endVertex();
        vc.vertex(pose, x3, y3, z3).color(r, g, b, a).uv(u1, v1).overlayCoords(overlay).uv2(light).normal(normalMat, nx, ny, nz).endVertex();
        vc.vertex(pose, x4, y4, z4).color(r, g, b, a).uv(u0, v1).overlayCoords(overlay).uv2(light).normal(normalMat, nx, ny, nz).endVertex();
    }

    private static void quadBothSides(Matrix4f pose, org.joml.Matrix3f normalMat, VertexConsumer vc,
                                      float x1, float y1, float z1,
                                      float x2, float y2, float z2,
                                      float x3, float y3, float z3,
                                      float x4, float y4, float z4,
                                      float r, float g, float b, float a,
                                      float u0, float v0, float u1, float v1,
                                      int light, int overlay,
                                      float nx, float ny, float nz) {
        quad(pose, normalMat, vc, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, r, g, b, a, u0, v0, u1, v1, light, overlay, nx, ny, nz);
        // reverse order with inverted normal
        quad(pose, normalMat, vc, x4, y4, z4, x3, y3, z3, x2, y2, z2, x1, y1, z1, r, g, b, a, u0, v0, u1, v1, light, overlay, -nx, -ny, -nz);
    }

    // --------- Color helpers: RGB <-> HSV (all channels in 0..1) ---------
    private static float[] rgbToHsv(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h;
        if (delta == 0f) {
            h = 0f;
        } else if (max == r) {
            h = ((g - b) / delta) % 6f;
        } else if (max == g) {
            h = ((b - r) / delta) + 2f;
        } else {
            h = ((r - g) / delta) + 4f;
        }
        h /= 6f;
        if (h < 0f) h += 1f;
        float s = max == 0f ? 0f : (delta / max);
        float v = max;
        return new float[]{h, s, v};
    }

    private static float[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs(((h * 6f) % 2f) - 1f));
        float m = v - c;
        float rf, gf, bf;
        float hp = h * 6f;
        if (hp < 1)      { rf = c; gf = x; bf = 0; }
        else if (hp < 2) { rf = x; gf = c; bf = 0; }
        else if (hp < 3) { rf = 0; gf = c; bf = x; }
        else if (hp < 4) { rf = 0; gf = x; bf = c; }
        else if (hp < 5) { rf = x; gf = 0; bf = c; }
        else             { rf = c; gf = 0; bf = x; }
        return new float[]{rf + m, gf + m, bf + m};
    }
}
