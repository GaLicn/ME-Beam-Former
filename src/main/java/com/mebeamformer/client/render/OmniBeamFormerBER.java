package com.mebeamformer.client.render;

import com.mebeamformer.block.OmniBeamFormerBlock;
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
        
        // 计算朝向偏移量 (0.25个方块)
        float offsetX = facing.getStepX() * 0.25f;
        float offsetY = facing.getStepY() * 0.25f;
        float offsetZ = facing.getStepZ() * 0.25f;

        // 厚度与普通光束成型器一致
        float thickness = 0.10f;
        for (BlockPos t : targets) {
            // 保存当前的PoseStack状态
            poseStack.pushPose();
            
            // 将整个渲染坐标系向朝向方向偏移0.5个方块
            poseStack.translate(offsetX, offsetY, offsetZ);
            
            // 计算从偏移后的方块位置到偏移后的目标位置的向量
            float vx = (float) (t.getX() - pos.getX());
            float vy = (float) (t.getY() - pos.getY());
            float vz = (float) (t.getZ() - pos.getZ());
            
            BeamRenderHelper.renderColoredBeamVector(poseStack, buffers, vx, vy, vz, r, g, b, packedLight, packedOverlay, thickness);
            
            // 恢复PoseStack状态
            poseStack.popPose();
        }
    }
}
