package com.mebeamformer.energy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge 1.21.1 能量Capability辅助类
 * 简化能量接口的获取，隐藏NeoForge Capability API的复杂性
 */
public class EnergyCapabilityHelper {
    
    /**
     * 从方块实体获取能量存储接口
     * 
     * @param be 方块实体
     * @param side 方向（可为null表示内部）
     * @return 能量存储接口，如果不存在返回null
     */
    @Nullable
    public static IEnergyStorage getEnergyStorage(BlockEntity be, @Nullable Direction side) {
        if (be == null || be.isRemoved()) return null;
        Level level = be.getLevel();
        if (level == null) return null;
        
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, be.getBlockPos(), be.getBlockState(), be, side);
    }
    
    /**
     * 从方块实体获取Long能量存储接口（自定义Capability）
     * 
     * @param be 方块实体
     * @param side 方向（可为null表示内部）
     * @return Long能量存储接口，如果不存在返回null
     */
    @Nullable
    public static ILongEnergyStorage getLongEnergyStorage(BlockEntity be, @Nullable Direction side) {
        if (be == null || be.isRemoved()) return null;
        Level level = be.getLevel();
        if (level == null) return null;
        
        return level.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, be.getBlockPos(), be.getBlockState(), be, side);
    }
    
    /**
     * 从位置获取能量存储接口
     * 
     * @param level 世界
     * @param pos 位置
     * @param side 方向（可为null表示内部）
     * @return 能量存储接口，如果不存在返回null
     */
    @Nullable
    public static IEnergyStorage getEnergyStorageAt(Level level, BlockPos pos, @Nullable Direction side) {
        if (level == null) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, be.getBlockState(), be, side);
    }
    
    /**
     * 检查方块实体是否有能量存储能力
     * 
     * @param be 方块实体
     * @return 如果有任何方向的能量存储能力返回true
     */
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

