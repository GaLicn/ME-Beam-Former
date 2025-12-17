package com.mebeamformer.energy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

public class EnergyStorageHelper {
    
    // Flux Networks（软依赖，反射）
    private static volatile boolean FLUX_INITIALIZED = false;
    private static Object FLUX_CAPABILITY = null;
    
    private static void initFluxReflection() {
        if (FLUX_INITIALIZED) return;
        synchronized (EnergyStorageHelper.class) {
            if (FLUX_INITIALIZED) return;
            try {
                Class<?> fluxCapClass = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
                java.lang.reflect.Field blockCapField = fluxCapClass.getField("BLOCK");
                FLUX_CAPABILITY = blockCapField.get(null);
            } catch (Exception e) {
            }
            FLUX_INITIALIZED = true;
        }
    }
    
    public static long extractEnergy(BlockEntity be, @Nullable Direction side, long maxExtract, boolean simulate) {
        if (be == null || be.isRemoved()) return -1;
        Level level = be.getLevel();
        if (level == null) return -1;

        long fluxExtracted = tryExtractFluxEnergy(be, level, side, maxExtract, simulate);
        if (fluxExtracted >= 0) {
            return fluxExtracted;
        }

        long gtExtracted = tryExtractGTEnergy(be, level, side, maxExtract, simulate);
        if (gtExtracted >= 0) {
            return gtExtracted;
        }

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
    
    public static long insertEnergy(BlockEntity be, @Nullable Direction side, long maxInsert, boolean simulate) {
        if (be == null || be.isRemoved()) return -1;
        Level level = be.getLevel();
        if (level == null) return -1;

        long fluxInserted = tryInsertFluxEnergy(be, level, side, maxInsert, simulate);
        if (fluxInserted >= 0) {
            return fluxInserted;
        }

        long gtInserted = tryInsertGTEnergy(be, level, side, maxInsert, simulate);
        if (gtInserted >= 0) {
            return gtInserted;
        }

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
    
    public static boolean canExtract(BlockEntity be, @Nullable Direction side) {
        if (be == null || be.isRemoved()) return false;
        Level level = be.getLevel();
        if (level == null) return false;

        initFluxReflection();
        if (FLUX_CAPABILITY != null) {
            try {
                java.lang.reflect.Method getCapMethod = level.getClass().getMethod(
                    "getCapability",
                    net.neoforged.neoforge.capabilities.BlockCapability.class,
                    BlockPos.class,
                    net.minecraft.world.level.block.state.BlockState.class,
                    BlockEntity.class,
                    Object.class
                );
                Object fluxCap = getCapMethod.invoke(level, FLUX_CAPABILITY, be.getBlockPos(), be.getBlockState(), be, side);
                if (fluxCap != null) {
                    java.lang.reflect.Method canExtractMethod = fluxCap.getClass().getMethod("canExtract");
                    if ((Boolean) canExtractMethod.invoke(fluxCap)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        IEnergyStorage normalCap = level.getCapability(
            Capabilities.EnergyStorage.BLOCK,
            be.getBlockPos(),
            be.getBlockState(),
            be,
            side
        );
        return normalCap != null && normalCap.canExtract();
    }
    
    public static boolean canReceive(BlockEntity be, @Nullable Direction side) {
        if (be == null || be.isRemoved()) return false;
        Level level = be.getLevel();
        if (level == null) return false;

        initFluxReflection();
        if (FLUX_CAPABILITY != null) {
            try {
                java.lang.reflect.Method getCapMethod = level.getClass().getMethod(
                    "getCapability",
                    net.neoforged.neoforge.capabilities.BlockCapability.class,
                    BlockPos.class,
                    net.minecraft.world.level.block.state.BlockState.class,
                    BlockEntity.class,
                    Object.class
                );
                Object fluxCap = getCapMethod.invoke(level, FLUX_CAPABILITY, be.getBlockPos(), be.getBlockState(), be, side);
                if (fluxCap != null) {
                    java.lang.reflect.Method canReceiveMethod = fluxCap.getClass().getMethod("canReceive");
                    if ((Boolean) canReceiveMethod.invoke(fluxCap)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        
        IEnergyStorage normalCap = level.getCapability(
            Capabilities.EnergyStorage.BLOCK,
            be.getBlockPos(),
            be.getBlockState(),
            be,
            side
        );
        return normalCap != null && normalCap.canReceive();
    }
    
    public static long getEnergyStored(BlockEntity be, @Nullable Direction side) {
        if (be == null || be.isRemoved()) return 0;
        Level level = be.getLevel();
        if (level == null) return 0;

        initFluxReflection();
        if (FLUX_CAPABILITY != null) {
            try {
                java.lang.reflect.Method getCapMethod = level.getClass().getMethod(
                    "getCapability",
                    net.neoforged.neoforge.capabilities.BlockCapability.class,
                    BlockPos.class,
                    net.minecraft.world.level.block.state.BlockState.class,
                    BlockEntity.class,
                    Object.class
                );
                Object fluxCap = getCapMethod.invoke(level, FLUX_CAPABILITY, be.getBlockPos(), be.getBlockState(), be, side);
                if (fluxCap != null) {
                    java.lang.reflect.Method getEnergyStoredMethod = fluxCap.getClass().getMethod("getEnergyStoredL");
                    return (Long) getEnergyStoredMethod.invoke(fluxCap);
                }
            } catch (Exception ignored) {
            }
        }

        IEnergyStorage normalCap = level.getCapability(
            Capabilities.EnergyStorage.BLOCK,
            be.getBlockPos(),
            be.getBlockState(),
            be,
            side
        );
        return normalCap != null ? normalCap.getEnergyStored() : 0;
    }

    private static long tryExtractFluxEnergy(BlockEntity be, Level level, @Nullable Direction side, long maxExtract, boolean simulate) {
        initFluxReflection();
        if (FLUX_CAPABILITY == null) return -1;
        
        try {
            java.lang.reflect.Method getCapMethod = level.getClass().getMethod(
                "getCapability",
                net.neoforged.neoforge.capabilities.BlockCapability.class,
                BlockPos.class,
                net.minecraft.world.level.block.state.BlockState.class,
                BlockEntity.class,
                Object.class
            );
            
            Object fluxCap = getCapMethod.invoke(level, FLUX_CAPABILITY, be.getBlockPos(), be.getBlockState(), be, side);
            
            if (fluxCap != null) {
                java.lang.reflect.Method canExtractMethod = fluxCap.getClass().getMethod("canExtract");
                if ((Boolean) canExtractMethod.invoke(fluxCap)) {
                    java.lang.reflect.Method extractMethod = fluxCap.getClass().getMethod("extractEnergyL", long.class, boolean.class);
                    return (Long) extractMethod.invoke(fluxCap, maxExtract, simulate);
                }
            }
        } catch (Exception e) {
        }
        
        return -1;
    }
    
    private static long tryInsertFluxEnergy(BlockEntity be, Level level, @Nullable Direction side, long maxInsert, boolean simulate) {
        initFluxReflection();
        if (FLUX_CAPABILITY == null) return -1;
        
        try {
            java.lang.reflect.Method getCapMethod = level.getClass().getMethod(
                "getCapability",
                net.neoforged.neoforge.capabilities.BlockCapability.class,
                BlockPos.class,
                net.minecraft.world.level.block.state.BlockState.class,
                BlockEntity.class,
                Object.class
            );
            
            Object fluxCap = getCapMethod.invoke(level, FLUX_CAPABILITY, be.getBlockPos(), be.getBlockState(), be, side);
            
            if (fluxCap != null) {
                java.lang.reflect.Method canReceiveMethod = fluxCap.getClass().getMethod("canReceive");
                if ((Boolean) canReceiveMethod.invoke(fluxCap)) {
                    java.lang.reflect.Method receiveMethod = fluxCap.getClass().getMethod("receiveEnergyL", long.class, boolean.class);
                    return (Long) receiveMethod.invoke(fluxCap, maxInsert, simulate);
                }
            }
        } catch (Exception e) {
        }
        
        return -1;
    }

    private static long tryExtractGTEnergy(BlockEntity be, Level level, @Nullable Direction side, long maxExtractFE, boolean simulate) {
        if (!GTEnergyAdapter.isGTAvailable()) return -1;
        
        try {
            Object gtCap = GTEnergyAdapter.getGTCapability();
            if (gtCap == null) return -1;

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
                java.lang.reflect.Method outputsEnergyMethod = gtContainer.getClass().getMethod("outputsEnergy", Direction.class);
                boolean canOutput = (Boolean) outputsEnergyMethod.invoke(gtContainer, side);
                
                if (canOutput) {
                    java.lang.reflect.Method getEnergyStoredMethod = gtContainer.getClass().getMethod("getEnergyStored");
                    long storedEU = (Long) getEnergyStoredMethod.invoke(gtContainer);
                    
                    if (storedEU > 0) {
                        long maxExtractEU = GTEnergyAdapter.feToEU(maxExtractFE);
                        long toExtractEU = Math.min(storedEU, maxExtractEU);
                        
                        if (!simulate) {
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
        }
        
        return -1;
    }
    
    private static long tryInsertGTEnergy(BlockEntity be, Level level, @Nullable Direction side, long maxInsertFE, boolean simulate) {
        if (!GTEnergyAdapter.isGTAvailable()) return -1;
        
        try {
            Object gtCap = GTEnergyAdapter.getGTCapability();
            if (gtCap == null) return -1;

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
                java.lang.reflect.Method inputsEnergyMethod = gtContainer.getClass().getMethod("inputsEnergy", Direction.class);
                boolean canInput = (Boolean) inputsEnergyMethod.invoke(gtContainer, side);
                
                if (canInput) {
                    java.lang.reflect.Method getInputVoltageMethod = gtContainer.getClass().getMethod("getInputVoltage");
                    java.lang.reflect.Method getInputAmperageMethod = gtContainer.getClass().getMethod("getInputAmperage");
                    java.lang.reflect.Method getEnergyCanBeInsertedMethod = gtContainer.getClass().getMethod("getEnergyCanBeInserted");
                    
                    long voltage = (Long) getInputVoltageMethod.invoke(gtContainer);
                    long amperage = (Long) getInputAmperageMethod.invoke(gtContainer);
                    long canInsertEU = (Long) getEnergyCanBeInsertedMethod.invoke(gtContainer);
                    
                    if (canInsertEU > 0) {
                        long maxInsertEU = GTEnergyAdapter.feToEU(maxInsertFE);
                        long toInsertEU = Math.min(canInsertEU, maxInsertEU);
                        
                        long maxByVoltageAmperage = voltage * amperage;
                        toInsertEU = Math.min(toInsertEU, maxByVoltageAmperage);
                        
                        if (toInsertEU > 0) {
                            if (!simulate) {
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
        }
        
        return -1;
    }
}

