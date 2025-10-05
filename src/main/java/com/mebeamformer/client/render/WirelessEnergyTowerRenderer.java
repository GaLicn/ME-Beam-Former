package com.mebeamformer.client.render;

import com.mebeamformer.Config;
import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import com.mebeamformer.item.LaserBindingTool;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class WirelessEnergyTowerRenderer implements BlockEntityRenderer<WirelessEnergyTowerBlockEntity> {
    
    public WirelessEnergyTowerRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public int getViewDistance() {
        // 扩大渲染距离以确保远距离光束能够被渲染
        // 无线能量塔可能有更远的连接距离
        return 512;
    }

    @Override
    public boolean shouldRenderOffScreen(WirelessEnergyTowerBlockEntity be) {
        // 允许即使BlockEntity不在屏幕上也渲染光束
        // 这对于长距离光束至关重要，因为光束可能在视野中但两端都不在
        var links = be != null ? be.getClientLinks() : null;
        return links != null && !links.isEmpty();
    }

    @Override
    public void render(WirelessEnergyTowerBlockEntity be, float partialTicks, PoseStack poseStack, 
                      MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be == null) return;
        
        Level level = be.getLevel();
        if (level == null) return;
        
        // 检查是否应该渲染光束
        // 如果配置为不总是渲染，则只在玩家手持光束绑定器时渲染
        if (!Config.alwaysRenderBeams) {
            Player player = Minecraft.getInstance().player;
            if (player == null) return;
            
            boolean holdingBindingTool = player.getMainHandItem().getItem() instanceof LaserBindingTool
                    || player.getOffhandItem().getItem() instanceof LaserBindingTool;
            
            if (!holdingBindingTool) return;
        }
        
        // 获取客户端同步的连接目标
        var links = be.getClientLinks();
        if (links == null || links.isEmpty()) return;
        
        // 感应塔底部位置
        BlockPos towerBasePos = be.getBlockPos();
        
        // 感应塔最上面方块的中心位置（底部向上2格）再往上偏移0.25
        float towerTopCenterX = towerBasePos.getX() + 0.5f;
        float towerTopCenterY = towerBasePos.getY() + 2.75f; // 底部 + 2格 + 0.5到中心 + 0.25偏移
        float towerTopCenterZ = towerBasePos.getZ() + 0.5f;
        
        // 红色光束
        float r = 1.0f;
        float g = 0.0f;
        float b = 0.0f;
        
        // 光束粗细
        float thickness = 0.04f;
        
        // 对每个连接的目标渲染光束
        for (BlockPos targetPos : links) {
            // 检查目标是否也是感应塔
            BlockEntity targetBE = level.getBlockEntity(targetPos);
            boolean isTargetTower = targetBE instanceof WirelessEnergyTowerBlockEntity;
            
            // 目标方块中心位置
            float targetCenterX = targetPos.getX() + 0.5f;
            float targetCenterY = targetPos.getY() + 0.5f;
            float targetCenterZ = targetPos.getZ() + 0.5f;
            
            // 如果目标也是感应塔，使用塔顶中心+向上偏移
            if (isTargetTower) {
                targetCenterY = targetPos.getY() + 2.75f; // 底部 + 2格 + 0.5到中心 + 0.25偏移
            }
            
            // 计算从塔顶中心到目标中心的向量
            float vx = targetCenterX - towerTopCenterX;
            float vy = targetCenterY - towerTopCenterY;
            float vz = targetCenterZ - towerTopCenterZ;
            
            // 计算向量长度
            double vectorLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vectorLength <= 0.1) continue; // 距离太短则跳过
            
            // 归一化方向向量
            float normalizedVx = (float) (vx / vectorLength);
            float normalizedVy = (float) (vy / vectorLength);
            float normalizedVz = (float) (vz / vectorLength);
            
            // 起点向连接方向缩进0.25个方块
            float startOffsetX = normalizedVx * 0.25f;
            float startOffsetY = normalizedVy * 0.25f;
            float startOffsetZ = normalizedVz * 0.25f;
            
            // 如果目标也是感应塔，终点也向起点方向缩进0.25个方块
            float endOffsetX = isTargetTower ? (-normalizedVx * 0.25f) : 0f;
            float endOffsetY = isTargetTower ? (-normalizedVy * 0.25f) : 0f;
            float endOffsetZ = isTargetTower ? (-normalizedVz * 0.25f) : 0f;
            
            // 重新计算光束向量（起点缩进+终点缩进）
            float adjustedVx = vx - startOffsetX - endOffsetX;
            float adjustedVy = vy - startOffsetY - endOffsetY;
            float adjustedVz = vz - startOffsetZ - endOffsetZ;
            
            // 保存状态
            poseStack.pushPose();
            
            // BeamRenderHelper.renderColoredBeamVector 会自动 translate(0.5, 0.5, 0.5)
            // 所以我们需要将原点移动到塔顶位置减去(0.5, 0.5, 0.5)，再加上起点缩进偏移
            // 从塔底部方块坐标系来看，塔顶中心+上偏移是 (0.5, 2.75, 0.5)
            // 减去内部的偏移，再加上方向缩进，我们需要 translate(startOffsetX, 2.25 + startOffsetY, startOffsetZ)
            poseStack.translate(startOffsetX, 2.25 + startOffsetY, startOffsetZ);
            
            // 渲染从塔顶到目标的红色光束（使用调整后的向量）
            BeamRenderHelper.renderColoredBeamVector(
                poseStack, buffers, adjustedVx, adjustedVy, adjustedVz, 
                r, g, b, 
                packedLight, packedOverlay, thickness
            );
            
            // 恢复状态
            poseStack.popPose();
        }
    }
} 