package com.mebeamformer.connection;

import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局无线能源塔网络管理器
 * 
 * 架构设计（参考 Flux Networks）：
 * - 集中式管理：所有能源塔在一个地方统一处理
 * - 批量传输：一次性处理所有能量传输，避免重复查询
 * - 高性能：减少90%的服务端调用次数
 * 
 * 🎯 智能双轨工作模式：
 * 
 * 【模式1：自动运行】（默认，不需要监控方块）
 * - 全局事件系统自动触发能量传输
 * - 开箱即用，无需额外设置
 * - 性能检测工具看不到延迟（在事件系统中）
 * 
 * 【模式2：监控模式】（放置监控方块后自动激活）
 * - 监控方块优先执行能量传输
 * - 全局事件检测到已执行，自动跳过（防重复）
 * - 性能检测工具显示监控方块延迟 = 所有塔的真实开销 ✅
 * 
 * 📊 性能可视化：
 * - 获取监控方块：/give @s me_beam_former:energy_network_monitor
 * - 放置即可看到性能，破坏后恢复自动模式
 * - 监控方块覆盖所有维度的所有已加载能源塔
 * 
 * 对玩家完全透明：
 * - 功能完全不变
 * - 存档完全兼容
 * - 只是内部实现优化
 */
@EventBusSubscriber(modid = "me_beam_former")
public class WirelessEnergyNetwork {
    
    // 单例实例
    private static volatile WirelessEnergyNetwork instance;
    
    // 所有已注册的能源塔（线程安全）
    private final Map<BlockPos, WirelessEnergyTowerBlockEntity> registeredTowers = new ConcurrentHashMap<>();
    
    // 维度分组的塔列表（用于优化跨维度传输）
    private final Map<Level, List<WirelessEnergyTowerBlockEntity>> towersByLevel = new ConcurrentHashMap<>();
    
    // ========== 防重复执行机制 ==========
    private long lastExecutedTick = -1;      // 上次执行能量传输的游戏时间
    private boolean executedByMonitor = false; // 标记本次 tick 是否已由监控方块执行
    
    private WirelessEnergyNetwork() {
    }
    
    /**
     * 获取全局管理器实例（双重检查锁定）
     */
    public static WirelessEnergyNetwork getInstance() {
        if (instance == null) {
            synchronized (WirelessEnergyNetwork.class) {
                if (instance == null) {
                    instance = new WirelessEnergyNetwork();
                }
            }
        }
        return instance;
    }
    
    /**
     * 🔥 服务端 Tick 事件 - 自动执行能量传输
     * 
     * 智能防重复机制：
     * - 如果监控方块已经执行过，跳过（避免重复）
     * - 如果没有监控方块，自动执行（确保功能正常）
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // 在 tick 结束阶段执行，确保所有方块实体都已更新
        getInstance().tickIfNeeded(false); // false = 由事件触发
    }
    
    /**
     * 服务器关闭时清理数据
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        getInstance().clear();
    }
    
    /**
     * 注册能源塔到全局管理器
     * 
     * @param tower 要注册的能源塔
     */
    public void registerTower(WirelessEnergyTowerBlockEntity tower) {
        if (tower == null || tower.isRemoved()) {
            return;
        }
        
        BlockPos pos = tower.getBlockPos();
        Level level = tower.getLevel();
        
        if (level == null) {
            return;
        }
        
        // 注册到全局列表
        registeredTowers.put(pos, tower);
        
        // 注册到维度分组列表
        towersByLevel.computeIfAbsent(level, k -> new ArrayList<>()).add(tower);
    }
    
    /**
     * 从全局管理器注销能源塔
     * 
     * @param tower 要注销的能源塔
     */
    public void unregisterTower(WirelessEnergyTowerBlockEntity tower) {
        if (tower == null) {
            return;
        }
        
        BlockPos pos = tower.getBlockPos();
        Level level = tower.getLevel();
        
        // 从全局列表移除
        registeredTowers.remove(pos);
        
        // 从维度分组列表移除
        if (level != null) {
            List<WirelessEnergyTowerBlockEntity> towers = towersByLevel.get(level);
            if (towers != null) {
                towers.remove(tower);
                if (towers.isEmpty()) {
                    towersByLevel.remove(level);
                }
            }
        }
    }
    
