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
    public void render(OmniBeamFormerBlockEntity be, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be == null) return;
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof OmniBeamFormerBlock)) return;
        Level level = be.getLevel();
        if (level == null) return;

        // 使用BlockEntity的shouldRenderBeam方法来决定是否渲染
        if (!be.shouldRenderBeam()) return;

        // 颜色：从任意相邻 IColorableBlockEntity 读取最鲜艳变体，否则白色
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
        
        // 获取方块朝向
        Direction facing = state.getValue(OmniBeamFormerBlock.FACING);
        
        // 厚度与普通光束成型器一致，稍稍调细
        float thickness = 0.08f;
        for (BlockPos t : targets) {
            // 获取目标方块的朝向
            BlockState targetState = level.getBlockState(t);
            Direction targetFacing = Direction.SOUTH; // 默认朝向
            
            // 检查目标方块是否有FACING属性
            if (targetState.getBlock() instanceof BeamFormerBlock) {
                targetFacing = targetState.getValue(BeamFormerBlock.FACING);
            } else if (targetState.getBlock() instanceof OmniBeamFormerBlock) {
                targetFacing = targetState.getValue(OmniBeamFormerBlock.FACING);
            }
            
            // 计算目标方块中心点向其face方向偏移0.25个方块后的位置
            float targetX = (float) t.getX() + 0.5f + targetFacing.getStepX() * 0.25f;
            float targetY = (float) t.getY() + 0.5f + targetFacing.getStepY() * 0.25f;
            float targetZ = (float) t.getZ() + 0.5f + targetFacing.getStepZ() * 0.25f;
            
            // 计算起点位置：当前方块中心点向face方向偏移0.25个方块
            float startX = (float) pos.getX() + 0.5f + facing.getStepX() * 0.25f;
            float startY = (float) pos.getY() + 0.5f + facing.getStepY() * 0.25f;
            float startZ = (float) pos.getZ() + 0.5f + facing.getStepZ() * 0.25f;
            
            // 计算从起点到终点的向量
            float vx = targetX - startX;
            float vy = targetY - startY;
            float vz = targetZ - startZ;
            
            // 计算向量长度用于归一化
            double vectorLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vectorLength <= 0.2) continue; // 如果距离太短，跳过这条光束
            
            // 归一化向量
            float normalizedVx = (float) (vx / vectorLength);
            float normalizedVy = (float) (vy / vectorLength);
            float normalizedVz = (float) (vz / vectorLength);
            
            // 起点向终点方向缩进0.15个方块
            float adjustedStartX = startX + normalizedVx * 0.2f;
            float adjustedStartY = startY + normalizedVy * 0.2f;
            float adjustedStartZ = startZ + normalizedVz * 0.2f;
            
            // 终点向光束方向延伸0.1个方块
            float adjustedTargetX = targetX + normalizedVx * 0.1f;
            float adjustedTargetY = targetY + normalizedVy * 0.1f;
            float adjustedTargetZ = targetZ + normalizedVz * 0.1f;
            
            // 重新计算调整后的向量
            float adjustedVx = adjustedTargetX - adjustedStartX;
            float adjustedVy = adjustedTargetY - adjustedStartY;
            float adjustedVz = adjustedTargetZ - adjustedStartZ;
            
            // 保存当前的PoseStack状态
            poseStack.pushPose();
            
            // 将渲染原点移动到调整后的起点位置（相对于方块中心）
            poseStack.translate(
                adjustedStartX - (pos.getX() + 0.5f), 
                adjustedStartY - (pos.getY() + 0.5f), 
                adjustedStartZ - (pos.getZ() + 0.5f)
            );
            
            BeamRenderHelper.renderColoredBeamVector(poseStack, buffers, adjustedVx, adjustedVy, adjustedVz, r, g, b, packedLight, packedOverlay, thickness);
            
            // 恢复PoseStack状态
            poseStack.popPose();
        }
    }
}
