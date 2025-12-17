package com.mebeamformer.blockentity;

import com.mebeamformer.ME_Beam_Former;
import com.mebeamformer.connection.WirelessEnergyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 用于触发全局能源网络的检测逻辑。
 */
public class EnergyNetworkMonitorBlockEntity extends BlockEntity {
    
    public EnergyNetworkMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ME_Beam_Former.ENERGY_NETWORK_MONITOR_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, EnergyNetworkMonitorBlockEntity be) {
        if (level.isClientSide) {
            return;
        }
        
        WirelessEnergyNetwork network = WirelessEnergyNetwork.getInstance();

        // 由监控方块主动触发一次网络处理，便于性能检测模组统计。
        network.triggerPerformanceCheck();
    }
    
    public static void clientTick(Level level, BlockPos pos, BlockState state, EnergyNetworkMonitorBlockEntity be) {
    }
}

