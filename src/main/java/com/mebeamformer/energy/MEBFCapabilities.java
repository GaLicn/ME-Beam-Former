package com.mebeamformer.energy;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

public final class MEBFCapabilities {

    /**
     * 扩展的能量存储能力，支持Long.MAX_VALUE的能量传输
     * 仅在你的模组需要传输超过Integer.MAX_VALUE的能量时使用此能力
     * 无线能源感应塔会同时处理Forge Energy和此能力
     */
    public static final Capability<ILongEnergyStorage> LONG_ENERGY_STORAGE = 
            CapabilityManager.get(new CapabilityToken<>() {});

    private MEBFCapabilities() {
    }
} 