package com.mebeamformer.client.render;

import com.mebeamformer.Config;
import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import com.mebeamformer.item.LaserBindingTool;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.joml.Matrix3f;

/**
 * 无线能源感应塔渲染器（简化版）
 * 使用固定宽度的红线渲染连接，性能优化
 */
public class WirelessEnergyTowerRenderer implements BlockEntityRenderer<WirelessEnergyTowerBlockEntity> {
    
    // 线条宽度（方块单位）
    private static final float LINE_WIDTH = 0.05f;
    
    // 使用纯白纹理配合颜色
    private static final ResourceLocation WHITE_TEXTURE = new ResourceLocation("minecraft", "textures/misc/white.png");
    
    public WirelessEnergyTowerRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public int getViewDistance() {
        // 扩大渲染距离以确保远距离连接线能够被渲染
        return 256;
    }

    @Override
    public boolean shouldRenderOffScreen(WirelessEnergyTowerBlockEntity be) {
        // 允许即使BlockEntity不在屏幕上也渲染连接线
        var links = be != null ? be.getClientLinks() : null;
        return links != null && !links.isEmpty();
    }

    @Override
    public void render(WirelessEnergyTowerBlockEntity be, float partialTicks, PoseStack poseStack, 
                      MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be == null) return;
        
        Level level = be.getLevel();
        if (level == null) return;
        
        // 检查是否应该渲染连接线
        // 如果配置为不总是渲染，则只在玩家手持激光绑定器时渲染
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
        
        // 感应塔顶部中心位置（底部向上2.5格，到达塔顶中心）
        float towerTopX = towerBasePos.getX() + 0.5f;
        float towerTopY = towerBasePos.getY() + 2.5f;
        float towerTopZ = towerBasePos.getZ() + 0.5f;
        
