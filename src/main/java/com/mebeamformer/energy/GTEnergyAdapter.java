package com.mebeamformer.energy;

import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import net.minecraft.core.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;

/**
 * GregTech CEu 能量适配器（使用动态代理实现软依赖）
 * 
 * 功能：
 * - 将 WirelessEnergyTowerBlockEntity 的 ILongEnergyStorage 接口适配为 GregTech 的 IEnergyContainer 接口
 * - 支持 GregTech 的电压(Voltage)和电流(Amperage)系统
 * - 能量转换：4 FE = 1 EU
 * - 通过反射实现，无需编译时依赖 GregTech
 * 
 * GT能量系统说明：
 * - Voltage（电压）：单次传输的能量包大小
 * - Amperage（电流）：同时传输的能量包数量
 * - Total EU/t = Voltage × Amperage
 * - 例如：32V × 2A = 64 EU/t = 256 FE/t
 * 
 * @author ME Beam Former Team
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
    
    /**
     * 初始化 GregTech 反射（线程安全的延迟加载）
     */
    private static void initGTReflection() {
        if (GT_INITIALIZED) return;
        synchronized (GTEnergyAdapter.class) {
            if (GT_INITIALIZED) return;
            try {
                // 尝试加载 GregTech 的能量容器接口
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
    
    /**
     * 检查 GregTech 是否已安装
     */
    public static boolean isGTAvailable() {
        initGTReflection();
        return GT_ENERGY_CONTAINER_CLASS != null;
    }
    
    /**
     * 获取 GregTech 的能量容器 Capability（通过反射）
     */
    public static Object getGTCapability() {
        initGTReflection();
        if (GT_CAPABILITY_CLASS == null) return null;
        
        try {
            // 尝试获取 GTCapability.CAPABILITY_ENERGY_CONTAINER
            Field capField = GT_CAPABILITY_CLASS.getField("CAPABILITY_ENERGY_CONTAINER");
            return capField.get(null);
        } catch (Exception e) {
            LOGGER.warn("Failed to get GT energy capability: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 创建 GregTech IEnergyContainer 的动态代理实例
     * 
     * @param tower 无线能源感应塔实例
     * @return GregTech IEnergyContainer 接口的代理对象
     */
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
    
    /**
     * InvocationHandler 实现：将 IEnergyContainer 方法调用转发到 WirelessEnergyTowerBlockEntity
     */
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
                        // inputsEnergy(Direction side) -> boolean
                        // 感应塔可以从任何方向接收能量
                        return true;
                    
                    case "changeEnergy":
                        // changeEnergy(long differenceAmount) -> long
                        // 正数=充电，负数=放电
                        // 返回实际改变的量
                        if (args != null && args.length == 1) {
                            long differenceEU = (Long) args[0];
                            long differenceFE = differenceEU * EU_TO_FE_RATIO;
                            
                            if (differenceFE > 0) {
                                // 充电
                                long receivedFE = tower.receiveEnergyL(differenceFE, false);
                                return receivedFE / EU_TO_FE_RATIO;
                            } else if (differenceFE < 0) {
                                // 放电
                                long extractedFE = tower.extractEnergyL(-differenceFE, false);
                                return -(extractedFE / EU_TO_FE_RATIO);
                            }
                        }
                        return 0L;
                    
                    case "getEnergyCanBeInserted":
                        // getEnergyCanBeInserted() -> long (EU)
                        // 返回可以插入的能量（EU）
                        long spaceEU = (tower.getMaxEnergyStoredL() - tower.getEnergyStoredL()) / EU_TO_FE_RATIO;
                        return spaceEU;
                    
                    case "getInputAmperage":
                        // getInputAmperage() -> long
                        // 返回最大输入电流（包数）
                        // 我们支持超大传输，返回一个很大的值
                        return Long.MAX_VALUE / 512L; // 避免溢出，假设最大电压为512V
                    
                    case "getInputVoltage":
                        // getInputVoltage() -> long
                        // 返回最大输入电压（EU per packet）
                        // 感应塔支持任意电压，返回一个合理的大值
                        return 8192L; // ZPM tier voltage (8192 EU)
                    
                    // ========== 能量输出相关 ==========
                    case "outputsEnergy":
                        // outputsEnergy(Direction side) -> boolean
                        // 感应塔可以向绑定的目标输出能量
                        return tower.hasTargets();
                    
                    case "getEnergyStored":
                        // getEnergyStored() -> long (EU)
                        long storedFE = tower.getEnergyStoredL();
                        return storedFE / EU_TO_FE_RATIO;
                    
                    case "getEnergyCapacity":
                        // getEnergyCapacity() -> long (EU)
                        long capacityFE = tower.getMaxEnergyStoredL();
                        return capacityFE / EU_TO_FE_RATIO;
                    
                    case "getOutputAmperage":
                        // getOutputAmperage() -> long
                        return 64L; // 64A 输出
                    
                    case "getOutputVoltage":
                        // getOutputVoltage() -> long
                        return 8192L; // ZPM tier
                    
                    // ========== 通用方法 ==========
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
                        // 返回默认值
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

