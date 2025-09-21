package com.mebeamformer.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Direction;

public final class BeamRenderHelper {
    private static final float MIN_THICKNESS = 0.15f;

    private BeamRenderHelper() {}

    public static void renderColoredBeam(PoseStack poseStack,
                                         MultiBufferSource buffers,
                                         Direction dir,
                                         double length,
                                         float r, float g, float b,
                                         int light, int overlay) {
        if (length <= 0) return;

        var consumer = buffers.getBuffer(RenderType.lines());
        poseStack.pushPose();
        // center to middle of the part
        poseStack.translate(0.5, 0.5, 0.5);

        double sx = MIN_THICKNESS, sy = MIN_THICKNESS, sz = MIN_THICKNESS;
        double dx = 0, dy = 0, dz = 0;

        switch (dir) {
            case NORTH -> { dz = -length; sz = Math.max(MIN_THICKNESS, (float) length + 0.25f); }
            case SOUTH -> { dz =  length; sz = Math.max(MIN_THICKNESS, (float) length + 0.25f); }
            case WEST  -> { dx = -length; sx = Math.max(MIN_THICKNESS, (float) length + 0.25f); }
            case EAST  -> { dx =  length; sx = Math.max(MIN_THICKNESS, (float) length + 0.25f); }
            case DOWN  -> { dy = -length; sy = Math.max(MIN_THICKNESS, (float) length + 0.25f); }
            case UP    -> { dy =  length; sy = Math.max(MIN_THICKNESS, (float) length + 0.25f); }
        }

        // draw centered box from origin to (dx,dy,dz) with sizes sx/sy/sz
        double minX = Math.min(0, dx) - sx / 2f;
        double maxX = Math.max(0, dx) + sx / 2f;
        double minY = Math.min(0, dy) - sy / 2f;
        double maxY = Math.max(0, dy) + sy / 2f;
        double minZ = Math.min(0, dz) - sz / 2f;
        double maxZ = Math.max(0, dz) + sz / 2f;

        LevelRenderer.renderLineBox(
                poseStack,
                consumer,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                r, g, b, 1.0f
        );

        poseStack.popPose();
    }
}