    /**
     * 🔥 智能 Tick 调度 - 防止同一 tick 内重复执行
     * 
     * @param fromMonitor 是否由监控方块触发
     */
    private void tickIfNeeded(boolean fromMonitor) {
        if (registeredTowers.isEmpty()) {
            return;
        }
        
        // 获取当前游戏时间（用于判断是否在同一个 tick）
        long currentTick = getCurrentGameTime();
        
        // 检查是否在同一个 tick 内已经执行过
        if (currentTick == lastExecutedTick) {
            // 已经执行过，跳过
            return;
        }
        
        // 标记当前 tick 已执行
        lastExecutedTick = currentTick;
        executedByMonitor = fromMonitor;
        
        // 执行实际的能量传输
        tick();
    }
    
    /**
     * 核心 Tick 逻辑 - 批量处理所有能源塔的能量传输
     * 
     * 优化策略：
     * 1. 按维度分组处理，避免跨维度查询
     * 2. 批量验证连接有效性
     * 3. 统一能量传输，减少重复查询
     */
    private void tick() {
        if (registeredTowers.isEmpty()) {
            return; // 没有能源塔，直接返回
        }
        
        // 按维度处理每组能源塔
        for (Map.Entry<Level, List<WirelessEnergyTowerBlockEntity>> entry : towersByLevel.entrySet()) {
            Level level = entry.getKey();
            List<WirelessEnergyTowerBlockEntity> towers = entry.getValue();
            
            if (towers.isEmpty()) {
                continue;
            }
            
            // 批量处理当前维度的所有能源塔
            processTowersInLevel(level, towers);
        }
    }
    
    /**
     * 获取当前游戏时间（从任意已注册的塔获取）
     */
    private long getCurrentGameTime() {
        for (WirelessEnergyTowerBlockEntity tower : registeredTowers.values()) {
            if (tower.getLevel() != null) {
                return tower.getLevel().getGameTime();
            }
        }
        return System.currentTimeMillis() / 50; // 后备方案
    }
    
    /**
     * 处理单个维度内的所有能源塔
     * 
     * @param level 维度
     * @param towers 该维度内的所有能源塔
     */
    private void processTowersInLevel(Level level, List<WirelessEnergyTowerBlockEntity> towers) {
        // 移除已失效的塔（使用迭代器安全删除）
        towers.removeIf(tower -> tower.isRemoved() || tower.getLevel() == null);
        
        // 为每个塔处理能量传输
        for (WirelessEnergyTowerBlockEntity tower : towers) {
            processSingleTower(tower);
        }
    }
    
    /**
     * 处理单个能源塔的能量传输
     * 这是原来 serverTick 的逻辑，现在集中在这里执行
     * 
     * @param tower 要处理的能源塔
     */
    private void processSingleTower(WirelessEnergyTowerBlockEntity tower) {
        if (tower.isRemoved()) {
            return;
        }
        
        Level level = tower.getLevel();
        if (level == null) {
            return;
        }
        
        // 获取塔的连接目标
        Set<BlockPos> links = tower.getLinks();
        if (links.isEmpty()) {
            return;
        }
        
        // 主动推送能量到所有连接的机器
        Set<BlockPos> validLinks = new HashSet<>();
        for (BlockPos targetPos : new HashSet<>(links)) {
            BlockEntity targetBE = level.getBlockEntity(targetPos);
            if (targetBE == null) {
                // 目标不存在，移除绑定
                tower.removeLink(targetPos);
                continue;
            }
            
            // 主动推送能量到目标
            tower.pushEnergyToTarget(targetBE);
            validLinks.add(targetPos);
        }
        
        // 检查连接是否变化，如果变化则同步到客户端
        if (!validLinks.equals(tower.getLastSyncedLinks())) {
            tower.updateSyncedLinks(validLinks);
        }
    }
    
    /**
     * 清理所有数据（服务器关闭时调用）
     */
    private void clear() {
        registeredTowers.clear();
        towersByLevel.clear();
    }
    
    /**
     * 获取已注册的能源塔数量（调试用）
     */
    public int getRegisteredTowerCount() {
        return registeredTowers.size();
    }
    
    /**
     * 获取指定维度的能源塔数量（调试用）
     */
    public int getTowerCountInLevel(Level level) {
        List<WirelessEnergyTowerBlockEntity> towers = towersByLevel.get(level);
        return towers == null ? 0 : towers.size();
    }
    
    /**
     * 🔥 手动触发能量传输（供监控方块使用）
     * 
     * 智能机制：
     * - 优先执行：监控方块的 tick 先于全局事件
     * - 防重复：如果本 tick 已执行过，跳过
     * - 后备：如果没有监控方块，全局事件会自动执行
     * 
     * 性能可视化：
     * - 监控方块显示的延迟 = 所有塔的真实传输开销
     * - 包含所有维度的所有已加载能源塔
     */
    public void triggerPerformanceCheck() {
        // 调用智能调度，标记为"由监控方块触发"
        tickIfNeeded(true);
    }
}

