package com.mebeamformer.energy;

import net.minecraftforge.common.capabilities.AutoRegisterCapability;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * 扩展的能量存储接口，支持Long.MAX_VALUE的能量传输
 * 兼容Forge的 {@link IEnergyStorage}
 */
@AutoRegisterCapability
public interface ILongEnergyStorage {

    /**
     * 向存储中添加能量。返回实际接收的能量数量。
     *
     * @param maxReceive 最大接收能量
     * @param simulate   如果为TRUE，则仅模拟
     * @return 实际接收（或模拟接收）的能量数量
     */
    long receiveEnergyL(long maxReceive, boolean simulate);

    /**
     * 从存储中提取能量。返回实际提取的能量数量。
     *
     * @param maxExtract 最大提取能量
     * @param simulate   如果为TRUE，则仅模拟
     * @return 实际提取（或模拟提取）的能量数量
     */
    long extractEnergyL(long maxExtract, boolean simulate);

    /**
     * 返回当前存储的能量数量
     */
    long getEnergyStoredL();

    /**
     * 返回最大能量存储容量
     */
    long getMaxEnergyStoredL();

    /**
     * 返回此存储是否可以提取能量
     * 如果为false，则任何extractEnergy调用都将返回0
     */
    boolean canExtract();

    /**
     * 返回此存储是否可以接收能量
     * 如果为false，则任何receiveEnergy调用都将返回0
     */
    boolean canReceive();
} 