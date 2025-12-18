package com.mebeamformer.energy;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import com.mebeamformer.MEBeamFormer;

public final class MEBFCapabilities {

    /**
     * 扩展的能量存储能力，支持Long.MAX_VALUE的能量传输
     */
    public static final BlockCapability<ILongEnergyStorage, Direction> LONG_ENERGY_STORAGE = 
            BlockCapability.createSided(
                ResourceLocation.fromNamespaceAndPath(MEBeamFormer.MODID, "long_energy_storage"),
                ILongEnergyStorage.class
            );

    private MEBFCapabilities() {
    }
} 