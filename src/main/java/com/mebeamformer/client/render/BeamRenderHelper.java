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
    private static final ResourceLocation BEAM_TEX = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/white.png");
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

    // 使用内置 RenderType，避免访问受保护的 RenderStateShard 常量造成的编译失败。

    public static void renderColoredBeam(PoseStack poseStack,
                                         MultiBufferSource buffers,
                                         Direction dir,
                                         double length,
                                         float r, float g, float b,
                                         int light, int overlay) {
        // 兼容旧调用：使用默认最小厚度
        renderColoredBeam(poseStack, buffers, dir, length, r, g, b, light, overlay, MIN_THICKNESS);
    }

    // 新增：允许指定光束粗细（单位：方块尺寸，0.0~1.0，典型 0.15~0.30）
    public static void renderColoredBeam(PoseStack poseStack,
                                         MultiBufferSource buffers,
                                         Direction dir,
                                         double length,
                                         float r, float g, float b,
                                         int light, int overlay,
                                         float thickness) {
        if (length <= 0) return;

        // Render a beacon-style translucent beam with animated texture.
        // Use small square cross-section around the center and extend along dir.
        final float half = Math.max(0.001f, thickness) / 2f;
        double dx = 0, dy = 0, dz = 0;
        switch (dir) {
            case NORTH -> dz = -length;
            case SOUTH -> dz = length;
            case WEST -> dx = -length;
            case EAST -> dx = length;
            case DOWN -> dy = -length;
            case UP -> dy = length;
        }

        // HSV 提亮 — 保留原有色相与饱和度，只将明度 V 拉满为 1.0，确保颜色与线缆一致但更亮
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
            // 新模型不是完整方块：将光束起点相对"朝向"向后偏移 0.75 格
            final double BACK_OFFSET = 0.75; // blocks
            final double START = 0.5 - BACK_OFFSET; // -0.25
            poseStack.translate(dir.getStepX() * START, dir.getStepY() * START, dir.getStepZ() * START);

            var last = poseStack.last();
            Matrix4f pose = last.pose();
            var normalMat = last.normal();

            // 统一获取缓冲（发光半透明）
            VertexConsumer vcEmissive = buffers.getBuffer(RenderType.entityTranslucentEmissive(BEAM_TEX));

            // 固定 UV，取消滚动动画，避免出现暗条纹；UV 覆盖整个面即可
            float v0 = 0f;
            float v1 = 1f;

            float aOuter = 0.40f; // legacy param (kept for reference)
            float aInner = 1.00f;  // core opacity

            // Build a cylindrical strip: more segments -> smoother cylinder
            final int SEGMENTS = 20;
            // Neon look: [outer halo, inner halo, colored core, white hot core, tiny white spark]
            final float[] SHELL_SCALE = new float[]{2.6f, 1.9f, 1.20f, 0.95f, 0.60f};
            // 外圈稍淡，核心更实，整体对比更强
            final float[] SHELL_ALPHA = new float[]{0.04f, 0.10f, 0.95f, 1.00f, 1.00f};
            // Axis vector
            float ax = (float) dx;
            float ay = (float) dy;
            float az = (float) dz;

            // Choose two perpendicular unit vectors (u, v) forming the cross-section basis
            float ux, uy, uz, vx, vy, vz;
            switch (dir) {
                case UP, DOWN -> {
                    ux = 1f;
                    uy = 0f;
                    uz = 0f;
                    vx = 0f;
                    vy = 0f;
                    vz = 1f;
                }
                case NORTH, SOUTH -> {
                    ux = 1f;
                    uy = 0f;
                    uz = 0f;
                    vx = 0f;
                    vy = 1f;
                    vz = 0f;
                }
                case WEST, EAST -> {
                    ux = 0f;
                    uy = 1f;
                    uz = 0f;
                    vx = 0f;
                    vy = 0f;
                    vz = 1f;
                }
                default -> {
                    ux = 1f;
                    uy = 0f;
                    uz = 0f;
                    vx = 0f;
                    vy = 1f;
                    vz = 0f;
                }
            }

            // force fullbright for pure color visuals
            int fullLight = 0xF000F0;

            // time-based subtle pulsation for neon sparkle (outer halos only)
            long gt = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0L;
            float flicker = 0.80f + 0.20f * (float) Math.sin(gt * 0.45);

            // 单循环按壳层从外到内绘制；对外圈叠加轻微轴向偏移，缓解贴面；统一使用发光半透明缓冲
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

                    // 轴向偏移：对外圈壳层沿光束轴做极小正向偏移，避免与核心/模型贴面
                    float EPS_SHIFT = 0.01f;
                    float ox = 0f, oy = 0f, oz = 0f;
                    // shell<=1 为外圈光环
                    if (shell <= 1) {
                        ox = dir.getStepX() * EPS_SHIFT;
                        oy = dir.getStepY() * EPS_SHIFT;
                        oz = dir.getStepZ() * EPS_SHIFT;
                    }

                    // end points（添加轴向偏移）
                    float ex0 = sx0 + ax + ox;
                    float ey0 = sy0 + ay + oy;
                    float ez0 = sz0 + az + oz;
                    float ex1 = sx1 + ax + ox;
                    float ey1 = sy1 + ay + oy;
                    float ez1 = sz1 + az + oz;

                    // pure color: avoid lighting variation by using zero normals
                    float nx = 0f, ny = 0f, nz = 0f;

                    // 固定 U，整个条带统一 UV，配合纯白纹理与颜色实现纯色
                    float u0 = 0f;
                    float u1 = 1f;

                    // 渲染目标：统一使用发光半透明缓冲
                    VertexConsumer targetVc = vcEmissive;

                    // white hot cores (shell==3, shell==4) use white; colored core (shell==2) is slightly desaturated toward white for a "bright to white" gradient
                    float cr, cg, cb;
                    if (ACHRO) {
                        // 白/灰输入：所有壳层使用纯白，保证最终视觉为白色
                        cr = 1f;
                        cg = 1f;
                        cb = 1f;
                    } else if (shell >= 3) {
                        cr = 1f;
                        cg = 1f;
                        cb = 1f;
                    } else if (shell == 2) {
                        // mix color with white by 30%
                        float mix = 0.25f; // 降低向白混合，保持颜色饱和
                        cr = r * (1f - mix) + 1f * mix;
                        cg = g * (1f - mix) + 1f * mix;
                        cb = b * (1f - mix) + 1f * mix;
                    } else {
                        cr = r;
                        cg = g;
                        cb = b;
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

        // 结束 renderColoredBeam 方法
    }

    // 专门用于part版的光束渲染，不偏移起点终点
    public static void renderColoredBeamForPart(PoseStack poseStack,
                                               MultiBufferSource buffers,
                                               Direction dir,
                                               double length,
                                               float r, float g, float b,
                                               int light, int overlay) {
        if (length <= 0) return;

        // Render a beacon-style translucent beam with animated texture.
        // Use small square cross-section around the center and extend along dir.
        final float half = Math.max(0.001f, MIN_THICKNESS) / 2f;
        double dx = 0, dy = 0, dz = 0;
        switch (dir) {
            case NORTH -> dz = -length;
            case SOUTH -> dz = length;
            case WEST -> dx = -length;
            case EAST -> dx = length;
            case DOWN -> dy = -length;
            case UP -> dy = length;
        }

        // HSV 提亮 — 保留原有色相与饱和度，只将明度 V 拉满为 1.0，确保颜色与线缆一致但更亮
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
            // Part版本不进行起点偏移，直接从方块表面开始

            var last = poseStack.last();
            Matrix4f pose = last.pose();
            var normalMat = last.normal();

            // 统一获取缓冲（发光半透明）
            VertexConsumer vcEmissive = buffers.getBuffer(RenderType.entityTranslucentEmissive(BEAM_TEX));

            // 固定 UV，取消滚动动画，避免出现暗条纹；UV 覆盖整个面即可
            float v0 = 0f;
            float v1 = 1f;

            float aOuter = 0.40f; // legacy param (kept for reference)
            float aInner = 1.00f;  // core opacity

            // Build a cylindrical strip: more segments -> smoother cylinder
            final int SEGMENTS = 20;
            // Neon look: [outer halo, inner halo, colored core, white hot core, tiny white spark]
            final float[] SHELL_SCALE = new float[]{2.6f, 1.9f, 1.20f, 0.95f, 0.60f};
            // 外圈稍淡，核心更实，整体对比更强
            final float[] SHELL_ALPHA = new float[]{0.04f, 0.10f, 0.95f, 1.00f, 1.00f};
            // Axis vector
            float ax = (float) dx;
            float ay = (float) dy;
            float az = (float) dz;

            // Choose two perpendicular unit vectors (u, v) forming the cross-section basis
            float ux, uy, uz, vx, vy, vz;
            switch (dir) {
                case UP, DOWN -> {
                    ux = 1f;
                    uy = 0f;
                    uz = 0f;
                    vx = 0f;
                    vy = 0f;
                    vz = 1f;
                }
                case NORTH, SOUTH -> {
                    ux = 1f;
                    uy = 0f;
                    uz = 0f;
                    vx = 0f;
                    vy = 1f;
                    vz = 0f;
                }
                case WEST, EAST -> {
                    ux = 0f;
                    uy = 1f;
                    uz = 0f;
                    vx = 0f;
                    vy = 0f;
                    vz = 1f;
                }
                default -> {
                    ux = 1f;
                    uy = 0f;
                    uz = 0f;
                    vx = 0f;
                    vy = 1f;
                    vz = 0f;
                }
            }

            // force fullbright for pure color visuals
            int fullLight = 0xF000F0;

            // time-based subtle pulsation for neon sparkle (outer halos only)
            long gt = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0L;
            float flicker = 0.80f + 0.20f * (float) Math.sin(gt * 0.45);

            // 单循环按壳层从外到内绘制；对外圈叠加轻微轴向偏移，缓解贴面；统一使用发光半透明缓冲
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

                    // 轴向偏移：对外圈壳层沿光束轴做极小正向偏移，避免与核心/模型贴面
                    float EPS_SHIFT = 0.01f;
                    float ox = 0f, oy = 0f, oz = 0f;
                    // shell<=1 为外圈光环
                    if (shell <= 1) {
                        ox = dir.getStepX() * EPS_SHIFT;
                        oy = dir.getStepY() * EPS_SHIFT;
                        oz = dir.getStepZ() * EPS_SHIFT;
                    }

                    // end points（添加轴向偏移）
                    float ex0 = sx0 + ax + ox;
                    float ey0 = sy0 + ay + oy;
                    float ez0 = sz0 + az + oz;
                    float ex1 = sx1 + ax + ox;
                    float ey1 = sy1 + ay + oy;
                    float ez1 = sz1 + az + oz;

                    // pure color: avoid lighting variation by using zero normals
                    float nx = 0f, ny = 0f, nz = 0f;

                    // 固定 U，整个条带统一 UV，配合纯白纹理与颜色实现纯色
                    float u0 = 0f;
                    float u1 = 1f;

                    // 渲染目标：统一使用发光半透明缓冲
                    VertexConsumer targetVc = vcEmissive;

                    // white hot cores (shell==3, shell==4) use white; colored core (shell==2) is slightly desaturated toward white for a "bright to white" gradient
                    float cr, cg, cb;
                    if (ACHRO) {
                        // 白/灰输入：所有壳层使用纯白，保证最终视觉为白色
                        cr = 1f;
                        cg = 1f;
                        cb = 1f;
                    } else if (shell >= 3) {
                        cr = 1f;
                        cg = 1f;
                        cb = 1f;
                    } else if (shell == 2) {
                        // mix color with white by 30%
                        float mix = 0.25f; // 降低向白混合，保持颜色饱和
                        cr = r * (1f - mix) + 1f * mix;
                        cg = g * (1f - mix) + 1f * mix;
                        cb = b * (1f - mix) + 1f * mix;
                    } else {
                        cr = r;
                        cg = g;
                        cb = b;
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

        // 新增：沿任意三维向量渲染光束（用于全向绑定）
        // 输入为世界空间中的向量（以方块中心为起点），向量长度即光束长度。
        public static void renderColoredBeamVector (PoseStack poseStack,
                MultiBufferSource buffers,
        float vx, float vy, float vz,
        float r, float g, float b,
        int light, int overlay,
        float thickness){
            // 长度计算
            double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (len <= 1.0e-6) return;

            final float half = Math.max(0.001f, thickness) / 2f;

            // 颜色 HSV 处理：保持与 renderColoredBeam 一致的视觉策略
            {
                float[] hsv = rgbToHsv(r, g, b);
                float h = hsv[0], s = hsv[1];
                boolean isAchromatic = s < 0.12f || (Math.abs(r - g) < 0.05f && Math.abs(g - b) < 0.05f);
                if (isAchromatic) {
                    r = g = b = 1.0f;
                } else {
                    s = Math.min(1.0f, Math.max(s * COLOR_SAT_BOOST, COLOR_SAT_MIN));
                    float v = 1.0f;
                    float[] rgb = hsvToRgb(h, s, v);
                    r = adjustChannel(rgb[0]);
                    g = adjustChannel(rgb[1]);
                    b = adjustChannel(rgb[2]);
                }

                final boolean ACHRO = isAchromatic;

                poseStack.pushPose();
                poseStack.translate(0.5, 0.5, 0.5);

                // 起点后移，与方块模型相协调
                final double BACK_OFFSET = 0.75; // blocks
                double invLen = 1.0 / len;
                double nx = vx * invLen, ny = vy * invLen, nz = vz * invLen;
                double startShift = 0.5 - BACK_OFFSET; // 与旧实现保持一致
                poseStack.translate(nx * startShift, ny * startShift, nz * startShift);

                var last = poseStack.last();
                Matrix4f pose = last.pose();
                var normalMat = last.normal();

                VertexConsumer vcEmissive = buffers.getBuffer(RenderType.entityTranslucentEmissive(BEAM_TEX));

                float v0 = 0f;
                float v1 = 1f;

                // 壳层参数
                final int SEGMENTS = 20;
                final float[] SHELL_SCALE = new float[]{2.6f, 1.9f, 1.20f, 0.95f, 0.60f};
                final float[] SHELL_ALPHA = new float[]{0.04f, 0.10f, 0.95f, 1.00f, 1.00f};

                // 轴向向量（世界空间）
                float ax = vx;
                float ay = vy;
                float az = vz;

                // 根据任意轴向量构造截面正交基 (u, v)
                // 选择一个非共线参考向量 tmp，先求 u = normalize(axis x tmp)，再 v = normalize(axis x u)
                float tax = ax, tay = ay, taz = az;
                float tLen = (float) Math.sqrt(tax * tax + tay * tay + taz * taz);
                tax /= tLen;
                tay /= tLen;
                taz /= tLen; // 归一化

                float rx = Math.abs(tay) < 0.99f ? 0f : 1f;
                float ry = Math.abs(tay) < 0.99f ? 1f : 0f;
                float rz = 0f;

                // u = axis x ref
                float ux = tay * rz - taz * ry;
                float uy = taz * rx - tax * rz;
                float uz = tax * ry - tay * rx;
                float uLen = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                if (uLen < 1.0e-6f) {
                    // 退化情况下改用另一个参考
                    rx = 0f;
                    ry = 0f;
                    rz = 1f;
                    ux = tay * rz - taz * ry;
                    uy = taz * rx - tax * rz;
                    uz = tax * ry - tay * rx;
                    uLen = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                }
                ux /= uLen;
                uy /= uLen;
                uz /= uLen;
                // v = axis x u
                float vx2 = tay * uz - taz * uy;
                float vy2 = taz * ux - tax * uz;
                float vz2 = tax * uy - tay * ux;
                float vLen = (float) Math.sqrt(vx2 * vx2 + vy2 * vy2 + vz2 * vz2);
                vx2 /= vLen;
                vy2 /= vLen;
                vz2 /= vLen;

                int fullLight = 0xF000F0;
                long gt = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0L;
                float flicker = 0.80f + 0.20f * (float) Math.sin(gt * 0.45);

                for (int shell = 0; shell < SHELL_SCALE.length; shell++) {
                    float radius = half * SHELL_SCALE[shell];
                    float alpha = SHELL_ALPHA[shell];
                    if (shell <= 1) alpha *= flicker;

                    for (int i = 0; i < SEGMENTS; i++) {
                        double a0 = (2 * Math.PI * i) / SEGMENTS;
                        double a1 = (2 * Math.PI * (i + 1)) / SEGMENTS;
                        float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
                        float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

                        // 起点环
                        float sx0 = ux * c0 * radius + vx2 * s0 * radius;
                        float sy0 = uy * c0 * radius + vy2 * s0 * radius;
                        float sz0 = uz * c0 * radius + vz2 * s0 * radius;
                        float sx1 = ux * c1 * radius + vx2 * s1 * radius;
                        float sy1 = uy * c1 * radius + vy2 * s1 * radius;
                        float sz1 = uz * c1 * radius + vz2 * s1 * radius;

                        // 轴向微移，避免贴面
                        float EPS_SHIFT = 0.01f;
                        float ox = (float) (tax * EPS_SHIFT);
                        float oy = (float) (tay * EPS_SHIFT);
                        float oz = (float) (taz * EPS_SHIFT);

                        // 终点环（沿轴向偏移）
                        float ex0 = sx0 + ax + ox;
                        float ey0 = sy0 + ay + oy;
                        float ez0 = sz0 + az + oz;
                        float ex1 = sx1 + ax + ox;
                        float ey1 = sy1 + ay + oy;
                        float ez1 = sz1 + az + oz;

                        float nx0 = 0f, ny0 = 0f, nz0 = 0f;
                        float u0 = 0f, u1 = 1f;

                        float cr, cg, cb;
                        if (ACHRO) {
                            cr = 1f;
                            cg = 1f;
                            cb = 1f;
                        } else if (shell >= 3) {
                            cr = 1f;
                            cg = 1f;
                            cb = 1f;
                        } else if (shell == 2) {
                            float mix = 0.25f;
                            cr = r * (1f - mix) + 1f * mix;
                            cg = g * (1f - mix) + 1f * mix;
                            cb = b * (1f - mix) + 1f * mix;
                        } else {
                            cr = r;
                            cg = g;
                            cb = b;
                        }

                        quadBothSides(pose, normalMat, vcEmissive,
                                sx0, sy0, sz0,
                                sx1, sy1, sz1,
                                ex1, ey1, ez1,
                                ex0, ey0, ez0,
                                cr, cg, cb, alpha,
                                u0, v0, u1, v1,
                                fullLight, overlay, nx0, ny0, nz0);
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
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setUv(u0, v0).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setUv(u1, v0).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(pose, x3, y3, z3).setColor(r, g, b, a).setUv(u1, v1).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(pose, x4, y4, z4).setColor(r, g, b, a).setUv(u0, v1).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
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
