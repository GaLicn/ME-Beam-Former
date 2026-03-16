package com.mebeamformer.client.render;

import appeng.api.implementations.blockentities.IColorableBlockEntity;
import appeng.api.parts.IPartHost;
import appeng.api.util.AEColor;
import com.mebeamformer.block.BeamFormerBlock;
import com.mebeamformer.block.OmniBeamFormerBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class BeamRenderHelper {
    private static final ResourceLocation BEAM_TEX =
            new ResourceLocation("minecraft", "textures/entity/beacon_beam.png");

    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final float DEFAULT_THICKNESS = 0.15f;
    private static final float OUTER_SCALE = 1.18f;
    private static final float CORE_SCALE = 0.62f;
    private static final float OUTER_ALPHA = 0.22f;
    private static final float CORE_ALPHA = 0.90f;
    private static final float CORE_WHITE_MIX = 0.16f;
    private static final double BLOCK_BEAM_SHIFT = -0.25d;
    private static final double PART_BEAM_START_SHIFT = 11.0d / 16.0d;
    private static final double PART_BEAM_LENGTH_TRIM = PART_BEAM_START_SHIFT * 2.0d;

    private BeamRenderHelper() {
    }

    public static float[] unpackRgb(int hex) {
        final float scale = 255.0f;
        return new float[]{
                ((hex >> 16) & 0xFF) / scale,
                ((hex >> 8) & 0xFF) / scale,
                (hex & 0xFF) / scale
        };
    }

    @Nullable
    public static float[] unpackColor(@Nullable AEColor color) {
        return color == null ? null : unpackRgb(color.blackVariant);
    }

    @Nullable
    public static float[] getColorableBlockEntityColor(@Nullable BlockEntity blockEntity) {
        if (!(blockEntity instanceof IColorableBlockEntity colorableBlockEntity)) {
            return null;
        }
        return unpackColor(colorableBlockEntity.getColor());
    }

    @Nullable
    public static float[] getPartHostColor(@Nullable IPartHost host) {
        return host == null ? null : unpackColor(host.getColor());
    }

    @Nullable
    public static float[] resolveBlockEndpointColor(Level level, BlockPos endpointPos) {
        BlockState state = level.getBlockState(endpointPos);
        Direction backDirection = getEndpointBackDirection(state);
        if (backDirection == null) {
            return null;
        }
        return getColorableBlockEntityColor(level.getBlockEntity(endpointPos.relative(backDirection)));
    }

    public static float[] blendEndpointColors(@Nullable float[] first, @Nullable float[] second) {
        if (first == null && second == null) {
            return new float[]{1.0f, 1.0f, 1.0f};
        }
        if (first == null) {
            return second.clone();
        }
        if (second == null) {
            return first.clone();
        }

        return new float[]{
                rms(first[0], second[0]),
                rms(first[1], second[1]),
                rms(first[2], second[2])
        };
    }

    public static void renderColoredBeam(
            PoseStack poseStack,
            MultiBufferSource buffers,
            Direction dir,
            double length,
            float r,
            float g,
            float b,
            int light,
            int overlay
    ) {
        renderColoredBeam(poseStack, buffers, dir, length, r, g, b, light, overlay, DEFAULT_THICKNESS);
    }

    public static void renderColoredBeam(
            PoseStack poseStack,
            MultiBufferSource buffers,
            Direction dir,
            double length,
            float r,
            float g,
            float b,
            int light,
            int overlay,
            float thickness
    ) {
        if (length <= 1.0E-6d) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5d, 0.5d, 0.5d);
        poseStack.translate(
                dir.getStepX() * BLOCK_BEAM_SHIFT,
                dir.getStepY() * BLOCK_BEAM_SHIFT,
                dir.getStepZ() * BLOCK_BEAM_SHIFT
        );

        renderBeamPrism(
                poseStack,
                buffers,
                dir.getStepX() * length,
                dir.getStepY() * length,
                dir.getStepZ() * length,
                r,
                g,
                b,
                light,
                overlay,
                thickness
        );

        poseStack.popPose();
    }

    public static void renderColoredBeamForPart(
            PoseStack poseStack,
            MultiBufferSource buffers,
            Direction dir,
            double length,
            float r,
            float g,
            float b,
            int light,
            int overlay
    ) {
        if (length <= 1.0E-6d) {
            return;
        }

        final double visibleLength = Math.max(0.0d, length - PART_BEAM_LENGTH_TRIM);
        if (visibleLength <= 1.0E-6d) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.5d, 0.5d, 0.5d);
        poseStack.translate(
                dir.getStepX() * PART_BEAM_START_SHIFT,
                dir.getStepY() * PART_BEAM_START_SHIFT,
                dir.getStepZ() * PART_BEAM_START_SHIFT
        );

        renderBeamPrism(
                poseStack,
                buffers,
                dir.getStepX() * visibleLength,
                dir.getStepY() * visibleLength,
                dir.getStepZ() * visibleLength,
                r,
                g,
                b,
                light,
                overlay,
                DEFAULT_THICKNESS
        );

        poseStack.popPose();
    }

    public static void renderColoredBeamVector(
            PoseStack poseStack,
            MultiBufferSource buffers,
            float vx,
            float vy,
            float vz,
            float r,
            float g,
            float b,
            int light,
            int overlay,
            float thickness
    ) {
        final double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (length <= 1.0E-6d) {
            return;
        }

        final double invLength = 1.0d / length;

        poseStack.pushPose();
        poseStack.translate(0.5d, 0.5d, 0.5d);
        poseStack.translate(
                vx * invLength * BLOCK_BEAM_SHIFT,
                vy * invLength * BLOCK_BEAM_SHIFT,
                vz * invLength * BLOCK_BEAM_SHIFT
        );

        renderBeamPrism(poseStack, buffers, vx, vy, vz, r, g, b, light, overlay, thickness);
        poseStack.popPose();
    }

    private static void renderBeamPrism(
            PoseStack poseStack,
            MultiBufferSource buffers,
            double vx,
            double vy,
            double vz,
            float r,
            float g,
            float b,
            int light,
            int overlay,
            float thickness
    ) {
        final double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (length <= 1.0E-6d) {
            return;
        }

        final float[] displayColor = normalizeDisplayColor(r, g, b);
        final float[] coreColor = mixWithWhite(displayColor, CORE_WHITE_MIX);
        final float radius = Math.max(0.01f, thickness) * 0.5f;

        PoseStack.Pose last = poseStack.last();
        Matrix4f pose = last.pose();
        Matrix3f normal = last.normal();
        VertexConsumer consumer = buffers.getBuffer(RenderType.beaconBeam(BEAM_TEX, true));
        float v0 = getTextureScroll();
        float v1 = v0 + (float) length * 1.6f;

        emitBeamShell(
                pose,
                normal,
                consumer,
                vx,
                vy,
                vz,
                radius * OUTER_SCALE,
                displayColor,
                OUTER_ALPHA,
                overlay,
                v0,
                v1
        );
        emitBeamShell(
                pose,
                normal,
                consumer,
                vx,
                vy,
                vz,
                radius * CORE_SCALE,
                coreColor,
                CORE_ALPHA,
                overlay,
                v0,
                v1
        );
    }

    private static void emitBeamShell(
            Matrix4f pose,
            Matrix3f normal,
            VertexConsumer consumer,
            double vx,
            double vy,
            double vz,
            float radius,
            float[] color,
            float alpha,
            int overlay,
            float v0,
            float v1
    ) {
        final double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        final double nx = vx / length;
        final double ny = vy / length;
        final double nz = vz / length;

        final double refX;
        final double refY;
        final double refZ;
        if (Math.abs(ny) < 0.92d) {
            refX = 0.0d;
            refY = 1.0d;
            refZ = 0.0d;
        } else {
            refX = 1.0d;
            refY = 0.0d;
            refZ = 0.0d;
        }

        double sideX = refY * nz - refZ * ny;
        double sideY = refZ * nx - refX * nz;
        double sideZ = refX * ny - refY * nx;
        double sideLength = Math.sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ);
        if (sideLength <= 1.0E-6d) {
            return;
        }

        sideX = sideX / sideLength * radius;
        sideY = sideY / sideLength * radius;
        sideZ = sideZ / sideLength * radius;

        double upX = ny * sideZ - nz * sideY;
        double upY = nz * sideX - nx * sideZ;
        double upZ = nx * sideY - ny * sideX;
        double upLength = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        if (upLength <= 1.0E-6d) {
            return;
        }

        upX = upX / upLength * radius;
        upY = upY / upLength * radius;
        upZ = upZ / upLength * radius;

        float sx0 = (float) (-sideX - upX);
        float sy0 = (float) (-sideY - upY);
        float sz0 = (float) (-sideZ - upZ);
        float sx1 = (float) (sideX - upX);
        float sy1 = (float) (sideY - upY);
        float sz1 = (float) (sideZ - upZ);
        float sx2 = (float) (sideX + upX);
        float sy2 = (float) (sideY + upY);
        float sz2 = (float) (sideZ + upZ);
        float sx3 = (float) (-sideX + upX);
        float sy3 = (float) (-sideY + upY);
        float sz3 = (float) (-sideZ + upZ);

        float ex0 = (float) (vx + sx0);
        float ey0 = (float) (vy + sy0);
        float ez0 = (float) (vz + sz0);
        float ex1 = (float) (vx + sx1);
        float ey1 = (float) (vy + sy1);
        float ez1 = (float) (vz + sz1);
        float ex2 = (float) (vx + sx2);
        float ey2 = (float) (vy + sy2);
        float ez2 = (float) (vz + sz2);
        float ex3 = (float) (vx + sx3);
        float ey3 = (float) (vy + sy3);
        float ez3 = (float) (vz + sz3);

        emitFace(pose, normal, consumer, sx0, sy0, sz0, sx1, sy1, sz1, ex1, ey1, ez1, ex0, ey0, ez0,
                color, alpha, overlay, v0, v1);
        emitFace(pose, normal, consumer, sx1, sy1, sz1, sx2, sy2, sz2, ex2, ey2, ez2, ex1, ey1, ez1,
                color, alpha, overlay, v0, v1);
        emitFace(pose, normal, consumer, sx2, sy2, sz2, sx3, sy3, sz3, ex3, ey3, ez3, ex2, ey2, ez2,
                color, alpha, overlay, v0, v1);
        emitFace(pose, normal, consumer, sx3, sy3, sz3, sx0, sy0, sz0, ex0, ey0, ez0, ex3, ey3, ez3,
                color, alpha, overlay, v0, v1);
    }

    private static void emitFace(
            Matrix4f pose,
            Matrix3f normal,
            VertexConsumer consumer,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4,
            float[] color,
            float alpha,
            int overlay,
            float v0,
            float v1
    ) {
        float[] faceNormal = computeNormal(x1, y1, z1, x2, y2, z2, x4, y4, z4);
        quadBothSides(
                pose,
                normal,
                consumer,
                x1,
                y1,
                z1,
                x2,
                y2,
                z2,
                x3,
                y3,
                z3,
                x4,
                y4,
                z4,
                color[0],
                color[1],
                color[2],
                alpha,
                0.0f,
                v0,
                1.0f,
                v1,
                overlay,
                faceNormal[0],
                faceNormal[1],
                faceNormal[2]
        );
    }

    private static float[] normalizeDisplayColor(float r, float g, float b) {
        float cr = clamp01(r);
        float cg = clamp01(g);
        float cb = clamp01(b);

        float max = Math.max(cr, Math.max(cg, cb));
        if (max <= 1.0E-4f) {
            return new float[]{1.0f, 1.0f, 1.0f};
        }

        float min = Math.min(cr, Math.min(cg, cb));
        float saturation = (max - min) / max;
        if (saturation < 0.08f) {
            return new float[]{1.0f, 1.0f, 1.0f};
        }

        float gain = 0.92f / max;
        cr = clamp01(cr * gain);
        cg = clamp01(cg * gain);
        cb = clamp01(cb * gain);

        float average = (cr + cg + cb) / 3.0f;
        float saturationBoost = 1.28f;
        cr = clamp01(average + (cr - average) * saturationBoost);
        cg = clamp01(average + (cg - average) * saturationBoost);
        cb = clamp01(average + (cb - average) * saturationBoost);
        return new float[]{cr, cg, cb};
    }

    private static float[] mixWithWhite(float[] color, float amount) {
        float mix = clamp01(amount);
        return new float[]{
                color[0] * (1.0f - mix) + mix,
                color[1] * (1.0f - mix) + mix,
                color[2] * (1.0f - mix) + mix
        };
    }

    private static float[] computeNormal(
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x4,
            float y4,
            float z4
    ) {
        float ax = x2 - x1;
        float ay = y2 - y1;
        float az = z2 - z1;
        float bx = x4 - x1;
        float by = y4 - y1;
        float bz = z4 - z1;

        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length <= 1.0E-6f) {
            return new float[]{0.0f, 1.0f, 0.0f};
        }

        return new float[]{nx / length, ny / length, nz / length};
    }

    private static float getTextureScroll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return 0.0f;
        }
        return -(mc.level.getGameTime() % 4000L) * 0.05f;
    }

    private static void quadBothSides(
            Matrix4f pose,
            Matrix3f normal,
            VertexConsumer consumer,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4,
            float r,
            float g,
            float b,
            float a,
            float u0,
            float v0,
            float u1,
            float v1,
            int overlay,
            float nx,
            float ny,
            float nz
    ) {
        quad(pose, normal, consumer, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4,
                r, g, b, a, u0, v0, u1, v1, overlay, nx, ny, nz);
        quad(pose, normal, consumer, x4, y4, z4, x3, y3, z3, x2, y2, z2, x1, y1, z1,
                r, g, b, a, u0, v0, u1, v1, overlay, -nx, -ny, -nz);
    }

    private static void quad(
            Matrix4f pose,
            Matrix3f normal,
            VertexConsumer consumer,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float x3,
            float y3,
            float z3,
            float x4,
            float y4,
            float z4,
            float r,
            float g,
            float b,
            float a,
            float u0,
            float v0,
            float u1,
            float v1,
            int overlay,
            float nx,
            float ny,
            float nz
    ) {
        consumer.vertex(pose, x1, y1, z1)
                .color(r, g, b, a)
                .uv(u0, v0)
                .overlayCoords(overlay)
                .uv2(FULL_BRIGHT)
                .normal(normal, nx, ny, nz)
                .endVertex();
        consumer.vertex(pose, x2, y2, z2)
                .color(r, g, b, a)
                .uv(u1, v0)
                .overlayCoords(overlay)
                .uv2(FULL_BRIGHT)
                .normal(normal, nx, ny, nz)
                .endVertex();
        consumer.vertex(pose, x3, y3, z3)
                .color(r, g, b, a)
                .uv(u1, v1)
                .overlayCoords(overlay)
                .uv2(FULL_BRIGHT)
                .normal(normal, nx, ny, nz)
                .endVertex();
        consumer.vertex(pose, x4, y4, z4)
                .color(r, g, b, a)
                .uv(u0, v1)
                .overlayCoords(overlay)
                .uv2(FULL_BRIGHT)
                .normal(normal, nx, ny, nz)
                .endVertex();
    }

    private static float rms(float first, float second) {
        return (float) Math.sqrt((first * first + second * second) * 0.5f);
    }

    private static float clamp01(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }

    @Nullable
    private static Direction getEndpointBackDirection(BlockState state) {
        if (state.getBlock() instanceof BeamFormerBlock) {
            return state.getValue(BeamFormerBlock.FACING).getOpposite();
        }
        if (state.getBlock() instanceof OmniBeamFormerBlock) {
            return state.getValue(OmniBeamFormerBlock.FACING).getOpposite();
        }
        return null;
    }
}
