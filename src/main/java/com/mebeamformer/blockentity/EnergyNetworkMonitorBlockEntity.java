package com.mebeamformer.blockentity;

import com.mebeamformer.MEBeamFormer;
import com.mebeamformer.connection.WirelessEnergyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 能源网络性能监控方块实体
 * 
 * 核心功能：性能可视化（完全可选）
 * - 让性能检测模组能够看到全局能源网络的真实延迟
 * - 不影响能量传输功能（有无监控方块都正常工作）
 * - 纯粹用于调试和性能分析
 * 
 * 工作原理：
 * - 监控方块优先执行能量传输
 * - 全局事件检测到已执行，自动跳过（防重复）
 * - 性能检测工具显示的延迟 = 所有塔的真实传输开销总和
 * 
 * 使用场景：
 * - 没有监控方块：能量正常传输，但性能检测工具看不到延迟
 * - 有监控方块：能量正常传输，性能检测工具能看到延迟
 * 
 * 覆盖范围：
 * - 所有维度（主世界、下界、末地等）
 * - 所有已加载区块的能源塔
 * - 监控方块位置不重要
 * 
 * 获取方式：
 * /give @s me_beam_former:energy_network_monitor
 */
public class EnergyNetworkMonitorBlockEntity extends BlockEntity {
    
    public EnergyNetworkMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(MEBeamFormer.ENERGY_NETWORK_MONITOR_BE.get(), pos, state);
    }
    
    /**
     * 服务端 Tick - 触发全局网络管理器的性能检测
     * 这个方法的延迟会被性能检测模组捕获
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, EnergyNetworkMonitorBlockEntity be) {
        if (level.isClientSide) {
            return;
        }
        
        WirelessEnergyNetwork network = WirelessEnergyNetwork.getInstance();
        
        // 关键：触发性能检测
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

