package com.mebeamformer.energy;

import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * 支持 long 级能量的存储接口，兼容 {@link IEnergyStorage} 语义。
 */
public interface ILongEnergyStorage {


    long receiveEnergyL(long maxReceive, boolean simulate);


    long extractEnergyL(long maxExtract, boolean simulate);


    long getEnergyStoredL();


    long getMaxEnergyStoredL();


    boolean canExtract();


    boolean canReceive();
}