package com.mebeamformer.energy;

import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Flux Networks动态代理适配器
 */
public class FluxEnergyAdapter {
    
    private static volatile Class<?> FLUX_INTERFACE_CLASS = null;
    private static volatile boolean FLUX_CHECKED = false;
    
    public static Object createFluxAdapter(WirelessEnergyTowerBlockEntity tower) {
        if (!FLUX_CHECKED) {
            synchronized (FluxEnergyAdapter.class) {
                if (!FLUX_CHECKED) {
                    try {
                        FLUX_INTERFACE_CLASS = Class.forName("sonar.fluxnetworks.api.energy.IFNEnergyStorage");
                    } catch (ClassNotFoundException e) {
                        // Flux Networks未安装
                    }
                    FLUX_CHECKED = true;
                }
            }
        }
        
        if (FLUX_INTERFACE_CLASS == null) {
            return null; // Flux未安装
        }
        
        try {
            // 创建动态代理，实现IFNEnergyStorage接口
            return Proxy.newProxyInstance(
                FLUX_INTERFACE_CLASS.getClassLoader(),
                new Class<?>[]{FLUX_INTERFACE_CLASS},
                new FluxInvocationHandler(tower)
            );
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 动态代理调用处理器
     * 将Flux Networks的方法调用转发到WirelessEnergyTowerBlockEntity
     */
    private static class FluxInvocationHandler implements InvocationHandler {
        private final WirelessEnergyTowerBlockEntity tower;
        
        public FluxInvocationHandler(WirelessEnergyTowerBlockEntity tower) {
            this.tower = tower;
        }
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            
            // 映射Flux Networks的方法到我们的ILongEnergyStorage方法
            switch (methodName) {
                case "receiveEnergyL":
                    if (args != null && args.length >= 2) {
                        return tower.receiveEnergyL((Long) args[0], (Boolean) args[1]);
                    }
                    return 0L;
                    
                case "extractEnergyL":
                    if (args != null && args.length >= 2) {
                        return tower.extractEnergyL((Long) args[0], (Boolean) args[1]);
                    }
                    return 0L;
                    
                case "getEnergyStoredL":
                    return tower.getEnergyStoredL();
                    
                case "getMaxEnergyStoredL":
                    return tower.getMaxEnergyStoredL();
                    
                case "canExtract":
                    return tower.canExtract();
                    
                case "canReceive":
                    return tower.canReceive();
                    
                // 兼容标准IEnergyStorage方法（如果Flux接口继承了它）
                case "receiveEnergy":
                    if (args != null && args.length >= 2) {
                        return tower.receiveEnergy((Integer) args[0], (Boolean) args[1]);
                    }
                    return 0;
                    
                case "extractEnergy":
                    if (args != null && args.length >= 2) {
                        return tower.extractEnergy((Integer) args[0], (Boolean) args[1]);
                    }
                    return 0;
                    
                case "getEnergyStored":
                    return tower.getEnergyStored();
                    
                case "getMaxEnergyStored":
                    return tower.getMaxEnergyStored();
                    
                default:
                    // 未知方法，尝试默认处理
                    return null;
            }
        }
    }
}

