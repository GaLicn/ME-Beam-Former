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
import static com.mebeamformer.block.BeamFormerBlock.STATUS;
import appeng.api.implementations.blockentities.IColorableBlockEntity;
import appeng.api.util.AEColor;

public class BeamFormerBER implements BlockEntityRenderer<BeamFormerBlockEntity> {
    public BeamFormerBER(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(BeamFormerBlockEntity te, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (te == null) return;
        BlockState state = te.getBlockState();
        if (!(state.getBlock() instanceof BeamFormerBlock)) return;
        Direction dir = state.getValue(BeamFormerBlock.FACING);
        int len = Math.max(0, te.getBeamLength());
        boolean beaming = state.getValue(STATUS) == BeamFormerBlock.Status.BEAMING;
        double visibleLen = len > 0 ? len : (beaming ? 0.5d : 0.0d);
        if (visibleLen <= 0) return;

        Level level = te.getLevel();
        BlockPos pos = te.getBlockPos();
        if (level == null) return;
        int checkLen = len > 0 ? len : 1; // 相邻时也检查 1 格
        if (!isPathClearForRender(level, pos, dir, checkLen)) return;

        // 颜色：尝试从背面相邻的线缆总线获取 AE 颜色（中等变体），否则默认为白色
        float r = 1f, g = 1f, b = 1f;
        BlockPos back = pos.relative(dir.getOpposite());
        var backBe = level.getBlockEntity(back);
        if (backBe instanceof IColorableBlockEntity cbe) {
            AEColor color = cbe.getColor();
            if (color != null) {
                int hex = color.mediumVariant;
                float scale = 255f;
                r = ((hex >> 16) & 0xFF) / scale;
                g = ((hex >> 8) & 0xFF) / scale;
                b = (hex & 0xFF) / scale;
            }
        }

        // 方块形态使用更粗的光束（例如 0.32f）
        float thickness = 0.32f;
        com.mebeamformer.client.render.BeamRenderHelper.renderColoredBeam(
                poseStack, buffers, dir, visibleLen, r, g, b, packedLight, packedOverlay, thickness);
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
