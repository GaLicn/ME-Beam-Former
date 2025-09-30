package com.mebeamformer.block;

import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
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

public class WirelessEnergyTowerBlock extends Block implements EntityBlock {
    
    public WirelessEnergyTowerBlock(BlockBehaviour.Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WirelessEnergyTowerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> {
            if (be instanceof WirelessEnergyTowerBlockEntity tower) {
                if (lvl.isClientSide) {
                    WirelessEnergyTowerBlockEntity.clientTick(lvl, pos, st, tower);
                } else {
                    WirelessEnergyTowerBlockEntity.serverTick(lvl, pos, st, tower);
                }
            }
        };
    }
} 