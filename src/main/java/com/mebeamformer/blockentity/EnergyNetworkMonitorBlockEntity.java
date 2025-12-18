package com.mebeamformer.blockentity;

import com.mebeamformer.MEBeamFormer;
import com.mebeamformer.connection.WirelessEnergyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 能源网络监控方块，供性能检测使用，可选装。
 */
public class EnergyNetworkMonitorBlockEntity extends BlockEntity {
    
    public EnergyNetworkMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(MEBeamFormer.ENERGY_NETWORK_MONITOR_BE.get(), pos, state);
    }
    
    public static void serverTick(Level level, BlockPos pos, BlockState state, EnergyNetworkMonitorBlockEntity be) {
        if (level.isClientSide) {
            return;
        }
        
        WirelessEnergyNetwork network = WirelessEnergyNetwork.getInstance();
        
        network.triggerPerformanceCheck();
        
        if (level.getGameTime() % 20 == 0) {
            int totalTowers = network.getRegisteredTowerCount();
            int localTowers = network.getTowerCountInLevel(level);
            
        }
    }
    
    public static void clientTick(Level level, BlockPos pos, BlockState state, EnergyNetworkMonitorBlockEntity be) {
        // 客户端不需要处理
    }
}

