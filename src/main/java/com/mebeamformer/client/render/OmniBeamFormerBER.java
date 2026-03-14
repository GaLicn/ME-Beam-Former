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
        
        float thickness = 0.08f;
        for (BlockPos t : targets) {
            BlockState targetState = level.getBlockState(t);
            Direction targetFacing = Direction.SOUTH; // 默认朝向
            
            if (targetState.getBlock() instanceof BeamFormerBlock) {
                targetFacing = targetState.getValue(BeamFormerBlock.FACING);
            } else if (targetState.getBlock() instanceof OmniBeamFormerBlock) {
                targetFacing = targetState.getValue(OmniBeamFormerBlock.FACING);
            }
            
            float targetX = (float) t.getX() + 0.5f + targetFacing.getStepX() * 0.25f;
            float targetY = (float) t.getY() + 0.5f + targetFacing.getStepY() * 0.25f;
            float targetZ = (float) t.getZ() + 0.5f + targetFacing.getStepZ() * 0.25f;
            
            float startX = (float) pos.getX() + 0.5f + facing.getStepX() * 0.25f;
            float startY = (float) pos.getY() + 0.5f + facing.getStepY() * 0.25f;
            float startZ = (float) pos.getZ() + 0.5f + facing.getStepZ() * 0.25f;
            
            float vx = targetX - startX;
            float vy = targetY - startY;
            float vz = targetZ - startZ;
            
            double vectorLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vectorLength <= 0.2) continue;
            
            float normalizedVx = (float) (vx / vectorLength);
            float normalizedVy = (float) (vy / vectorLength);
            float normalizedVz = (float) (vz / vectorLength);
            
            float adjustedStartX = startX + normalizedVx * 0.2f;
            float adjustedStartY = startY + normalizedVy * 0.2f;
            float adjustedStartZ = startZ + normalizedVz * 0.2f;
            
            float adjustedTargetX = targetX + normalizedVx * 0.1f;
            float adjustedTargetY = targetY + normalizedVy * 0.1f;
            float adjustedTargetZ = targetZ + normalizedVz * 0.1f;
            
            float adjustedVx = adjustedTargetX - adjustedStartX;
            float adjustedVy = adjustedTargetY - adjustedStartY;
            float adjustedVz = adjustedTargetZ - adjustedStartZ;

            float[] targetColor = BeamRenderHelper.resolveBlockEndpointColor(level, t);
            float[] beamColor = BeamRenderHelper.blendEndpointColors(sourceColor, targetColor);
            
            poseStack.pushPose();
            
            poseStack.translate(
                adjustedStartX - (pos.getX() + 0.5f), 
                adjustedStartY - (pos.getY() + 0.5f), 
                adjustedStartZ - (pos.getZ() + 0.5f)
            );
            
            BeamRenderHelper.renderColoredBeamVector(
                    poseStack,
                    buffers,
                    adjustedVx,
                    adjustedVy,
                    adjustedVz,
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
}
