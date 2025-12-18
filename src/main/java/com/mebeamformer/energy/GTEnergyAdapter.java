package com.mebeamformer.energy;

import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import net.minecraft.core.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;

/**
 * GregTech CEu 能量适配器（动态代理软依赖，4 FE = 1 EU）。
 */
public class GTEnergyAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(GTEnergyAdapter.class);
    
    // GregTech 反射缓存
    private static volatile boolean GT_INITIALIZED = false;
    private static Class<?> GT_ENERGY_CONTAINER_CLASS = null;
    private static Class<?> GT_CAPABILITY_CLASS = null;
    
    // 能量转换率：4 FE = 1 EU
    public static final long FE_TO_EU_RATIO = 4L;
    public static final long EU_TO_FE_RATIO = 4L;
    
    private static void initGTReflection() {
        if (GT_INITIALIZED) return;
        synchronized (GTEnergyAdapter.class) {
            if (GT_INITIALIZED) return;
            try {
                GT_ENERGY_CONTAINER_CLASS = Class.forName("com.gregtechceu.gtceu.api.capability.IEnergyContainer");
                GT_CAPABILITY_CLASS = Class.forName("com.gregtechceu.gtceu.api.capability.GTCapability");
                
                LOGGER.info("GregTech CEu detected, energy conversion enabled (4 FE = 1 EU)");
            } catch (ClassNotFoundException e) {
                LOGGER.debug("GregTech CEu not installed, GT energy support disabled");
            } catch (Exception e) {
                LOGGER.warn("Failed to initialize GregTech reflection: {}", e.getMessage());
            }
            GT_INITIALIZED = true;
        }
    }
    
    public static boolean isGTAvailable() {
        initGTReflection();
        return GT_ENERGY_CONTAINER_CLASS != null;
    }
    
    public static Object getGTCapability() {
        initGTReflection();
        if (GT_CAPABILITY_CLASS == null) return null;
        
        try {
            Field capField = GT_CAPABILITY_CLASS.getField("CAPABILITY_ENERGY_CONTAINER");
            return capField.get(null);
        } catch (Exception e) {
            LOGGER.warn("Failed to get GT energy capability: {}", e.getMessage());
            return null;
        }
    }
    
    public static Object createGTAdapter(WirelessEnergyTowerBlockEntity tower) {
        initGTReflection();
        if (GT_ENERGY_CONTAINER_CLASS == null) {
            throw new IllegalStateException("GregTech not available");
        }
        
        return Proxy.newProxyInstance(
            GT_ENERGY_CONTAINER_CLASS.getClassLoader(),
            new Class<?>[]{GT_ENERGY_CONTAINER_CLASS},
            new GTInvocationHandler(tower)
        );
    }
    
    private static class GTInvocationHandler implements InvocationHandler {
        private final WirelessEnergyTowerBlockEntity tower;
        
        public GTInvocationHandler(WirelessEnergyTowerBlockEntity tower) {
            this.tower = tower;
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            
            try {
                switch (methodName) {
                    // ========== 能量输入相关 ==========
                    case "acceptEnergyFromNetwork":
                        // acceptEnergyFromNetwork(Direction side, long voltage, long amperage)
                        // 返回实际接受的 amperage
                        if (args != null && args.length == 3) {
                            Direction side = (Direction) args[0];
                            long voltage = (Long) args[1];    // EU per packet
                            long amperage = (Long) args[2];   // Number of packets
                            
                            long totalEU = voltage * amperage;
                            long totalFE = totalEU * EU_TO_FE_RATIO; // EU to FE
                            
                            // 尝试接收能量
                            long receivedFE = tower.receiveEnergyL(totalFE, false);
                            long receivedEU = receivedFE / EU_TO_FE_RATIO;
                            long receivedAmperage = voltage > 0 ? receivedEU / voltage : 0;
                            
                            return receivedAmperage;
                        }
                        return 0L;
                    
                    case "inputsEnergy":
                        return true;
                    
                    case "changeEnergy":
                        if (args != null && args.length == 1) {
                            long differenceEU = (Long) args[0];
                            long differenceFE = differenceEU * EU_TO_FE_RATIO;
                            
                            if (differenceFE > 0) {
                                long receivedFE = tower.receiveEnergyL(differenceFE, false);
                                return receivedFE / EU_TO_FE_RATIO;
                            } else if (differenceFE < 0) {
                                long extractedFE = tower.extractEnergyL(-differenceFE, false);
                                return -(extractedFE / EU_TO_FE_RATIO);
                            }
                        }
                        return 0L;
                    
                    case "getEnergyCanBeInserted":
                        long spaceEU = (tower.getMaxEnergyStoredL() - tower.getEnergyStoredL()) / EU_TO_FE_RATIO;
                        return spaceEU;
                    
                    case "getInputAmperage":
                        return Long.MAX_VALUE / 512L;
                    
                    case "getInputVoltage":
                        return 8192L;
                    
                    case "outputsEnergy":
                        return tower.hasTargets();
                    
                    case "getEnergyStored":
                        long storedFE = tower.getEnergyStoredL();
                        return storedFE / EU_TO_FE_RATIO;
                    
                    case "getEnergyCapacity":
                        long capacityFE = tower.getMaxEnergyStoredL();
                        return capacityFE / EU_TO_FE_RATIO;
                    
                    case "getOutputAmperage":
                        return 64L;
                    
                    case "getOutputVoltage":
                        return 8192L;
                    
                    case "toString":
                        return "GTEnergyAdapter[" + tower + "]";
                    
                    case "hashCode":
                        return tower.hashCode();
                    
                    case "equals":
                        if (args != null && args.length == 1) {
                            Object other = args[0];
                            if (Proxy.isProxyClass(other.getClass())) {
                                InvocationHandler handler = Proxy.getInvocationHandler(other);
                                if (handler instanceof GTInvocationHandler) {
                                    return this.tower.equals(((GTInvocationHandler) handler).tower);
                                }
                            }
                        }
                        return false;
                    
                    default:
                        LOGGER.warn("Unhandled GT IEnergyContainer method: {} ({})", 
                            methodName, method);
                        Class<?> returnType = method.getReturnType();
                        if (returnType == boolean.class) return false;
                        if (returnType == long.class) return 0L;
                        if (returnType == int.class) return 0;
                        return null;
                }
            } catch (Exception e) {
                LOGGER.error("Error invoking GT method {}: {}", methodName, e.getMessage(), e);
                throw e;
            }
        }
    }
    
    /**
     * 辅助方法：从 FE 转换为 EU
     */
    public static long feToEU(long fe) {
        return fe / FE_TO_EU_RATIO;
    }
    
    /**
     * 辅助方法：从 EU 转换为 FE
     */
    public static long euToFE(long eu) {
        return eu * EU_TO_FE_RATIO;
    }
}

