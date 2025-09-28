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
        if (targets == null || targets.isEmpty()) return;

        // 获取方块朝向
        Direction facing = state.getValue(OmniBeamFormerBlock.FACING);
        
        // 厚度与普通光束成型器一致
        float thickness = 0.10f;
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
            
            // 保存当前的PoseStack状态
            poseStack.pushPose();
            
            // 将渲染原点移动到起点位置（相对于方块中心）
            poseStack.translate(
                startX - (pos.getX() + 0.5f), 
                startY - (pos.getY() + 0.5f), 
                startZ - (pos.getZ() + 0.5f)
            );
            
            BeamRenderHelper.renderColoredBeamVector(poseStack, buffers, vx, vy, vz, r, g, b, packedLight, packedOverlay, thickness);
            
            // 恢复PoseStack状态
            poseStack.popPose();
        }
    }
}
