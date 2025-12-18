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
        return 512;
    }

    @Override
    public boolean shouldRenderOffScreen(WirelessEnergyTowerBlockEntity be) {
        var links = be != null ? be.getClientLinks() : null;
        return links != null && !links.isEmpty();
    }

    @Override
    public void render(WirelessEnergyTowerBlockEntity be, float partialTicks, PoseStack poseStack, 
                      MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be == null) return;
        
        Level level = be.getLevel();
        if (level == null) return;
        
        if (!Config.alwaysRenderBeams) {
            Player player = Minecraft.getInstance().player;
            if (player == null) return;
            
            boolean holdingBindingTool = player.getMainHandItem().getItem() instanceof LaserBindingTool
                    || player.getOffhandItem().getItem() instanceof LaserBindingTool;
            
            if (!holdingBindingTool) return;
        }
        
        var links = be.getClientLinks();
        if (links == null || links.isEmpty()) return;
        
        BlockPos towerBasePos = be.getBlockPos();
        
        float towerTopCenterX = towerBasePos.getX() + 0.5f;
        float towerTopCenterY = towerBasePos.getY() + 2.75f; // 底部 + 2格 + 0.5到中心 + 0.25偏移
        float towerTopCenterZ = towerBasePos.getZ() + 0.5f;
        
        float r = 1.0f;
        float g = 0.0f;
        float b = 0.0f;
        
        float thickness = 0.04f;
        
        for (BlockPos targetPos : links) {
            BlockEntity targetBE = level.getBlockEntity(targetPos);
            boolean isTargetTower = targetBE instanceof WirelessEnergyTowerBlockEntity;
            
            float targetCenterX = targetPos.getX() + 0.5f;
            float targetCenterY = targetPos.getY() + 0.5f;
            float targetCenterZ = targetPos.getZ() + 0.5f;
            
            if (isTargetTower) {
                targetCenterY = targetPos.getY() + 2.75f; // 底部 + 2格 + 0.5到中心 + 0.25偏移
            }
            
            float vx = targetCenterX - towerTopCenterX;
            float vy = targetCenterY - towerTopCenterY;
            float vz = targetCenterZ - towerTopCenterZ;
            
            double vectorLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (vectorLength <= 0.1) continue; // 距离太短则跳过
            
            float normalizedVx = (float) (vx / vectorLength);
            float normalizedVy = (float) (vy / vectorLength);
            float normalizedVz = (float) (vz / vectorLength);
            
            float startOffsetX = normalizedVx * 0.25f;
            float startOffsetY = normalizedVy * 0.25f;
            float startOffsetZ = normalizedVz * 0.25f;
            
            float endOffsetX = isTargetTower ? (-normalizedVx * 0.25f) : 0f;
            float endOffsetY = isTargetTower ? (-normalizedVy * 0.25f) : 0f;
            float endOffsetZ = isTargetTower ? (-normalizedVz * 0.25f) : 0f;
            
            float adjustedVx = vx - startOffsetX - endOffsetX;
            float adjustedVy = vy - startOffsetY - endOffsetY;
            float adjustedVz = vz - startOffsetZ - endOffsetZ;
            
            poseStack.pushPose();
            
            poseStack.translate(startOffsetX, 2.25 + startOffsetY, startOffsetZ);
            
            BeamRenderHelper.renderColoredBeamVector(
                poseStack, buffers, adjustedVx, adjustedVy, adjustedVz, 
                r, g, b, 
                packedLight, packedOverlay, thickness
            );
            
            poseStack.popPose();
        }
    }
} 