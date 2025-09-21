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
    private static final ResourceLocation BEAM_TEX = ResourceLocation.withDefaultNamespace("textures/entity/beacon_beam.png");

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

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);

        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(BEAM_TEX));

        var last = poseStack.last();
        Matrix4f pose = last.pose();
        var normalMat = last.normal();

        // UV animation like beacon
        long gameTime = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0L;
        double scroll = (gameTime % 40L) / 40.0; // 0..1
        float v0 = (float) (scroll);
        float v1 = v0 + (float) length; // stretch with length

        float aOuter = 0.25f; // slight outer glow
        float aInner = 0.7f;  // inner core

        // We build 4 faces forming a prism around the main axis
        switch (dir) {
            case NORTH, SOUTH -> {
                float x1 = -half, x2 = +half, y1 = -half, y2 = +half;
                float z0 = 0f, z1f = (float) dz;
                // two orthogonal pairs to make square
                quad(pose, normalMat, vc, x1, y1, z0, x2, y1, z0, x2, y2, z0, x1, y2, z0, r, g, b, aInner, 0f, v0, 1f, v1, light, overlay, 0f, 0f, -1f);
                quad(pose, normalMat, vc, x2, y1, z1f, x1, y1, z1f, x1, y2, z1f, x2, y2, z1f, r, g, b, aInner, 0f, v0, 1f, v1, light, overlay, 0f, 0f, 1f);
                quad(pose, normalMat, vc, x1, y1, z1f, x1, y1, z0, x1, y2, z0, x1, y2, z1f, r, g, b, aOuter, 0f, v0, 1f, v1, light, overlay, -1f, 0f, 0f);
                quad(pose, normalMat, vc, x2, y1, z0, x2, y1, z1f, x2, y2, z1f, x2, y2, z0, r, g, b, aOuter, 0f, v0, 1f, v1, light, overlay, 1f, 0f, 0f);
            }
            case WEST, EAST -> {
                float z1 = -half, z2 = +half, y1 = -half, y2 = +half;
                float x0 = 0f, x1f = (float) dx;
                quad(pose, normalMat, vc, x0, y1, z1, x0, y1, z2, x0, y2, z2, x0, y2, z1, r, g, b, aInner, 0f, v0, 1f, v1, light, overlay, -1f, 0f, 0f);
                quad(pose, normalMat, vc, x1f, y1, z2, x1f, y1, z1, x1f, y2, z1, x1f, y2, z2, r, g, b, aInner, 0f, v0, 1f, v1, light, overlay, 1f, 0f, 0f);
                quad(pose, normalMat, vc, x1f, y1, z1, x0, y1, z1, x0, y2, z1, x1f, y2, z1, r, g, b, aOuter, 0f, v0, 1f, v1, light, overlay, 0f, 0f, -1f);
                quad(pose, normalMat, vc, x0, y1, z2, x1f, y1, z2, x1f, y2, z2, x0, y2, z2, r, g, b, aOuter, 0f, v0, 1f, v1, light, overlay, 0f, 0f, 1f);
            }
            case DOWN, UP -> {
                float x1 = -half, x2 = +half, z1 = -half, z2 = +half;
                float y0 = 0f, y1f = (float) dy;
                quad(pose, normalMat, vc, x1, y0, z1, x2, y0, z1, x2, y0, z2, x1, y0, z2, r, g, b, aInner, 0f, v0, 1f, v1, light, overlay, 0f, -1f, 0f);
                quad(pose, normalMat, vc, x2, y1f, z1, x1, y1f, z1, x1, y1f, z2, x2, y1f, z2, r, g, b, aInner, 0f, v0, 1f, v1, light, overlay, 0f, 1f, 0f);
                quad(pose, normalMat, vc, x1, y1f, z1, x1, y0, z1, x1, y0, z2, x1, y1f, z2, r, g, b, aOuter, 0f, v0, 1f, v1, light, overlay, -1f, 0f, 0f);
                quad(pose, normalMat, vc, x2, y0, z1, x2, y1f, z1, x2, y1f, z2, x2, y0, z2, r, g, b, aOuter, 0f, v0, 1f, v1, light, overlay, 1f, 0f, 0f);
            }
        }

        poseStack.popPose();
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
}
