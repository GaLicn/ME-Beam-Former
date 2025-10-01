package com.mebeamformer.blockentity;

import appeng.api.networking.GridFlags;
import appeng.blockentity.grid.AENetworkBlockEntity;
import com.mebeamformer.ME_Beam_Former;
import com.mebeamformer.energy.ILongEnergyStorage;
import com.mebeamformer.energy.MEBFCapabilities;
import com.mebeamformer.integration.AE2FluxIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.lang.reflect.Method;

/**
 * 无线能源感应塔
 * 
 * 功能：
 * 1. 从邻居能量源提取能量，无线传输给绑定的目标机器
 * 2. 继承 AENetworkBlockEntity，可以连接 AE2 线缆并接入 ME 网络
 * 3. 如果安装了 appflux，可以直接从 ME 网络的 FE 存储提取能量
 * 4. 支持多种能量接口：Flux Networks、GregTech CEu、Long Energy、Forge Energy
 * 
 * 能量优先级：
 * - 输出到格雷机器时：ME 网络 (appflux) > Flux Networks > Long Energy > Forge Energy > 邻居能量源
 */
public class WirelessEnergyTowerBlockEntity extends AENetworkBlockEntity implements ILinkable {
    // 持久化：绑定目标集合
    private final Set<BlockPos> links = new HashSet<>();
    // 客户端渲染缓存：当前连接的目标列表（服务端同步）
    private List<BlockPos> clientLinks = Collections.emptyList();
    // 上一次服务端可见集合，用于决定是否 markForUpdate()
    private final Set<BlockPos> lastSyncedLinks = new HashSet<>();
    // 最大传输速率: Long.MAX_VALUE
    private static final long MAX_TRANSFER = Long.MAX_VALUE;
    
    // 能量能力缓存
    private final LazyOptional<?>[] energyCaps = new LazyOptional[7]; // 6个方向 + 1个null方向

    public WirelessEnergyTowerBlockEntity(BlockPos pos, BlockState state) {
        super(ME_Beam_Former.WIRELESS_ENERGY_TOWER_BE.get(), pos, state);
        
        // 配置 AE2 网络节点
        // 如果安装了 appflux，则可以从 ME 网络提取 FE 能量
        // 设置为不需要频道，空闲功率消耗为 0（能源塔不消耗网络能量）
        this.getMainNode()
            .setFlags(GridFlags.REQUIRE_CHANNEL)  // 需要频道才能连接
            .setIdlePowerUsage(0.0);  // 不消耗 AE2 网络能量
    }
    
