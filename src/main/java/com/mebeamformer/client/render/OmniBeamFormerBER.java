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

        // 颜色：从任意相邻 IColorableBlockEntity 读取，否则白色
        float r = 1f, g = 1f, b = 1f;
        BlockPos pos = be.getBlockPos();
        for (Direction d : Direction.values()) {
            BlockEntity adj = level.getBlockEntity(pos.relative(d));
            if (adj instanceof IColorableBlockEntity cbe) {
                AEColor color = cbe.getColor();
                if (color != null) {
                    int hex = color.mediumVariant;
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

        // 厚度与普通光束成型器一致
        float thickness = 0.10f;
        for (BlockPos t : targets) {
            float vx = (float) (t.getX() - pos.getX());
            float vy = (float) (t.getY() - pos.getY());
            float vz = (float) (t.getZ() - pos.getZ());
            BeamRenderHelper.renderColoredBeamVector(poseStack, buffers, vx, vy, vz, r, g, b, packedLight, packedOverlay, thickness);
        }
    }
}
