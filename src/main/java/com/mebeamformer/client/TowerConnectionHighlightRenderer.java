package com.mebeamformer.client;

import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import com.mebeamformer.item.LaserBindingTool;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.*;

/**
 * 无线能源塔连接目标高亮渲染器（性能优化版）
 * 只高亮激光绑定工具当前选定的能源塔所连接的机器
 */
@Mod.EventBusSubscriber(modid = "me_beam_former", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TowerConnectionHighlightRenderer {
    
    private static final String TAG_SOURCE = "SourcePos";
    private static final String TAG_SOURCE_TYPE = "SourceType";
    private static final String TYPE_TOWER = "tower";
    
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // 在透明物体渲染后绘制高亮（确保可以透过方块看到）
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        Level level = mc.level;
        
        if (player == null || level == null) {
            return;
        }
        
        // 检查玩家手持的激光绑定工具
        ItemStack toolStack = null;
        if (player.getMainHandItem().getItem() instanceof LaserBindingTool) {
            toolStack = player.getMainHandItem();
        } else if (player.getOffhandItem().getItem() instanceof LaserBindingTool) {
            toolStack = player.getOffhandItem();
        }
        
        if (toolStack == null) {
            return;
        }
        
        // 收集当前选定能源塔电网中的所有连接目标
        Set<BlockPos> connectedBlocks = collectConnectedBlocksFromTool(level, toolStack);
        
        if (connectedBlocks.isEmpty()) {
            return;
        }
        
        // 渲染高亮
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        Vec3 cameraPos = event.getCamera().getPosition();
        
        renderHighlights(poseStack, bufferSource, connectedBlocks, cameraPos);
    }
    
    /**
     * 从激光绑定工具获取当前选定能源塔及其电网的所有连接目标
     * 性能优化：只查询选定的塔，不扫描整个区域
     */
    private static Set<BlockPos> collectConnectedBlocksFromTool(Level level, ItemStack toolStack) {
        Set<BlockPos> connectedBlocks = new HashSet<>();
        
        // 检查工具是否有绑定的源
        CompoundTag tag = toolStack.getTag();
        if (tag == null || !tag.contains(TAG_SOURCE)) {
            return connectedBlocks;
        }
        
        // 检查源类型是否是能源塔
        String sourceType = tag.getString(TAG_SOURCE_TYPE);
        if (!TYPE_TOWER.equals(sourceType)) {
            return connectedBlocks;
        }
        
        // 获取源塔的位置
        CompoundTag sourceTag = tag.getCompound(TAG_SOURCE);
        BlockPos sourcePos = new BlockPos(
            sourceTag.getInt("x"),
            sourceTag.getInt("y"),
            sourceTag.getInt("z")
        );
        
        // 获取源塔的方块实体
        BlockEntity sourceBE = level.getBlockEntity(sourcePos);
        if (!(sourceBE instanceof WirelessEnergyTowerBlockEntity sourceTower)) {
            return connectedBlocks;
        }
        
        // 收集这个塔电网中的所有连接（包括塔到塔、塔到机器）
        collectTowerNetwork(level, sourceTower, connectedBlocks, new HashSet<>());
        
        return connectedBlocks;
    }
    
    /**
     * 递归收集塔电网中的所有连接目标（排除感应塔本身）
     * 
     * @param level 世界
     * @param tower 当前塔
     * @param connectedBlocks 收集的连接目标（输出，只包含机器）
     * @param visitedTowers 已访问的塔（防止循环）
     */
    private static void collectTowerNetwork(Level level, WirelessEnergyTowerBlockEntity tower,
                                           Set<BlockPos> connectedBlocks, Set<BlockPos> visitedTowers) {
        BlockPos towerPos = tower.getBlockPos();
        
        // 防止重复访问
        if (!visitedTowers.add(towerPos)) {
            return;
        }
        
        // 获取这个塔的所有连接
        List<BlockPos> links = tower.getClientLinks();
        if (links == null || links.isEmpty()) {
            return;
        }
        
        for (BlockPos targetPos : links) {
            // 检查目标是否是能源塔
            BlockEntity targetBE = level.getBlockEntity(targetPos);
            
            if (targetBE instanceof WirelessEnergyTowerBlockEntity targetTower) {
                // 目标是感应塔：不高亮，但递归收集它的连接
                collectTowerNetwork(level, targetTower, connectedBlocks, visitedTowers);
            } else {
                // 目标是普通机器：添加到高亮列表
                connectedBlocks.add(targetPos);
            }
        }
    }
    
    /**
     * 渲染彩虹高亮边框
     */
    private static void renderHighlights(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                        Set<BlockPos> blocks, Vec3 cameraPos) {
        
        poseStack.pushPose();
        
        // 偏移到相机位置
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        
        // 获取渲染缓冲
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.lines());
        
        Matrix4f pose = poseStack.last().pose();
        
        // 时间用于彩虹效果
        long time = System.currentTimeMillis();
        
        for (BlockPos pos : blocks) {
            // 为每个方块计算彩虹颜色（基于位置和时间）
            float hue = ((pos.getX() * 3 + pos.getY() * 5 + pos.getZ() * 7 + time / 50) % 360) / 360.0f;
            int rgb = hsvToRgb(hue, 1.0f, 1.0f);
            
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            
            // 渲染方块边框
            AABB aabb = new AABB(pos).inflate(0.002); // 稍微扩大一点避免Z-fighting
            LevelRenderer.renderLineBox(poseStack, buffer, aabb, 
                    red / 255.0f, green / 255.0f, blue / 255.0f, 1.0f);
        }
        
        poseStack.popPose();
        
        // 确保渲染
        bufferSource.endBatch(RenderType.lines());
    }
    
    /**
     * HSV转RGB
     * @param h 色相 (0-1)
     * @param s 饱和度 (0-1)
     * @param v 明度 (0-1)
     * @return RGB颜色（打包为int）
     */
    private static int hsvToRgb(float h, float s, float v) {
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        
        float r, g, b;
        switch (i % 6) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        
        int red = (int) (r * 255);
        int green = (int) (g * 255);
        int blue = (int) (b * 255);
        
        return (red << 16) | (green << 8) | blue;
    }
}