    @Override
    public void setRemoved() {
        super.setRemoved();
        invalidateEnergyCaps();
    }
    
    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        invalidateEnergyCaps();
    }
    
    private void invalidateEnergyCaps() {
        for (int i = 0; i < energyCaps.length; i++) {
            if (energyCaps[i] != null) {
                energyCaps[i].invalidate();
                energyCaps[i] = null;
            }
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, WirelessEnergyTowerBlockEntity be) {
        if (be.isRemoved()) return;

        // 主动推送能量到所有连接的机器
        Set<BlockPos> validLinks = new HashSet<>();
        for (BlockPos targetPos : new HashSet<>(be.links)) {
            BlockEntity targetBE = level.getBlockEntity(targetPos);
            if (targetBE == null) {
                // 目标不存在，移除绑定
                be.removeLink(targetPos);
                continue;
            }

            // 主动推送能量到目标
            be.pushEnergyToTarget(targetBE);
            validLinks.add(targetPos);
        }
        
        // 检查连接是否变化，如果变化则同步到客户端
        if (!validLinks.equals(be.lastSyncedLinks)) {
            be.lastSyncedLinks.clear();
            be.lastSyncedLinks.addAll(validLinks);
            be.markForUpdate();
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, WirelessEnergyTowerBlockEntity be) {
        // 客户端不需要处理
    }

    /**
     * 主动推送能量到目标机器
     * 从能量源提取能量，然后推送给目标
     * 
     * 能量源优先级顺序：
     * 0. AE2 Network (appflux) - 支持Long，从ME网络的FE存储提取 ✨
     * 1. Flux Networks (IFNEnergyStorage) - 支持Long.MAX_VALUE，自动兼容Mekanism等模组
     * 2. Long能量接口 (ILongEnergyStorage) - 支持Long.MAX_VALUE
     * 3. 标准Forge Energy (IEnergyStorage) - 支持Integer.MAX_VALUE
     * 
     * 目标类型支持：
     * - 其他无线能源感应塔（组成电网）
     * - GregTech CEu (IEnergyContainer) - 支持Long，4 FE = 1 EU
     * - Flux Networks设备
     * - Long能量接口设备
     * - 标准Forge Energy设备
     */
    private void pushEnergyToTarget(BlockEntity target) {
        if (level == null) return;

        // 特殊处理：如果目标是另一个感应塔，直接传输能量（电网功能）
        if (target instanceof WirelessEnergyTowerBlockEntity targetTower) {
            pushEnergyToTower(targetTower);
            return;
        }

        // 优先尝试格雷科技
        boolean transferred = tryPushGTEnergy(target);
        if (transferred) return;

        // 尝试使用Long能量接口推送（包括Flux Networks会在getCapability中处理）
        transferred = tryPushLongEnergy(target);
        if (!transferred) {
            // 回退到标准Forge Energy
            tryPushForgeEnergy(target);
        }
    }
    
    /**
     * 推送能量到另一个感应塔（电网功能）
     * 直接从源的邻居提取能量，传递给目标塔及其整个电网
     */
    private void pushEnergyToTower(WirelessEnergyTowerBlockEntity targetTower) {
        if (level == null) return;
        
        // 创建访问追踪集合，防止循环
        Set<BlockPos> visited = new HashSet<>();
        visited.add(this.worldPosition); // 标记源塔已访问
        
        // 优先尝试从 AE2 网络提取能量
        if (AE2FluxIntegration.isAvailable()) {
            long extracted = AE2FluxIntegration.extractEnergyFromOwnNetwork(this, MAX_TRANSFER, true);
            if (extracted > 0) {
                // 尝试将能量推送到目标塔及其整个电网
                long inserted = targetTower.distributeEnergyInNetwork(extracted, false, visited);
                if (inserted > 0) {
                    AE2FluxIntegration.extractEnergyFromOwnNetwork(this, inserted, false);
                    return;
                }
            }
        }
        
        // 从自己的邻居获取能量源
        Object sourceFlux = getNeighborFluxEnergy();
        if (sourceFlux != null) {
            try {
                Method extractMethod = sourceFlux.getClass().getMethod("extractEnergyL", long.class, boolean.class);
                long extracted = (Long) extractMethod.invoke(sourceFlux, MAX_TRANSFER, true);
                if (extracted > 0) {
                    long inserted = targetTower.distributeEnergyInNetwork(extracted, false, visited);
                    if (inserted > 0) {
                        extractMethod.invoke(sourceFlux, inserted, false);
                        return;
                    }
                }
            } catch (Exception ignored) {}
        }
        
        ILongEnergyStorage sourceLong = getNeighborLongEnergy();
        if (sourceLong != null) {
            long extracted = sourceLong.extractEnergyL(MAX_TRANSFER, true);
            if (extracted > 0) {
                long inserted = targetTower.distributeEnergyInNetwork(extracted, false, visited);
                if (inserted > 0) {
                    sourceLong.extractEnergyL(inserted, false);
                    return;
                }
            }
        }
        
        IEnergyStorage sourceEnergy = getNeighborForgeEnergy();
        if (sourceEnergy != null) {
            int extracted = sourceEnergy.extractEnergy(Integer.MAX_VALUE, true);
            if (extracted > 0) {
                long inserted = targetTower.distributeEnergyInNetwork(extracted, false, visited);
                if (inserted > 0) {
                    sourceEnergy.extractEnergy((int) Math.min(inserted, Integer.MAX_VALUE), false);
                }
            }
        }
    }
    
    /**
     * 在整个感应塔网络中分配能量
     * 使用深度优先遍历，将能量分配给当前塔及其连接的所有设备和塔
     * 
     * @param amount 要分配的能量
     * @param simulate 是否模拟
     * @param visited 已访问的塔的位置集合（防止循环）
     * @return 实际分配的能量
     */
    private long distributeEnergyInNetwork(long amount, boolean simulate, Set<BlockPos> visited) {
        if (level == null || amount <= 0) return 0;
        
        // 标记当前塔为已访问
        if (!visited.add(this.worldPosition)) {
            // 已经访问过，避免循环
            return 0;
        }
        
        long totalInserted = 0;
        
        // 1. 先分配给当前塔的邻居设备
        for (Direction dir : Direction.values()) {
            if (totalInserted >= amount) break;
            
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE != null && !(neighborBE instanceof WirelessEnergyTowerBlockEntity)) {
                long remaining = amount - totalInserted;
                
                // 尝试Long接口
                LazyOptional<ILongEnergyStorage> longCap = neighborBE.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir.getOpposite());
                if (longCap.isPresent()) {
                    ILongEnergyStorage storage = longCap.orElse(null);
                    if (storage != null && storage.canReceive()) {
                        long inserted = storage.receiveEnergyL(remaining, simulate);
                        totalInserted += inserted;
                        continue;
                    }
                }
                
                // 回退到标准接口
                LazyOptional<IEnergyStorage> normalCap = neighborBE.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite());
                if (normalCap.isPresent()) {
                    IEnergyStorage storage = normalCap.orElse(null);
                    if (storage != null && storage.canReceive()) {
                        int inserted = storage.receiveEnergy((int) Math.min(remaining, Integer.MAX_VALUE), simulate);
                        totalInserted += inserted;
                    }
                }
            }
        }
        
        // 2. 分配给当前塔连接的普通设备（非感应塔）
        if (totalInserted < amount && !links.isEmpty()) {
            for (BlockPos targetPos : new HashSet<>(links)) {
                if (totalInserted >= amount) break;
                
                BlockEntity targetBE = level.getBlockEntity(targetPos);
                if (targetBE == null) continue;
                
                // 只处理非感应塔设备
                if (!(targetBE instanceof WirelessEnergyTowerBlockEntity)) {
                    long remaining = amount - totalInserted;
                    long inserted = pushEnergyToTargetDirect(targetBE, remaining, simulate);
                    totalInserted += inserted;
                }
            }
        }
        
        // 3. 将剩余能量递归分配给连接的其他感应塔
        if (totalInserted < amount && !links.isEmpty()) {
            for (BlockPos targetPos : new HashSet<>(links)) {
                if (totalInserted >= amount) break;
                
                BlockEntity targetBE = level.getBlockEntity(targetPos);
                if (targetBE instanceof WirelessEnergyTowerBlockEntity targetTower) {
                    // 检查是否已访问过
                    if (!visited.contains(targetPos)) {
                        long remaining = amount - totalInserted;
                        // 递归分配能量到下一个塔及其网络
                        long inserted = targetTower.distributeEnergyInNetwork(remaining, simulate, visited);
                        totalInserted += inserted;
                    }
                }
            }
        }
        
        return totalInserted;
    }
    
    /**
     * 直接推送能量到目标设备（用于能量分配）
     * 返回实际插入的能量
     */
    private long pushEnergyToTargetDirect(BlockEntity target, long amount, boolean simulate) {
        if (level == null || amount <= 0) return 0;
        
        // 尝试格雷科技
        long inserted = tryPushGTEnergyDirect(target, amount, simulate);
        if (inserted > 0) return inserted;
        
        // 尝试Long能量接口
        for (Direction dir : Direction.values()) {
            LazyOptional<ILongEnergyStorage> longCap = target.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir);
            if (longCap.isPresent()) {
                ILongEnergyStorage storage = longCap.orElse(null);
                if (storage != null && storage.canReceive()) {
                    return storage.receiveEnergyL(amount, simulate);
                }
            }
        }
        
        // 回退到标准Forge Energy
        for (Direction dir : Direction.values()) {
            LazyOptional<IEnergyStorage> cap = target.getCapability(ForgeCapabilities.ENERGY, dir);
            if (cap.isPresent()) {
                IEnergyStorage storage = cap.orElse(null);
                if (storage != null && storage.canReceive()) {
                    return storage.receiveEnergy((int) Math.min(amount, Integer.MAX_VALUE), simulate);
                }
            }
        }
        
        return 0;
    }
    
    /**
     * 直接推送能量到格雷科技设备（用于能量分配）
     */
    private long tryPushGTEnergyDirect(BlockEntity target, long amountFE, boolean simulate) {
        try {
            Class<?> gtCapClass = Class.forName("com.gregtechceu.gtceu.api.capability.forge.GTCapability");
            java.lang.reflect.Field field = gtCapClass.getField("CAPABILITY_ENERGY_CONTAINER");
            Capability<?> gtCap = (Capability<?>) field.get(null);
            
            for (Direction dir : Direction.values()) {
                LazyOptional<?> cap = target.getCapability(gtCap, dir);
                if (cap.isPresent()) {
                    Object container = cap.orElse(null);
                    if (container != null) {
                        Method inputsEnergyMethod = container.getClass().getMethod("inputsEnergy", Direction.class);
                        if ((Boolean) inputsEnergyMethod.invoke(container, dir)) {
                            // FE 转换为 EU (4 FE = 1 EU)
                            long amountEU = amountFE >> 2;
                            
                            Method getInputVoltageMethod = container.getClass().getMethod("getInputVoltage");
                            Method getInputAmperageMethod = container.getClass().getMethod("getInputAmperage");
                            
                            long voltage = (Long) getInputVoltageMethod.invoke(container);
                            long amperage = (Long) getInputAmperageMethod.invoke(container);
                            
                            long actualVoltage = Math.min(voltage, amountEU);
                            long actualAmperage = Math.min(amperage, amountEU / Math.max(actualVoltage, 1));
                            
                            if (!simulate) {
                                Method acceptEnergyMethod = container.getClass().getMethod("acceptEnergyFromNetwork", Direction.class, long.class, long.class);
                                long acceptedAmperage = (Long) acceptEnergyMethod.invoke(container, dir, actualVoltage, actualAmperage);
                                long transferredEU = actualVoltage * acceptedAmperage;
                                return transferredEU << 2; // EU 转回 FE
                            } else {
                                return (actualVoltage * actualAmperage) << 2;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }


    /**
     * 尝试推送能量到GregTech CEu机器
     * 能量转换：4 FE = 1 EU
     */
    private boolean tryPushGTEnergy(BlockEntity target) {
        try {
            // 获取格雷科技的能力
            Class<?> gtCapClass = Class.forName("com.gregtechceu.gtceu.api.capability.forge.GTCapability");
            java.lang.reflect.Field field = gtCapClass.getField("CAPABILITY_ENERGY_CONTAINER");
            Capability<?> gtCap = (Capability<?>) field.get(null);
            
            // 检查目标是否有格雷能力
            for (Direction dir : Direction.values()) {
                LazyOptional<?> cap = target.getCapability(gtCap, dir);
                if (cap.isPresent()) {
                    Object container = cap.orElse(null);
                    if (container != null) {
                        // 检查是否可以输入能量
                        Method inputsEnergyMethod = container.getClass().getMethod("inputsEnergy", Direction.class);
                        if ((Boolean) inputsEnergyMethod.invoke(container, dir)) {
                            return pushToGTContainer(container, dir);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 格雷科技未安装或版本不兼容
        }
        return false;
    }
    
    /**
     * 推送能量到格雷科技能量容器
     * 处理电压、电流和FE-EU转换
     */
    private boolean pushToGTContainer(Object container, Direction side) {
        try {
            // 获取容器信息
            Method getEnergyCanBeInsertedMethod = container.getClass().getMethod("getEnergyCanBeInserted");
            long demand = (Long) getEnergyCanBeInsertedMethod.invoke(container);
            if (demand == 0) return false;
            
            Method getInputVoltageMethod = container.getClass().getMethod("getInputVoltage");
            Method getInputAmperageMethod = container.getClass().getMethod("getInputAmperage");
            Method acceptEnergyMethod = container.getClass().getMethod("acceptEnergyFromNetwork", Direction.class, long.class, long.class);
            
            long voltage = (Long) getInputVoltageMethod.invoke(container);
            long amperage = (Long) getInputAmperageMethod.invoke(container);
            
            // 从邻居能量源获取能量（FE）
            // 优先级：AE2 Network (appflux) > Flux Networks (long) > Long Energy (long) > Forge Energy (int)
            
            // 0. 最优先：尝试从 AE2 网络提取能量（如果安装了 appflux）
            if (AE2FluxIntegration.isAvailable()) {
                long ae2Energy = tryExtractFromAE2Network(container, side, voltage, amperage, demand, acceptEnergyMethod);
                if (ae2Energy > 0) {
                    return true;
                }
            }
            
            // 1. 优先尝试 Flux Networks（支持 Long，无限制）
            Object sourceFlux = getNeighborFluxEnergy();
            if (sourceFlux != null) {
                return pushFluxToGT(sourceFlux, container, side, voltage, amperage, demand);
            }
            
            // 2. 尝试 Long 接口
            ILongEnergyStorage sourceLong = getNeighborLongEnergy();
            if (sourceLong != null) {
                return pushLongToGT(sourceLong, container, side, voltage, amperage, demand, acceptEnergyMethod);
            }
            
            // 3. 回退到标准 Forge Energy（限制为 Integer.MAX_VALUE）
            IEnergyStorage sourceEnergy = getNeighborForgeEnergy();
            if (sourceEnergy == null) {
                return false;
            }
            
            // 尝试提取尽可能多的能量（受限于 Integer.MAX_VALUE）
            int extractedFE = sourceEnergy.extractEnergy(Integer.MAX_VALUE, true);
            if (extractedFE == 0) return false;
            
            // FE 转换为 EU (4 FE = 1 EU)
            long amountEU = extractedFE >> 2;
            
            // 计算实际传输的电压：取(机器电压, 可用EU, 需求)的最小值
            long actualVoltage = Math.min(Math.min(voltage, amountEU), demand);
            if (actualVoltage == 0) return false;
            
            // 计算实际传输的电流：取(机器电流, 可用EU/电压)的最小值
            long actualAmperage = Math.min(amperage, amountEU / actualVoltage);
            
            // 调用格雷科技的接收方法
            long acceptedAmperage = (Long) acceptEnergyMethod.invoke(container, side, actualVoltage, actualAmperage);
            long transferredEU = actualVoltage * acceptedAmperage;
            
            if (transferredEU > 0) {
                // 从源实际提取对应的FE（受限于 int 范围）
                int actualExtractFE = (int) Math.min(transferredEU << 2, Integer.MAX_VALUE);
                sourceEnergy.extractEnergy(actualExtractFE, false);
                return true;
            }
        } catch (Exception e) {
            // 传输失败
        }
        return false;
    }
    
    /**
     * 使用Long能量接口推送到格雷科技
     */
    private boolean pushLongToGT(ILongEnergyStorage source, Object container, Direction side, 
                                  long voltage, long amperage, long demand, Method acceptEnergyMethod) {
        try {
            // 尝试提取尽可能多的能量
            long extractedFE = source.extractEnergyL(Long.MAX_VALUE, true);
            if (extractedFE == 0) return false;
            
            // FE 转换为 EU (4 FE = 1 EU)
            long amountEU = extractedFE >> 2;
            
            // 计算实际传输的电压：取(机器电压, 可用EU, 需求)的最小值
            long actualVoltage = Math.min(Math.min(voltage, amountEU), demand);
            if (actualVoltage == 0) return false;
            
            // 计算实际传输的电流：取(机器电流, 可用EU/电压)的最小值
            long actualAmperage = Math.min(amperage, amountEU / actualVoltage);
            
            // 调用格雷科技的接收方法
            long acceptedAmperage = (Long) acceptEnergyMethod.invoke(container, side, actualVoltage, actualAmperage);
            long transferredEU = actualVoltage * acceptedAmperage;
            
            if (transferredEU > 0) {
                // 从源实际提取对应的FE
                long actualExtractFE = transferredEU << 2;
                source.extractEnergyL(actualExtractFE, false);
                return true;
            }
        } catch (Exception e) {
            // 传输失败
        }
        return false;
    }
    
    /**
     * 尝试从 AE2 网络提取能量并推送到格雷科技
     * 
     * @return 实际传输的 EU 数量
     */
    private long tryExtractFromAE2Network(Object container, Direction side, 
                                          long voltage, long amperage, long demand, Method acceptEnergyMethod) {
        try {
            // 计算可以传输的 EU 数量
            long maxTransferEU = Math.min(voltage * amperage, demand);
            long maxTransferFE = maxTransferEU << 2; // EU 转 FE (乘以4)
            
            // 从自己的 AE2 网络提取 FE（模拟）
            long extractedFE = AE2FluxIntegration.extractEnergyFromOwnNetwork(this, maxTransferFE, true);
            if (extractedFE == 0) {
                return 0;
            }
            
            // FE 转换为 EU (4 FE = 1 EU)
            long amountEU = extractedFE >> 2;
            
            // 计算实际传输的电压：取(机器电压, 可用EU, 需求)的最小值
            long actualVoltage = Math.min(Math.min(voltage, amountEU), demand);
            if (actualVoltage == 0) {
                return 0;
            }
            
            // 计算实际传输的电流：取(机器电流, 可用EU/电压)的最小值
            long actualAmperage = Math.min(amperage, amountEU / actualVoltage);
            
            // 调用格雷科技的接收方法
            long acceptedAmperage = (Long) acceptEnergyMethod.invoke(container, side, actualVoltage, actualAmperage);
            long transferredEU = actualVoltage * acceptedAmperage;
            
            if (transferredEU > 0) {
                // 从自己的 AE2 网络实际提取对应的 FE
                long actualExtractFE = transferredEU << 2;
                AE2FluxIntegration.extractEnergyFromOwnNetwork(this, actualExtractFE, false);
                return transferredEU;
            }
        } catch (Exception e) {
            // 传输失败
        }
        return 0;
    }
    
    /**
     * 使用Flux Networks接口推送到格雷科技
     */
    private boolean pushFluxToGT(Object sourceFlux, Object container, Direction side,
                                  long voltage, long amperage, long demand) {
        try {
            Method extractMethod = sourceFlux.getClass().getMethod("extractEnergyL", long.class, boolean.class);
            Method acceptEnergyMethod = container.getClass().getMethod("acceptEnergyFromNetwork", Direction.class, long.class, long.class);
            
            // 尝试提取尽可能多的能量（Long.MAX_VALUE）
            long extractedFE = (Long) extractMethod.invoke(sourceFlux, Long.MAX_VALUE, true);
            if (extractedFE == 0) return false;
            
            // FE 转换为 EU (4 FE = 1 EU)
            long amountEU = extractedFE >> 2;
            
            // 计算实际传输的电压：取(机器电压, 可用EU, 需求)的最小值
            long actualVoltage = Math.min(Math.min(voltage, amountEU), demand);
            if (actualVoltage == 0) return false;
            
            // 计算实际传输的电流：取(机器电流, 可用EU/电压)的最小值
            long actualAmperage = Math.min(amperage, amountEU / actualVoltage);
            
            // 调用格雷科技的接收方法
            long acceptedAmperage = (Long) acceptEnergyMethod.invoke(container, side, actualVoltage, actualAmperage);
            long transferredEU = actualVoltage * acceptedAmperage;
            
            if (transferredEU > 0) {
                // 从Flux源实际提取对应的FE
                long actualExtractFE = transferredEU << 2;
                extractMethod.invoke(sourceFlux, actualExtractFE, false);
                return true;
            }
        } catch (Exception e) {
            // 传输失败
        }
        return false;
    }

    /**
     * 尝试使用Long能量接口推送能量（主动模式）
     * 优先使用Flux Networks接口
     */
    private boolean tryPushLongEnergy(BlockEntity target) {
        // 优先尝试Flux Networks接口（支持Long）
        Object sourceFlux = getNeighborFluxEnergy();
        if (sourceFlux != null) {
            return pushFluxEnergy(sourceFlux, target);
        }
        
        // 回退到自定义Long接口
        ILongEnergyStorage sourceEnergy = getNeighborLongEnergy();
        if (sourceEnergy == null) {
            return false; // 没有Long能量源
        }

        // 检查目标是否支持Long能量接收
        ILongEnergyStorage targetLongEnergy = null;
        for (Direction dir : Direction.values()) {
            LazyOptional<ILongEnergyStorage> cap = target.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir);
            if (cap.isPresent()) {
                targetLongEnergy = cap.orElse(null);
                if (targetLongEnergy != null && targetLongEnergy.canReceive()) {
                    break;
                }
                targetLongEnergy = null;
            }
        }

        if (targetLongEnergy != null) {
            // 双方都支持Long能量，进行超大值传输
            long extracted = sourceEnergy.extractEnergyL(MAX_TRANSFER, true);
            if (extracted > 0) {
                long inserted = targetLongEnergy.receiveEnergyL(extracted, false);
                if (inserted > 0) {
                    sourceEnergy.extractEnergyL(inserted, false);
                }
            }
            return true;
        }

        // 目标不支持Long能量，尝试标准能量接口
        IEnergyStorage targetEnergy = null;
        for (Direction dir : Direction.values()) {
            LazyOptional<IEnergyStorage> cap = target.getCapability(ForgeCapabilities.ENERGY, dir);
            if (cap.isPresent()) {
                targetEnergy = cap.orElse(null);
                if (targetEnergy != null && targetEnergy.canReceive()) {
                    break;
                }
                targetEnergy = null;
            }
        }

        if (targetEnergy != null) {
            // 源支持Long，目标只支持int，每次最多传输Integer.MAX_VALUE
            long extracted = sourceEnergy.extractEnergyL(Integer.MAX_VALUE, true);
            if (extracted > 0) {
                int inserted = targetEnergy.receiveEnergy((int) Math.min(extracted, Integer.MAX_VALUE), false);
                if (inserted > 0) {
                    sourceEnergy.extractEnergyL(inserted, false);
                }
            }
            return true;
        }

        return false;
    }
    
    /**
     * 从邻居获取Flux Networks能量接口
     */
    private Object getNeighborFluxEnergy() {
        if (level == null) return null;
        
        try {
            Class<?> fluxCapClass = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
            java.lang.reflect.Field field = fluxCapClass.getField("FN_ENERGY_STORAGE");
            Capability<?> fluxCap = (Capability<?>) field.get(null);
            
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = worldPosition.relative(dir);
                BlockEntity neighborBE = level.getBlockEntity(neighborPos);
                if (neighborBE != null && neighborBE != this) {
                    LazyOptional<?> cap = neighborBE.getCapability(fluxCap, dir.getOpposite());
                    if (cap.isPresent()) {
                        Object storage = cap.orElse(null);
                        if (storage != null) {
                            // 检查是否可以提取
                            Method canExtractMethod = storage.getClass().getMethod("canExtract");
                            if ((Boolean) canExtractMethod.invoke(storage)) {
                                return storage;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
    
    /**
     * 使用Flux Networks接口推送能量
     */
    private boolean pushFluxEnergy(Object sourceFlux, BlockEntity target) {
        try {
            Class<?> fluxCapClass = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
            java.lang.reflect.Field field = fluxCapClass.getField("FN_ENERGY_STORAGE");
            Capability<?> fluxCap = (Capability<?>) field.get(null);
            
            // 尝试获取目标的Flux接口
            Object targetFlux = null;
            for (Direction dir : Direction.values()) {
                LazyOptional<?> cap = target.getCapability(fluxCap, dir);
                if (cap.isPresent()) {
                    targetFlux = cap.orElse(null);
                    if (targetFlux != null) {
                        Method canReceiveMethod = targetFlux.getClass().getMethod("canReceive");
                        if ((Boolean) canReceiveMethod.invoke(targetFlux)) {
                            break;
                        }
                        targetFlux = null;
                    }
                }
            }
            
            if (targetFlux != null) {
                // 双方都支持Flux，使用Long传输
                Method extractMethod = sourceFlux.getClass().getMethod("extractEnergyL", long.class, boolean.class);
                Method receiveMethod = targetFlux.getClass().getMethod("receiveEnergyL", long.class, boolean.class);
                
                // 从源提取能量（模拟）
                long extracted = (Long) extractMethod.invoke(sourceFlux, Long.MAX_VALUE, true);
                if (extracted > 0) {
                    // 向目标插入能量（实际）
                    long inserted = (Long) receiveMethod.invoke(targetFlux, extracted, false);
                    if (inserted > 0) {
                        // 从源实际提取能量
                        extractMethod.invoke(sourceFlux, inserted, false);
                        return true;
                    }
                }
            } else {
                // 目标不支持Flux，尝试标准接口
                IEnergyStorage targetEnergy = null;
                for (Direction dir : Direction.values()) {
                    LazyOptional<IEnergyStorage> cap = target.getCapability(ForgeCapabilities.ENERGY, dir);
                    if (cap.isPresent()) {
                        targetEnergy = cap.orElse(null);
                        if (targetEnergy != null && targetEnergy.canReceive()) {
                            break;
                        }
                        targetEnergy = null;
                    }
                }
                
                if (targetEnergy != null) {
                    // 源支持Flux Long，目标只支持int
                    Method extractMethod = sourceFlux.getClass().getMethod("extractEnergyL", long.class, boolean.class);
                    long extracted = (Long) extractMethod.invoke(sourceFlux, (long) Integer.MAX_VALUE, true);
                    if (extracted > 0) {
                        int inserted = targetEnergy.receiveEnergy((int) Math.min(extracted, Integer.MAX_VALUE), false);
                        if (inserted > 0) {
                            extractMethod.invoke(sourceFlux, (long) inserted, false);
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 静默失败
        }
        return false;
    }
    
    /**
     * 从邻居获取Long能量存储
     */
    private ILongEnergyStorage getNeighborLongEnergy() {
        if (level == null) return null;
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE != null && neighborBE != this) {
                LazyOptional<ILongEnergyStorage> cap = neighborBE.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir.getOpposite());
                if (cap.isPresent()) {
                    ILongEnergyStorage storage = cap.orElse(null);
                    if (storage != null && storage.canExtract()) {
                        return storage;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 使用标准Forge Energy推送能量（回退方案，主动模式）
     */
    private void tryPushForgeEnergy(BlockEntity target) {
        // 从邻居获取能量源（标准接口）
        IEnergyStorage sourceEnergy = getNeighborForgeEnergy();
        if (sourceEnergy == null) return;

        // 获取目标的能量存储
        IEnergyStorage targetEnergy = null;
        for (Direction dir : Direction.values()) {
            LazyOptional<IEnergyStorage> cap = target.getCapability(ForgeCapabilities.ENERGY, dir);
            if (cap.isPresent()) {
                targetEnergy = cap.orElse(null);
                if (targetEnergy != null && targetEnergy.canReceive()) {
                    break;
                }
                targetEnergy = null;
            }
        }

        if (targetEnergy == null) return;

        // 从能量源提取能量，主动推送给目标
        // 注意：即使内部使用long，向标准机器推送时也限制为Integer.MAX_VALUE
        int extracted = sourceEnergy.extractEnergy(Integer.MAX_VALUE, true);
        if (extracted > 0) {
            // 主动推送到目标机器
            int inserted = targetEnergy.receiveEnergy(extracted, false);
            if (inserted > 0) {
                // 实际从源提取能量
                sourceEnergy.extractEnergy(inserted, false);
            }
        }
    }
    
    /**
     * 从邻居获取标准Forge能量存储
     */
    private IEnergyStorage getNeighborForgeEnergy() {
        if (level == null) return null;
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE != null && neighborBE != this) {
                LazyOptional<IEnergyStorage> cap = neighborBE.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite());
                if (cap.isPresent()) {
                    IEnergyStorage storage = cap.orElse(null);
                    if (storage != null && storage.canExtract()) {
                        return storage;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 添加连接
     */
    public void addLink(BlockPos other) {
        if (other.equals(this.getBlockPos())) return;
        if (this.links.add(other)) {
            this.setChanged();
        }
    }

    /**
     * 移除连接
     */
    public void removeLink(BlockPos other) {
        if (this.links.remove(other)) {
            this.setChanged();
        }
    }

    /**
     * 获取所有连接
     */
    public Set<BlockPos> getLinks() {
        return Collections.unmodifiableSet(links);
    }
    
    /**
     * 获取客户端连接列表（用于渲染）
     */
    public List<BlockPos> getClientLinks() {
        return clientLinks;
    }
    
    @Override
    protected void writeToStream(FriendlyByteBuf data) {
        super.writeToStream(data);
        // 同步当前连接目标集合
        data.writeVarInt(this.lastSyncedLinks.size());
        for (BlockPos p : this.lastSyncedLinks) {
            data.writeBlockPos(p);
        }
    }

    @Override
    protected boolean readFromStream(FriendlyByteBuf data) {
        boolean changed = super.readFromStream(data);
        int n = data.readVarInt();
        List<BlockPos> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(data.readBlockPos());
        }
        boolean linksChanged = !list.equals(this.clientLinks);
        this.clientLinks = list;
        return changed || linksChanged;
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag list = new ListTag();
        for (BlockPos p : this.links) {
            CompoundTag t = new CompoundTag();
            t.putInt("x", p.getX());
            t.putInt("y", p.getY());
            t.putInt("z", p.getZ());
            list.add(t);
        }
        tag.put("links", list);
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        this.links.clear();
        if (tag.contains("links", Tag.TAG_LIST)) {
            ListTag list = tag.getList("links", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                BlockPos pos = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
                this.links.add(pos);
            }
        }
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (!isRemoved()) {
            // 检查是否为Flux Networks的能力
            if (isFluxEnergyCapability(cap)) {
                final int index = side == null ? 0 : side.get3DDataValue() + 1;
                LazyOptional<?> handler = energyCaps[index];
                if (handler == null) {
                    Object fluxAdapter = createFluxEnergyAdapter(side);
                    if (fluxAdapter != null) {
                        handler = LazyOptional.of(() -> fluxAdapter);
                        energyCaps[index] = handler;
                    }
                }
                if (handler != null) {
                    return handler.cast();
                }
            }
            
            // 标准能量能力
            if (cap == ForgeCapabilities.ENERGY || cap == MEBFCapabilities.LONG_ENERGY_STORAGE) {
                final int index = side == null ? 0 : side.get3DDataValue() + 1;
                LazyOptional<?> handler = energyCaps[index];
                if (handler == null) {
                    final TowerEnergyStorage storage = new TowerEnergyStorage(side);
                    handler = LazyOptional.of(() -> storage);
                    energyCaps[index] = handler;
                }
                return handler.cast();
            }
        }
        return super.getCapability(cap, side);
    }
    
    /**
     * 检查是否为Flux Networks的能量能力
     */
    private boolean isFluxEnergyCapability(Capability<?> cap) {
        try {
            Class<?> fluxCapClass = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
            java.lang.reflect.Field field = fluxCapClass.getField("FN_ENERGY_STORAGE");
            Capability<?> fluxCap = (Capability<?>) field.get(null);
            return cap == fluxCap;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 创建Flux Networks能量适配器（使用动态代理）
     */
    private Object createFluxEnergyAdapter(Direction side) {
        try {
            Class<?> interfaceClass = Class.forName("sonar.fluxnetworks.api.energy.IFNEnergyStorage");
            
            return java.lang.reflect.Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{interfaceClass},
                (proxy, method, args) -> handleFluxMethod(method.getName(), args, side)
            );
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 处理Flux Networks接口方法调用
     */
    private Object handleFluxMethod(String methodName, Object[] args, Direction side) {
        switch (methodName) {
            case "extractEnergyL":
                return handleExtractEnergyL(args);
            case "receiveEnergyL":
                return 0L; // 感应塔主要用于输出
            case "getEnergyStoredL":
                return getNeighborEnergyStoredL();
            case "getMaxEnergyStoredL":
                return getNeighborMaxEnergyStoredL();
            case "canExtract":
                return canNeighborExtract();
            case "canReceive":
                return false; // 感应塔主要用于输出
            default:
                return null;
        }
    }
    
    /**
     * 从邻居提取能量（Flux Networks long版本）
     */
    private long handleExtractEnergyL(Object[] args) {
        if (args == null || args.length < 2) return 0L;
        long maxExtract = (Long) args[0];
        boolean simulate = (Boolean) args[1];
        
        if (maxExtract <= 0 || level == null) return 0L;
        
        // 尝试从邻居提取（优先Flux Networks接口）
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE != null && neighborBE != this) {
                // 尝试Flux Networks接口
                long extracted = tryExtractFluxEnergy(neighborBE, dir, maxExtract, simulate);
                if (extracted > 0) return extracted;
                
                // 回退到标准接口
                IEnergyStorage storage = getForgeEnergyStorage(neighborBE, dir.getOpposite());
                if (storage != null && storage.canExtract()) {
                    int maxInt = (int) Math.min(maxExtract, Integer.MAX_VALUE);
                    return storage.extractEnergy(maxInt, simulate);
                }
            }
        }
        return 0L;
    }
    
    /**
     * 尝试使用Flux Networks接口提取能量
     */
    private long tryExtractFluxEnergy(BlockEntity be, Direction side, long maxExtract, boolean simulate) {
        try {
            Class<?> fluxCapClass = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
            java.lang.reflect.Field field = fluxCapClass.getField("FN_ENERGY_STORAGE");
            Capability<?> fluxCap = (Capability<?>) field.get(null);
            
            LazyOptional<?> cap = be.getCapability(fluxCap, side.getOpposite());
            if (cap.isPresent()) {
                Object storage = cap.orElse(null);
                if (storage != null) {
                    Method extractMethod = storage.getClass().getMethod("extractEnergyL", long.class, boolean.class);
                    return (Long) extractMethod.invoke(storage, maxExtract, simulate);
                }
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }
    
    /**
     * 获取邻居的能量存储量
     */
    private long getNeighborEnergyStoredL() {
        if (level == null) return 0L;
        
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE != null && neighborBE != this) {
                long stored = tryGetFluxEnergyStored(neighborBE, dir);
                if (stored > 0) return stored;
                
                IEnergyStorage storage = getForgeEnergyStorage(neighborBE, dir.getOpposite());
                if (storage != null) {
                    return storage.getEnergyStored();
                }
            }
        }
        return 0L;
    }
    
    /**
     * 获取邻居的最大能量存储量
     */
    private long getNeighborMaxEnergyStoredL() {
        if (level == null) return Long.MAX_VALUE;
        
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE != null && neighborBE != this) {
                long max = tryGetFluxMaxEnergyStored(neighborBE, dir);
                if (max > 0) return max;
                
                IEnergyStorage storage = getForgeEnergyStorage(neighborBE, dir.getOpposite());
                if (storage != null) {
                    return storage.getMaxEnergyStored();
                }
            }
        }
        return Long.MAX_VALUE;
    }
    
    /**
     * 检查邻居是否可以提取能量
     */
    private boolean canNeighborExtract() {
        if (level == null) return false;
        
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE != null && neighborBE != this) {
                IEnergyStorage storage = getForgeEnergyStorage(neighborBE, dir.getOpposite());
                if (storage != null && storage.canExtract()) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 尝试获取Flux Networks的能量存储量
     */
    private long tryGetFluxEnergyStored(BlockEntity be, Direction side) {
        try {
            Class<?> fluxCapClass = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
            java.lang.reflect.Field field = fluxCapClass.getField("FN_ENERGY_STORAGE");
            Capability<?> fluxCap = (Capability<?>) field.get(null);
            
            LazyOptional<?> cap = be.getCapability(fluxCap, side.getOpposite());
            if (cap.isPresent()) {
                Object storage = cap.orElse(null);
                if (storage != null) {
                    Method method = storage.getClass().getMethod("getEnergyStoredL");
                    return (Long) method.invoke(storage);
                }
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }
    
    /**
     * 尝试获取Flux Networks的最大能量存储量
     */
    private long tryGetFluxMaxEnergyStored(BlockEntity be, Direction side) {
        try {
            Class<?> fluxCapClass = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
            java.lang.reflect.Field field = fluxCapClass.getField("FN_ENERGY_STORAGE");
            Capability<?> fluxCap = (Capability<?>) field.get(null);
            
            LazyOptional<?> cap = be.getCapability(fluxCap, side.getOpposite());
            if (cap.isPresent()) {
                Object storage = cap.orElse(null);
                if (storage != null) {
                    Method method = storage.getClass().getMethod("getMaxEnergyStoredL");
                    return (Long) method.invoke(storage);
                }
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }
    
    /**
     * 获取标准Forge能量存储
     */
    private IEnergyStorage getForgeEnergyStorage(BlockEntity be, Direction side) {
        LazyOptional<IEnergyStorage> cap = be.getCapability(ForgeCapabilities.ENERGY, side);
        return cap.orElse(null);
    }
    
    /**
     * 能量适配器，将邻居的能量存储包装为支持Long的接口
     */
    private class TowerEnergyStorage implements IEnergyStorage, ILongEnergyStorage {
        @Nullable
        private final Direction side;
        
        public TowerEnergyStorage(@Nullable Direction side) {
            this.side = side;
        }
        
        /**
         * 获取邻居的能量存储（优先Long接口）
         */
        @Nullable
        private Object getNeighborStorage() {
            if (level == null) return null;
            
            // 检查所有方向的邻居
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = worldPosition.relative(dir);
                BlockEntity neighborBE = level.getBlockEntity(neighborPos);
                if (neighborBE != null && neighborBE != WirelessEnergyTowerBlockEntity.this) {
                    // 优先尝试Long能量接口
                    LazyOptional<ILongEnergyStorage> longCap = neighborBE.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir.getOpposite());
                    if (longCap.isPresent()) {
                        return longCap.orElse(null);
                    }
                    // 回退到标准能量接口
                    LazyOptional<IEnergyStorage> normalCap = neighborBE.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite());
                    if (normalCap.isPresent()) {
                        return normalCap.orElse(null);
                    }
                }
            }
            return null;
        }
        
        ///// 标准 Forge Energy 接口 \\\\\
        
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            Object storage = getNeighborStorage();
            if (storage instanceof ILongEnergyStorage longStorage) {
                return (int) Math.min(longStorage.receiveEnergyL(maxReceive, simulate), Integer.MAX_VALUE);
            } else if (storage instanceof IEnergyStorage normalStorage) {
                return normalStorage.receiveEnergy(maxReceive, simulate);
            }
            return 0;
        }
        
        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            Object storage = getNeighborStorage();
            if (storage instanceof ILongEnergyStorage longStorage) {
                return (int) Math.min(longStorage.extractEnergyL(maxExtract, simulate), Integer.MAX_VALUE);
            } else if (storage instanceof IEnergyStorage normalStorage) {
                return normalStorage.extractEnergy(maxExtract, simulate);
            }
            return 0;
        }
        
        @Override
        public int getEnergyStored() {
            return (int) Math.min(getEnergyStoredL(), Integer.MAX_VALUE);
        }
        
        @Override
        public int getMaxEnergyStored() {
            return (int) Math.min(getMaxEnergyStoredL(), Integer.MAX_VALUE);
        }
        
        @Override
        public boolean canExtract() {
            Object storage = getNeighborStorage();
            if (storage instanceof ILongEnergyStorage longStorage) {
                return longStorage.canExtract();
            } else if (storage instanceof IEnergyStorage normalStorage) {
                return normalStorage.canExtract();
            }
            return false;
        }
        
        @Override
        public boolean canReceive() {
            Object storage = getNeighborStorage();
            if (storage instanceof ILongEnergyStorage longStorage) {
                return longStorage.canReceive();
            } else if (storage instanceof IEnergyStorage normalStorage) {
                return normalStorage.canReceive();
            }
            return false;
        }
        
        ///// Long 能量接口 \\\\\
        
        @Override
        public long receiveEnergyL(long maxReceive, boolean simulate) {
            Object storage = getNeighborStorage();
            if (storage instanceof ILongEnergyStorage longStorage) {
                return longStorage.receiveEnergyL(maxReceive, simulate);
            } else if (storage instanceof IEnergyStorage normalStorage) {
                return normalStorage.receiveEnergy((int) Math.min(maxReceive, Integer.MAX_VALUE), simulate);
            }
            return 0;
        }
        
        @Override
        public long extractEnergyL(long maxExtract, boolean simulate) {
            Object storage = getNeighborStorage();
            if (storage instanceof ILongEnergyStorage longStorage) {
                return longStorage.extractEnergyL(maxExtract, simulate);
            } else if (storage instanceof IEnergyStorage normalStorage) {
                return normalStorage.extractEnergy((int) Math.min(maxExtract, Integer.MAX_VALUE), simulate);
            }
            return 0;
        }
        
        @Override
        public long getEnergyStoredL() {
            Object storage = getNeighborStorage();
            if (storage instanceof ILongEnergyStorage longStorage) {
                return longStorage.getEnergyStoredL();
            } else if (storage instanceof IEnergyStorage normalStorage) {
                return normalStorage.getEnergyStored();
            }
            return 0;
        }
        
        @Override
        public long getMaxEnergyStoredL() {
            Object storage = getNeighborStorage();
            if (storage instanceof ILongEnergyStorage longStorage) {
                return longStorage.getMaxEnergyStoredL();
            } else if (storage instanceof IEnergyStorage normalStorage) {
                return normalStorage.getMaxEnergyStored();
            }
            return 0;
        }
    }
    
    /**
     * 扩展渲染边界框，确保远距离光束能够正常渲染
     */
    @OnlyIn(Dist.CLIENT)
    @Override
    public AABB getRenderBoundingBox() {
        // 如果没有连接目标，使用默认边界框
        if (clientLinks == null || clientLinks.isEmpty()) {
            BlockPos pos = getBlockPos();
            return new AABB(pos.getX() - 5, pos.getY() - 5, pos.getZ() - 5, 
                           pos.getX() + 6, pos.getY() + 8, pos.getZ() + 6); // Y轴考虑塔的高度
        }

        BlockPos pos = getBlockPos();
        // 计算包含所有目标的边界框（从塔顶开始）
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = pos.getX() + 1;
        double maxY = pos.getY() + 3; // 塔高3格
        double maxZ = pos.getZ() + 1;

        for (BlockPos target : clientLinks) {
            minX = Math.min(minX, target.getX());
            minY = Math.min(minY, target.getY());
            minZ = Math.min(minZ, target.getZ());
            maxX = Math.max(maxX, target.getX() + 1);
            maxY = Math.max(maxY, target.getY() + 1);
            maxZ = Math.max(maxZ, target.getZ() + 1);
        }

        // 扩大边界框，确保光束在各个角度都能正常渲染
        double expansion = 5.0;
        return new AABB(minX - expansion, minY - expansion, minZ - expansion, 
                       maxX + expansion, maxY + expansion, maxZ + expansion);
    }
} 