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
 * 统一处理标准能量、Flux Networks Long能量和GregTech能量
 */
public class EnergyStorageHelper {
    
    /**
     * 尝试从方块实体提取能量（支持多种能量系统）
     * 优先级：Flux Networks Long > GregTech > 标准能量
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
        
        // 2. 尝试GregTech能量（如果已安装）
        long gtExtracted = tryExtractGTEnergy(be, level, side, maxExtract, simulate);
        if (gtExtracted >= 0) {
            return gtExtracted;
        }
        
        // 3. 标准能量接口（转换为int）
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
     * 优先级：Flux Networks Long > GregTech > 标准能量
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
        
        // 2. 尝试GregTech能量（如果已安装）
        long gtInserted = tryInsertGTEnergy(be, level, side, maxInsert, simulate);
        if (gtInserted >= 0) {
            return gtInserted;
        }
        
        // 3. 标准能量接口（转换为int）
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
    
    // ==================== GregTech 能量支持 ====================
    
    /**
     * 尝试从 GregTech 机器提取能量（通过反射）
     * 
     * @return 实际提取的能量（FE），-1表示没有GT接口或GT未安装
     */
    private static long tryExtractGTEnergy(BlockEntity be, Level level, @Nullable Direction side, long maxExtractFE, boolean simulate) {
        if (!GTEnergyAdapter.isGTAvailable()) return -1;
        
        try {
            Object gtCap = GTEnergyAdapter.getGTCapability();
            if (gtCap == null) return -1;
            
            // 通过反射获取GT能量容器（因为类型在编译时未知）
            // 使用Level.getCapability的反射版本
            java.lang.reflect.Method getCapMethod = level.getClass().getMethod(
                "getCapability",
                net.neoforged.neoforge.capabilities.BlockCapability.class,
                BlockPos.class,
                net.minecraft.world.level.block.state.BlockState.class,
                BlockEntity.class,
                Object.class
            );
            
            Object gtContainer = getCapMethod.invoke(level, gtCap, be.getBlockPos(), be.getBlockState(), be, side);
            
            if (gtContainer != null) {
                // 通过反射调用GT方法
                java.lang.reflect.Method outputsEnergyMethod = gtContainer.getClass().getMethod("outputsEnergy", Direction.class);
                boolean canOutput = (Boolean) outputsEnergyMethod.invoke(gtContainer, side);
                
                if (canOutput) {
                    // 获取可提取的能量（EU）
                    java.lang.reflect.Method getEnergyStoredMethod = gtContainer.getClass().getMethod("getEnergyStored");
                    long storedEU = (Long) getEnergyStoredMethod.invoke(gtContainer);
                    
                    if (storedEU > 0) {
                        long maxExtractEU = GTEnergyAdapter.feToEU(maxExtractFE);
                        long toExtractEU = Math.min(storedEU, maxExtractEU);
                        
                        if (!simulate) {
                            // changeEnergy(负数) = 提取能量
                            java.lang.reflect.Method changeEnergyMethod = gtContainer.getClass().getMethod("changeEnergy", long.class);
                            long extractedEU = -(Long) changeEnergyMethod.invoke(gtContainer, -toExtractEU);
                            return GTEnergyAdapter.euToFE(extractedEU);
                        } else {
                            return GTEnergyAdapter.euToFE(toExtractEU);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // GT调用失败或不兼容
        }
        
        return -1;
    }
    
    /**
     * 尝试向 GregTech 机器插入能量（通过反射）
     * 
     * @return 实际插入的能量（FE），-1表示没有GT接口或GT未安装
     */
    private static long tryInsertGTEnergy(BlockEntity be, Level level, @Nullable Direction side, long maxInsertFE, boolean simulate) {
        if (!GTEnergyAdapter.isGTAvailable()) return -1;
        
        try {
            Object gtCap = GTEnergyAdapter.getGTCapability();
            if (gtCap == null) return -1;
            
            // 通过反射获取GT能量容器（因为类型在编译时未知）
            java.lang.reflect.Method getCapMethod = level.getClass().getMethod(
                "getCapability",
                net.neoforged.neoforge.capabilities.BlockCapability.class,
                BlockPos.class,
                net.minecraft.world.level.block.state.BlockState.class,
                BlockEntity.class,
                Object.class
            );
            
            Object gtContainer = getCapMethod.invoke(level, gtCap, be.getBlockPos(), be.getBlockState(), be, side);
            
            if (gtContainer != null) {
                // 通过反射调用GT方法
                java.lang.reflect.Method inputsEnergyMethod = gtContainer.getClass().getMethod("inputsEnergy", Direction.class);
                boolean canInput = (Boolean) inputsEnergyMethod.invoke(gtContainer, side);
                
                if (canInput) {
                    // 获取电压和电流
                    java.lang.reflect.Method getInputVoltageMethod = gtContainer.getClass().getMethod("getInputVoltage");
                    java.lang.reflect.Method getInputAmperageMethod = gtContainer.getClass().getMethod("getInputAmperage");
                    java.lang.reflect.Method getEnergyCanBeInsertedMethod = gtContainer.getClass().getMethod("getEnergyCanBeInserted");
                    
                    long voltage = (Long) getInputVoltageMethod.invoke(gtContainer);
                    long amperage = (Long) getInputAmperageMethod.invoke(gtContainer);
                    long canInsertEU = (Long) getEnergyCanBeInsertedMethod.invoke(gtContainer);
                    
                    if (canInsertEU > 0) {
                        long maxInsertEU = GTEnergyAdapter.feToEU(maxInsertFE);
                        long toInsertEU = Math.min(canInsertEU, maxInsertEU);
                        
                        // 限制到电压*电流
                        long maxByVoltageAmperage = voltage * amperage;
                        toInsertEU = Math.min(toInsertEU, maxByVoltageAmperage);
                        
                        if (toInsertEU > 0) {
                            if (!simulate) {
                                // acceptEnergyFromNetwork(side, voltage, amperage)
                                long actualVoltage = Math.min(voltage, toInsertEU);
                                long actualAmperage = Math.min(amperage, toInsertEU / Math.max(actualVoltage, 1));
                                
                                java.lang.reflect.Method acceptEnergyMethod = gtContainer.getClass().getMethod(
                                    "acceptEnergyFromNetwork", Direction.class, long.class, long.class
                                );
                                long acceptedAmperage = (Long) acceptEnergyMethod.invoke(gtContainer, side, actualVoltage, actualAmperage);
                                long insertedEU = actualVoltage * acceptedAmperage;
                                return GTEnergyAdapter.euToFE(insertedEU);
                            } else {
                                return GTEnergyAdapter.euToFE(toInsertEU);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // GT调用失败或不兼容
        }
        
        return -1;
    }
}

