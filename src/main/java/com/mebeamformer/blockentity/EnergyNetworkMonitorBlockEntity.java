package com.mebeamformer.blockentity;

import com.mebeamformer.ME_Beam_Former;
import com.mebeamformer.connection.WirelessEnergyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 能源网络性能监控方块实体
 * 
 * 目的：
 * - 让性能检测模组能够看到全局能源网络的延迟
 * - 不影响实际功能，只是触发全局管理器的 tick
 * - 便于调试和性能分析
 * 
 * 使用方法：
 * 1. 放置这个监控方块在世界中
 * 2. 它会在每个 tick 触发全局管理器
 * 3. 性能检测模组会显示此方块的延迟（实际上是整个网络的延迟）
 */
public class EnergyNetworkMonitorBlockEntity extends BlockEntity {
    
    public EnergyNetworkMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ME_Beam_Former.ENERGY_NETWORK_MONITOR_BE.get(), pos, state);
    }
    
    /**
     * 服务端 Tick - 触发全局网络管理器的性能检测
     * 🔥 这个方法的延迟会被性能检测模组捕获
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, EnergyNetworkMonitorBlockEntity be) {
        if (level.isClientSide) {
            return;
        }
        
        WirelessEnergyNetwork network = WirelessEnergyNetwork.getInstance();
        
        // 🔥 关键：触发性能检测
        // 这会模拟遍历所有能源塔，让性能检测模组能看到延迟
        // 注意：这不会影响实际的能量传输（已由全局事件处理）
        network.triggerPerformanceCheck();
        
        // 每秒在方块上方显示网络统计信息（可选）
        if (level.getGameTime() % 20 == 0) {
            int totalTowers = network.getRegisteredTowerCount();
            int localTowers = network.getTowerCountInLevel(level);
            
            // 你可以通过性能检测模组看到这个方块的延迟
            // 这个延迟代表整个能源网络的处理开销
        }
    }
    
    public static void clientTick(Level level, BlockPos pos, BlockState state, EnergyNetworkMonitorBlockEntity be) {
        // 客户端不需要处理
    }
}

