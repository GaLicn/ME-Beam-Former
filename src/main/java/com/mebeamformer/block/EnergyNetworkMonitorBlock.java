package com.mebeamformer.block;

import com.mebeamformer.blockentity.EnergyNetworkMonitorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 能源网络性能监控方块
 * 
 * 用于调试和性能分析：
 * - 让性能检测模组能够显示全局能源网络的延迟
 * - 放置在世界中即可监控整个网络性能
 * - 不影响实际游戏功能
 */
public class EnergyNetworkMonitorBlock extends Block implements EntityBlock {
    
    public EnergyNetworkMonitorBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }
    
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EnergyNetworkMonitorBlockEntity(pos, state);
    }
    
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> {
            if (be instanceof EnergyNetworkMonitorBlockEntity monitor) {
                if (lvl.isClientSide) {
                    EnergyNetworkMonitorBlockEntity.clientTick(lvl, pos, st, monitor);
                } else {
                    EnergyNetworkMonitorBlockEntity.serverTick(lvl, pos, st, monitor);
                }
            }
        };
    }
}

