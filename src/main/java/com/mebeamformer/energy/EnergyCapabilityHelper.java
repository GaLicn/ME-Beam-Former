package com.mebeamformer.energy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

public class EnergyCapabilityHelper {
    
    @Nullable
    public static IEnergyStorage getEnergyStorage(BlockEntity be, @Nullable Direction side) {
        if (be == null || be.isRemoved()) return null;
        Level level = be.getLevel();
        if (level == null) return null;
        
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, be.getBlockPos(), be.getBlockState(), be, side);
    }
    
    @Nullable
    public static ILongEnergyStorage getLongEnergyStorage(BlockEntity be, @Nullable Direction side) {
        if (be == null || be.isRemoved()) return null;
        Level level = be.getLevel();
        if (level == null) return null;
        
        return level.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, be.getBlockPos(), be.getBlockState(), be, side);
    }
    
    @Nullable
    public static IEnergyStorage getEnergyStorageAt(Level level, BlockPos pos, @Nullable Direction side) {
        if (level == null) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, be.getBlockState(), be, side);
    }
    
    public static boolean hasEnergyStorage(BlockEntity be) {
        if (be == null || be.isRemoved()) return false;
        
        for (Direction dir : Direction.values()) {
            if (getEnergyStorage(be, dir) != null) {
                return true;
            }
        }
        return getEnergyStorage(be, null) != null;
    }
}

