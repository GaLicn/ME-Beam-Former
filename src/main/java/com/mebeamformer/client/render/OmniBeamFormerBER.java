package com.mebeamformer.client.render;

import com.mebeamformer.block.OmniBeamFormerBlock;
import com.mebeamformer.block.BeamFormerBlock;
import com.mebeamformer.blockentity.OmniBeamFormerBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class OmniBeamFormerBER implements BlockEntityRenderer<OmniBeamFormerBlockEntity> {
    private static final double OMNI_CORE_CENTER_OFFSET = 3.5d / 16.0d;
    private static final double BLOCK_BEAM_CENTER_OFFSET = 0.25d;
    private static final double VECTOR_RENDER_SHIFT_COMPENSATION = 0.25d;

    public OmniBeamFormerBER(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public boolean shouldRenderOffScreen(OmniBeamFormerBlockEntity be) {
        var targets = be != null ? be.getClientActiveTargets() : null;
        return targets != null && !targets.isEmpty();
    }

    @Override
    public boolean shouldRender(OmniBeamFormerBlockEntity be, Vec3 cameraPos) {
        if (be == null) {
            return false;
        }

        return isWithinViewDistance(this.getRenderBoundingBox(be), cameraPos, this.getViewDistance());
    }

    @Override
    public AABB getRenderBoundingBox(OmniBeamFormerBlockEntity be) {
        return be != null ? be.getRenderBoundingBox() : AABB.INFINITE;
    }

    @Override
    public void render(OmniBeamFormerBlockEntity be, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be == null) return;
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof OmniBeamFormerBlock)) return;
        Level level = be.getLevel();
        if (level == null) return;

        BlockPos pos = be.getBlockPos();
        float[] sourceColor = BeamRenderHelper.resolveBlockEndpointColor(level, pos);

        var targets = be.getClientActiveTargets();
        if (targets == null || targets.isEmpty()) return;
        
        Direction facing = state.getValue(OmniBeamFormerBlock.FACING);
        Vec3 sourceAnchor = getOmniBeamAnchor(pos, facing);
        
        float thickness = 0.08f;
        for (BlockPos t : targets) {
            BlockState targetState = level.getBlockState(t);
            Vec3 targetAnchor = getTargetAnchor(t, targetState);
            Vec3 beamVector = targetAnchor.subtract(sourceAnchor);

            double vectorLength = beamVector.length();
            if (vectorLength <= 0.2) continue;

            Vec3 normalized = beamVector.scale(1.0d / vectorLength);
            Vec3 renderOrigin = sourceAnchor.add(normalized.scale(VECTOR_RENDER_SHIFT_COMPENSATION));

            float[] targetColor = BeamRenderHelper.resolveBlockEndpointColor(level, t);
            float[] beamColor = BeamRenderHelper.blendEndpointColors(sourceColor, targetColor);
            
            poseStack.pushPose();
            
            poseStack.translate(
                renderOrigin.x - (pos.getX() + 0.5d), 
                renderOrigin.y - (pos.getY() + 0.5d), 
                renderOrigin.z - (pos.getZ() + 0.5d)
            );
            
            BeamRenderHelper.renderColoredBeamVector(
                    poseStack,
                    buffers,
                    (float) beamVector.x,
                    (float) beamVector.y,
                    (float) beamVector.z,
                    beamColor[0],
                    beamColor[1],
                    beamColor[2],
                    packedLight,
                    packedOverlay,
                    thickness);
            
            poseStack.popPose();
        }
    }

    private static boolean isWithinViewDistance(AABB bounds, Vec3 cameraPos, double maxDistance) {
        double nearestX = Math.max(bounds.minX, Math.min(cameraPos.x, bounds.maxX));
        double nearestY = Math.max(bounds.minY, Math.min(cameraPos.y, bounds.maxY));
        double nearestZ = Math.max(bounds.minZ, Math.min(cameraPos.z, bounds.maxZ));

        double dx = cameraPos.x - nearestX;
        double dy = cameraPos.y - nearestY;
        double dz = cameraPos.z - nearestZ;
        double maxDistanceSq = maxDistance * maxDistance;
        return dx * dx + dy * dy + dz * dz <= maxDistanceSq;
    }

    private static Vec3 getTargetAnchor(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof BeamFormerBlock) {
            Direction facing = state.getValue(BeamFormerBlock.FACING);
            return getForwardAnchor(pos, facing, BLOCK_BEAM_CENTER_OFFSET);
        }
        if (state.getBlock() instanceof OmniBeamFormerBlock) {
            Direction facing = state.getValue(OmniBeamFormerBlock.FACING);
            return getOmniBeamAnchor(pos, facing);
        }
        return Vec3.atCenterOf(pos);
    }

    private static Vec3 getOmniBeamAnchor(BlockPos pos, Direction facing) {
        return getForwardAnchor(pos, facing, OMNI_CORE_CENTER_OFFSET);
    }

    private static Vec3 getForwardAnchor(BlockPos pos, Direction direction, double offset) {
        return new Vec3(
                pos.getX() + 0.5d + direction.getStepX() * offset,
                pos.getY() + 0.5d + direction.getStepY() * offset,
                pos.getZ() + 0.5d + direction.getStepZ() * offset
        );
    }
}