        // 对每个连接的目标渲染红线
        for (BlockPos targetPos : links) {
            // 检查目标是否也是感应塔
            BlockEntity targetBE = level.getBlockEntity(targetPos);
            boolean isTargetTower = targetBE instanceof WirelessEnergyTowerBlockEntity;
            
            // 计算目标中心位置
            float targetX = targetPos.getX() + 0.5f;
            float targetY = targetPos.getY() + 0.5f;
            float targetZ = targetPos.getZ() + 0.5f;
            
            // 如果目标也是感应塔，使用塔顶中心
            if (isTargetTower) {
                targetY = targetPos.getY() + 2.5f;
            }
            
            // 计算相对位置（相对于当前塔底部）
            float startX = towerTopX - towerBasePos.getX();
            float startY = towerTopY - towerBasePos.getY();
            float startZ = towerTopZ - towerBasePos.getZ();
            
            float endX = targetX - towerBasePos.getX();
            float endY = targetY - towerBasePos.getY();
            float endZ = targetZ - towerBasePos.getZ();
            
            // 渲染固定宽度的线条
            renderLine(poseStack, buffers, startX, startY, startZ, endX, endY, endZ, 
                      1.0f, 0.0f, 0.0f, 1.0f, packedLight, packedOverlay);
        }
    }
    
    /**
     * 渲染一条固定宽度的线条（使用四边形）
     * 
     * @param r 红色分量 (0-1)
     * @param g 绿色分量 (0-1)
     * @param b 蓝色分量 (0-1)
     * @param a 透明度 (0-1)
     */
    private void renderLine(PoseStack poseStack, MultiBufferSource buffers,
                           float x1, float y1, float z1, 
                           float x2, float y2, float z2,
                           float r, float g, float b, float a,
                           int light, int overlay) {
        
        poseStack.pushPose();
        
        // 计算线条方向向量
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        
        if (length < 0.001) {
            poseStack.popPose();
            return;
        }
        
        // 归一化方向向量
        float nx = (float) (dx / length);
        float ny = (float) (dy / length);
        float nz = (float) (dz / length);
        
        // 计算两个垂直于线条的向量（用于构建四边形）
        // 选择一个不平行于线条的参考向量
        float rx, ry, rz;
        if (Math.abs(ny) < 0.99) {
            // 使用向上向量
            rx = 0;
            ry = 1;
            rz = 0;
        } else {
            // 如果线条接近垂直，使用侧向向量
            rx = 1;
            ry = 0;
            rz = 0;
        }
        
        // 计算垂直向量1：叉积 (n × r)
        float v1x = ny * rz - nz * ry;
        float v1y = nz * rx - nx * rz;
        float v1z = nx * ry - ny * rx;
        
        // 归一化
        double v1len = Math.sqrt(v1x * v1x + v1y * v1y + v1z * v1z);
        v1x /= v1len;
        v1y /= v1len;
        v1z /= v1len;
        
        // 计算垂直向量2：叉积 (n × v1)
        float v2x = ny * v1z - nz * v1y;
        float v2y = nz * v1x - nx * v1z;
        float v2z = nx * v1y - ny * v1x;
        
        // 缩放到线宽的一半
        float hw = LINE_WIDTH / 2.0f;
        v1x *= hw;
        v1y *= hw;
        v1z *= hw;
        v2x *= hw;
        v2y *= hw;
        v2z *= hw;
        
        // 获取渲染缓冲
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE_TEXTURE));
        
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        
        // 全亮光照
        int fullLight = 0xF000F0;
        
        // 渲染四个面（四边形盒子，朝向相机）
        // 面1
        addQuad(buffer, pose, normal,
               x1 - v1x, y1 - v1y, z1 - v1z,
               x1 + v1x, y1 + v1y, z1 + v1z,
               x2 + v1x, y2 + v1y, z2 + v1z,
               x2 - v1x, y2 - v1y, z2 - v1z,
               r, g, b, a, fullLight, overlay);
        
        // 面2
        addQuad(buffer, pose, normal,
               x1 - v2x, y1 - v2y, z1 - v2z,
               x1 + v2x, y1 + v2y, z1 + v2z,
               x2 + v2x, y2 + v2y, z2 + v2z,
               x2 - v2x, y2 - v2y, z2 - v2z,
               r, g, b, a, fullLight, overlay);
        
        poseStack.popPose();
    }
    
    /**
     * 添加一个四边形
     * 使用零法线向量避免光照计算，确保颜色恒定
     */
    private void addQuad(VertexConsumer buffer, Matrix4f pose, Matrix3f normal,
                        float x1, float y1, float z1,
                        float x2, float y2, float z2,
                        float x3, float y3, float z3,
                        float x4, float y4, float z4,
                        float r, float g, float b, float a,
                        int light, int overlay) {
        
        // 使用零法线向量 (0, 0, 0) 避免任何光照计算
        // 这样颜色将完全由顶点颜色和光照级别决定，不受视角影响
        
        // 正面
        buffer.vertex(pose, x1, y1, z1).color(r, g, b, a).uv(0, 0).overlayCoords(overlay).uv2(light).normal(normal, 0, 0, 0).endVertex();
        buffer.vertex(pose, x2, y2, z2).color(r, g, b, a).uv(1, 0).overlayCoords(overlay).uv2(light).normal(normal, 0, 0, 0).endVertex();
        buffer.vertex(pose, x3, y3, z3).color(r, g, b, a).uv(1, 1).overlayCoords(overlay).uv2(light).normal(normal, 0, 0, 0).endVertex();
        buffer.vertex(pose, x4, y4, z4).color(r, g, b, a).uv(0, 1).overlayCoords(overlay).uv2(light).normal(normal, 0, 0, 0).endVertex();
        
        // 背面
        buffer.vertex(pose, x4, y4, z4).color(r, g, b, a).uv(0, 1).overlayCoords(overlay).uv2(light).normal(normal, 0, 0, 0).endVertex();
        buffer.vertex(pose, x3, y3, z3).color(r, g, b, a).uv(1, 1).overlayCoords(overlay).uv2(light).normal(normal, 0, 0, 0).endVertex();
        buffer.vertex(pose, x2, y2, z2).color(r, g, b, a).uv(1, 0).overlayCoords(overlay).uv2(light).normal(normal, 0, 0, 0).endVertex();
        buffer.vertex(pose, x1, y1, z1).color(r, g, b, a).uv(0, 0).overlayCoords(overlay).uv2(light).normal(normal, 0, 0, 0).endVertex();
    }
} 