package com.mebeamformer.client.render;

import com.mebeamformer.block.BeamFormerBlock;
import com.mebeamformer.blockentity.BeamFormerBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class BeamFormerBER implements BlockEntityRenderer<BeamFormerBlockEntity> {
    public BeamFormerBER(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(BeamFormerBlockEntity te, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (te == null) return;
        BlockState state = te.getBlockState();
        if (!(state.getBlock() instanceof BeamFormerBlock)) return;
        Direction dir = state.getValue(BeamFormerBlock.FACING);
        int len = Math.max(0, te.getBeamLength());
        if (len <= 0) return;

        Level level = te.getLevel();
        BlockPos pos = te.getBlockPos();
        if (level == null) return;
        if (!isPathClearForRender(level, pos, dir, len)) return;

        float r = 1f, g = 1f, b = 1f; // 先用白色光束
        com.mebeamformer.client.render.BeamRenderHelper.renderColoredBeam(
                poseStack, buffers, dir, len, r, g, b, packedLight, packedOverlay);
    }

    private boolean isPathClearForRender(Level level, BlockPos start, Direction dir, int length) {
        BlockPos cur = start;
        for (int i = 0; i < length; i++) {
            cur = cur.relative(dir);
            var state = level.getBlockState(cur);
            if (state.canOcclude() && !state.isAir()) {
                var other = level.getBlockEntity(cur);
                if (other instanceof BeamFormerBlockEntity) {
                    Direction otherFacing = level.getBlockState(cur).getValue(BeamFormerBlock.FACING);
                    if (i == length - 1 && otherFacing == dir.getOpposite()) {
                        return true; // 允许终点为对向的成型器
                    }
                }
                return false;
            }
            var be = level.getBlockEntity(cur);
            if (be instanceof BeamFormerBlockEntity) {
                Direction otherFacing = level.getBlockState(cur).getValue(BeamFormerBlock.FACING);
                if (otherFacing == dir) return false; // 不穿过同向
            }
        }
        return true;
    }
}
