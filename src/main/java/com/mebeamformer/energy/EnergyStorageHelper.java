package com.mebeamformer.energy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge 1.21.1 能量存储辅助类
 * 统一处理标准能量和Flux Networks Long能量
 */
public class EnergyStorageHelper {
    
    /**
     * 尝试从方块实体提取能量（支持多种能量系统）
     * 优先级：Flux Networks Long > 标准能量
     * 
     * @return 实际提取的能量，-1表示没有能量接口
     */
    public static long extractEnergy(BlockEntity be, @Nullable Direction side, long maxExtract, boolean simulate) {
        if (be == null || be.isRemoved()) return -1;
        Level level = be.getLevel();
        if (level == null) return -1;
        
        // 1. 尝试Flux Networks Long能量（如果已安装）
        try {
            var fluxCap = level.getCapability(
                sonar.fluxnetworks.api.FluxCapabilities.BLOCK,
                be.getBlockPos(),
                be.getBlockState(),
                be,
                side
            );
            if (fluxCap != null && fluxCap.canExtract()) {
                return fluxCap.extractEnergyL(maxExtract, simulate);
            }
        } catch (NoClassDefFoundError ignored) {
            // Flux Networks未安装
        }
        
        // 2. 标准能量接口（转换为int）
        IEnergyStorage normalCap = level.getCapability(
            Capabilities.EnergyStorage.BLOCK,
            be.getBlockPos(),
            be.getBlockState(),
            be,
            side
        );
        if (normalCap != null && normalCap.canExtract()) {
            int maxInt = (int) Math.min(maxExtract, Integer.MAX_VALUE);
            return normalCap.extractEnergy(maxInt, simulate);
        }
        
        return -1; // 没有能量接口
    }
    
    /**
     * 尝试向方块实体插入能量（支持多种能量系统）
     * 优先级：Flux Networks Long > 标准能量
     * 
     * @return 实际插入的能量，-1表示没有能量接口
     */
    public static long insertEnergy(BlockEntity be, @Nullable Direction side, long maxInsert, boolean simulate) {
        if (be == null || be.isRemoved()) return -1;
        Level level = be.getLevel();
        if (level == null) return -1;
        
        // 1. 尝试Flux Networks Long能量（如果已安装）
        try {
            var fluxCap = level.getCapability(
                sonar.fluxnetworks.api.FluxCapabilities.BLOCK,
                be.getBlockPos(),
                be.getBlockState(),
                be,
                side
            );
            if (fluxCap != null && fluxCap.canReceive()) {
                return fluxCap.receiveEnergyL(maxInsert, simulate);
            }
        } catch (NoClassDefFoundError ignored) {
            // Flux Networks未安装
        }
        
        // 2. 标准能量接口（转换为int）
        IEnergyStorage normalCap = level.getCapability(
            Capabilities.EnergyStorage.BLOCK,
            be.getBlockPos(),
            be.getBlockState(),
            be,
            side
        );
        if (normalCap != null && normalCap.canReceive()) {
            int maxInt = (int) Math.min(maxInsert, Integer.MAX_VALUE);
            return normalCap.receiveEnergy(maxInt, simulate);
        }
        
        return -1; // 没有能量接口
    }
    
    /**
     * 检查方块实体是否可以提取能量
     */
    public static boolean canExtract(BlockEntity be, @Nullable Direction side) {
        if (be == null || be.isRemoved()) return false;
        Level level = be.getLevel();
        if (level == null) return false;
        
        // 检查Flux Networks
        try {
            var fluxCap = level.getCapability(
                sonar.fluxnetworks.api.FluxCapabilities.BLOCK,
                be.getBlockPos(),
                be.getBlockState(),
                be,
                side
            );
            if (fluxCap != null && fluxCap.canExtract()) {
                return true;
            }
        } catch (NoClassDefFoundError ignored) {
        }
        
        // 检查标准能量
        IEnergyStorage normalCap = level.getCapability(
            Capabilities.EnergyStorage.BLOCK,
            be.getBlockPos(),
            be.getBlockState(),
            be,
            side
        );
        return normalCap != null && normalCap.canExtract();
    }
    
    /**
     * 检查方块实体是否可以接收能量
     */
    public static boolean canReceive(BlockEntity be, @Nullable Direction side) {
        if (be == null || be.isRemoved()) return false;
        Level level = be.getLevel();
        if (level == null) return false;
        
        // 检查Flux Networks
        try {
            var fluxCap = level.getCapability(
                sonar.fluxnetworks.api.FluxCapabilities.BLOCK,
                be.getBlockPos(),
                be.getBlockState(),
                be,
                side
            );
            if (fluxCap != null && fluxCap.canReceive()) {
                return true;
            }
        } catch (NoClassDefFoundError ignored) {
        }
        
        // 检查标准能量
        IEnergyStorage normalCap = level.getCapability(
            Capabilities.EnergyStorage.BLOCK,
            be.getBlockPos(),
            be.getBlockState(),
            be,
            side
        );
        return normalCap != null && normalCap.canReceive();
    }
    
    /**
     * 获取方块实体存储的能量
     */
    public static long getEnergyStored(BlockEntity be, @Nullable Direction side) {
        if (be == null || be.isRemoved()) return 0;
        Level level = be.getLevel();
        if (level == null) return 0;
        
        // 尝试Flux Networks
        try {
            var fluxCap = level.getCapability(
                sonar.fluxnetworks.api.FluxCapabilities.BLOCK,
                be.getBlockPos(),
                be.getBlockState(),
                be,
                side
            );
            if (fluxCap != null) {
                return fluxCap.getEnergyStoredL();
            }
        } catch (NoClassDefFoundError ignored) {
        }
        
        // 标准能量
        IEnergyStorage normalCap = level.getCapability(
            Capabilities.EnergyStorage.BLOCK,
            be.getBlockPos(),
            be.getBlockState(),
            be,
            side
        );
        return normalCap != null ? normalCap.getEnergyStored() : 0;
    }
}

