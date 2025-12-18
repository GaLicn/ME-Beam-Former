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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import appeng.api.implementations.blockentities.IColorableBlockEntity;
import appeng.api.util.AEColor;

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
    public void render(OmniBeamFormerBlockEntity be, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be == null) return;
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof OmniBeamFormerBlock)) return;
        Level level = be.getLevel();
        if (level == null) return;

        float r = 1f, g = 1f, b = 1f;
        BlockPos pos = be.getBlockPos();
        for (Direction d : Direction.values()) {
            BlockEntity adj = level.getBlockEntity(pos.relative(d));
            if (adj instanceof IColorableBlockEntity cbe) {
                AEColor color = cbe.getColor();
                if (color != null) {
                    int hex = color.blackVariant;
                    float scale = 255f;
                    r = ((hex >> 16) & 0xFF) / scale;
                    g = ((hex >> 8) & 0xFF) / scale;
                    b = (hex & 0xFF) / scale;
                    break;
                }
            }
        }

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
            
            poseStack.pushPose();
            
            poseStack.translate(
                adjustedStartX - (pos.getX() + 0.5f), 
                adjustedStartY - (pos.getY() + 0.5f), 
                adjustedStartZ - (pos.getZ() + 0.5f)
            );
            
            BeamRenderHelper.renderColoredBeamVector(poseStack, buffers, adjustedVx, adjustedVy, adjustedVz, r, g, b, packedLight, packedOverlay, thickness);
            
            poseStack.popPose();
        }
    }
}
