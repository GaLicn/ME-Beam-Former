package com.mebeamformer.blockentity;

import appeng.api.networking.GridFlags;
import appeng.blockentity.grid.AENetworkBlockEntity;
import com.mebeamformer.ME_Beam_Former;
import com.mebeamformer.connection.WirelessEnergyNetwork;
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
 * 无线能源感应塔。
 * 能量转发由 {@link WirelessEnergyNetwork} 统一驱动。
 */
public class WirelessEnergyTowerBlockEntity extends AENetworkBlockEntity implements ILinkable {

    // 反射缓存（所有实例共享）
    // Flux Networks
    private static volatile boolean FLUX_INITIALIZED = false;
    private static Class<?> FLUX_CAP_CLASS = null;
    private static Capability<?> FLUX_CAPABILITY = null;
    private static Method FLUX_EXTRACT_METHOD = null;
    private static Method FLUX_RECEIVE_METHOD = null;
    private static Method FLUX_CAN_EXTRACT_METHOD = null;
    private static Method FLUX_CAN_RECEIVE_METHOD = null;
    private static Method FLUX_GET_ENERGY_STORED_METHOD = null;
    private static Method FLUX_GET_MAX_ENERGY_STORED_METHOD = null;
    
    // GregTech
    private static volatile boolean GT_INITIALIZED = false;
    private static Class<?> GT_CAP_CLASS = null;
    private static Capability<?> GT_CAPABILITY = null;
    private static Method GT_INPUTS_ENERGY_METHOD = null;
    private static Method GT_ACCEPT_ENERGY_METHOD = null;
    private static Method GT_GET_INPUT_VOLTAGE_METHOD = null;
    private static Method GT_GET_INPUT_AMPERAGE_METHOD = null;
    private static Method GT_GET_ENERGY_CAN_BE_INSERTED_METHOD = null;
    
    // 邻居能量源缓存
    private static class NeighborEnergyCache {
        Direction direction;
        BlockPos position;
        Object energyHandler; // 存储实际的能量接口对象
        EnergySourceType type;
        long lastValidatedTick;
    }
    
    private enum EnergySourceType {
        FLUX_NETWORKS,
        LONG_ENERGY,
        FORGE_ENERGY,
        GREGTECH,
        NONE
    }
    
    private NeighborEnergyCache energySourceCache = null;
    private static final int CACHE_VALIDITY_TICKS = 40; // 2秒缓存有效期

    private final Set<BlockPos> links = new HashSet<>();
    private List<BlockPos> clientLinks = Collections.emptyList();
    private final Set<BlockPos> lastSyncedLinks = new HashSet<>();
    private static final long MAX_TRANSFER = Long.MAX_VALUE;

    // 能量能力缓存
    private final LazyOptional<?>[] forgeEnergyCaps = new LazyOptional[7]; // 标准 Forge Energy
    private final LazyOptional<?>[] longEnergyCaps = new LazyOptional[7]; // Long Energy
    private final LazyOptional<?>[] fluxEnergyCaps = new LazyOptional[7]; // Flux Networks Energy

    public WirelessEnergyTowerBlockEntity(BlockPos pos, BlockState state) {
        super(ME_Beam_Former.WIRELESS_ENERGY_TOWER_BE.get(), pos, state);

        // AE2 节点：需要频道，空闲功耗 0
        this.getMainNode()
            .setFlags(GridFlags.REQUIRE_CHANNEL)
            .setIdlePowerUsage(0.0);
    }

    /**
     * 初始化 Flux Networks 反射缓存。
     */
    private static void initFluxReflection() {
        if (FLUX_INITIALIZED) return;
        synchronized (WirelessEnergyTowerBlockEntity.class) {
            if (FLUX_INITIALIZED) return;
            try {
                FLUX_CAP_CLASS = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
                java.lang.reflect.Field field = FLUX_CAP_CLASS.getField("FN_ENERGY_STORAGE");
                FLUX_CAPABILITY = (Capability<?>) field.get(null);
                
                Class<?> storageClass = Class.forName("sonar.fluxnetworks.api.energy.IFNEnergyStorage");
                FLUX_EXTRACT_METHOD = storageClass.getMethod("extractEnergyL", long.class, boolean.class);
                FLUX_RECEIVE_METHOD = storageClass.getMethod("receiveEnergyL", long.class, boolean.class);
                FLUX_CAN_EXTRACT_METHOD = storageClass.getMethod("canExtract");
                FLUX_CAN_RECEIVE_METHOD = storageClass.getMethod("canReceive");
                FLUX_GET_ENERGY_STORED_METHOD = storageClass.getMethod("getEnergyStoredL");
                FLUX_GET_MAX_ENERGY_STORED_METHOD = storageClass.getMethod("getMaxEnergyStoredL");
            } catch (Exception e) {
                // Flux Networks 未安装或版本不兼容
            }
            FLUX_INITIALIZED = true;
        }
    }
    
    /**
     * 初始化 GregTech 反射缓存。
     */
    private static void initGTReflection() {
        if (GT_INITIALIZED) return;
        synchronized (WirelessEnergyTowerBlockEntity.class) {
            if (GT_INITIALIZED) return;
            try {
                GT_CAP_CLASS = Class.forName("com.gregtechceu.gtceu.api.capability.forge.GTCapability");
                java.lang.reflect.Field field = GT_CAP_CLASS.getField("CAPABILITY_ENERGY_CONTAINER");
                GT_CAPABILITY = (Capability<?>) field.get(null);
                
                // 不预加载具体的容器方法，因为接口可能有多个实现
                // 在实际使用时再获取方法
            } catch (Exception e) {
                // GregTech 未安装或版本不兼容
            }
            GT_INITIALIZED = true;
        }
    }
    
    @Override
    public void onLoad() {
        super.onLoad();

        // 注册到全局网络
        if (level != null && !level.isClientSide) {
            WirelessEnergyNetwork.getInstance().registerTower(this);
        }
    }
    
    @Override
    public void setRemoved() {
        super.setRemoved();

        // 从全局网络注销
        if (level != null && !level.isClientSide) {
            WirelessEnergyNetwork.getInstance().unregisterTower(this);
        }
        invalidateEnergyCaps();
    }
    
    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        invalidateEnergyCaps();
    }
    
    private void invalidateEnergyCaps() {
        for (int i = 0; i < forgeEnergyCaps.length; i++) {
            if (forgeEnergyCaps[i] != null) {
                forgeEnergyCaps[i].invalidate();
                forgeEnergyCaps[i] = null;
            }
        }
        for (int i = 0; i < longEnergyCaps.length; i++) {
            if (longEnergyCaps[i] != null) {
                longEnergyCaps[i].invalidate();
                longEnergyCaps[i] = null;
            }
        }
        for (int i = 0; i < fluxEnergyCaps.length; i++) {
            if (fluxEnergyCaps[i] != null) {
                fluxEnergyCaps[i].invalidate();
                fluxEnergyCaps[i] = null;
            }
        }
    }

    // tick 由 WirelessEnergyNetwork 统一处理

    /**
     * 获取上次同步的连接列表（供全局管理器使用）
     */
    public Set<BlockPos> getLastSyncedLinks() {
        return Collections.unmodifiableSet(lastSyncedLinks);
    }
    
    /**
     * 更新同步的连接列表（供全局管理器使用）
     */
    public void updateSyncedLinks(Set<BlockPos> validLinks) {
        this.lastSyncedLinks.clear();
        this.lastSyncedLinks.addAll(validLinks);
        this.markForUpdate();
    }

    /**
     * 向目标推送能量（供全局网络调用）。
     * 优先级：AE2(appflux) -> GregTech -> Long -> Forge。
     */
    public void pushEnergyToTarget(BlockEntity target) {
        if (level == null) return;

        // 目标为塔时，进行网络分配
        if (target instanceof WirelessEnergyTowerBlockEntity targetTower) {
            pushEnergyToTower(targetTower);
            return;
        }

        // AE2 网络
        if (AE2FluxIntegration.isAvailable()) {
            boolean transferred = tryPushFromAE2Network(target);
            if (transferred) return;
        }

        // GregTech
        boolean transferred = tryPushGTEnergy(target);
        if (transferred) return;

        // Long / Forge
        transferred = tryPushLongEnergy(target);
        if (!transferred) {
            // 回退到标准Forge Energy
            tryPushForgeEnergy(target);
        }
    }
    
    /**
     * 尝试从AE2网络提取能量并推送到目标设备
     */
    private boolean tryPushFromAE2Network(BlockEntity target) {
        // 尝试从AE2网络提取能量（模拟）
        long extracted = AE2FluxIntegration.extractEnergyFromOwnNetwork(this, MAX_TRANSFER, true);
        if (extracted <= 0) return false;
        
        // 尝试推送到目标（实际）
        long inserted = pushEnergyToTargetDirect(target, extracted, false);
        if (inserted > 0) {
            // 从AE2网络实际提取对应的能量
            AE2FluxIntegration.extractEnergyFromOwnNetwork(this, inserted, false);
            return true;
        }
        
        return false;
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
     * 接收来自其他感应塔转发的能量（被动接收模式）
     * 用于塔到塔的递归转发，防止循环
     * 
     * @param amount 要接收的能量
     * @param simulate 是否模拟
     * @param visited 已访问的塔的位置集合（防止循环）
     * @return 实际接收并转发的能量
     */
    private long receiveEnergyFromTower(long amount, boolean simulate, Set<BlockPos> visited) {
        if (level == null || amount <= 0) return 0;
        
        // 标记当前塔为已访问，防止循环
        if (!visited.add(this.worldPosition)) {
            return 0; // 已经访问过，避免循环
        }
        
        long totalInserted = 0;
        
        // 分配给当前塔的邻居设备（不是感应塔的设备）
        for (Direction dir : Direction.values()) {
            if (totalInserted >= amount) break;
            
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE != null && !(neighborBE instanceof WirelessEnergyTowerBlockEntity)) {
                long remaining = amount - totalInserted;
                long inserted = pushEnergyToTargetDirect(neighborBE, remaining, simulate);
                totalInserted += inserted;
            }
        }
        
        // 分配给当前塔绑定的普通设备（非感应塔）
        if (totalInserted < amount && !links.isEmpty()) {
            for (BlockPos targetPos : new HashSet<>(links)) {
                if (totalInserted >= amount) break;
                
                BlockEntity targetBE = level.getBlockEntity(targetPos);
                if (targetBE == null) continue;
                
                if (!(targetBE instanceof WirelessEnergyTowerBlockEntity)) {
                    long remaining = amount - totalInserted;
                    long inserted = pushEnergyToTargetDirect(targetBE, remaining, simulate);
                    totalInserted += inserted;
                }
            }
        }
        
        // 将剩余能量递归分配给连接的其他感应塔
        if (totalInserted < amount && !links.isEmpty()) {
            for (BlockPos targetPos : new HashSet<>(links)) {
                if (totalInserted >= amount) break;
                
                BlockEntity targetBE = level.getBlockEntity(targetPos);
                if (targetBE instanceof WirelessEnergyTowerBlockEntity targetTower) {
                    if (!visited.contains(targetPos)) {
                        long remaining = amount - totalInserted;
                        long inserted = targetTower.receiveEnergyFromTower(remaining, simulate, visited);
                        totalInserted += inserted;
                    }
                }
            }
        }
        
        return totalInserted;
    }
    
    /**
     * 在塔网络中分配能量（队列遍历，避免递归）。
     */
    private long distributeEnergyInNetwork(long amount, boolean simulate, Set<BlockPos> visited) {
        if (level == null || amount <= 0) return 0;
        
        // 使用队列遍历
        java.util.Queue<WirelessEnergyTowerBlockEntity> towerQueue = new java.util.LinkedList<>();
        towerQueue.add(this);
        visited.add(this.worldPosition);
        
        long totalInserted = 0;
        
        // 迭代处理每个塔
        while (!towerQueue.isEmpty() && totalInserted < amount) {
            WirelessEnergyTowerBlockEntity currentTower = towerQueue.poll();
            long remaining = amount - totalInserted;
            
            // 1. 先分配给当前塔的邻居设备（非塔）
            for (Direction dir : Direction.values()) {
                if (totalInserted >= amount) break;
                
                BlockPos neighborPos = currentTower.worldPosition.relative(dir);
                BlockEntity neighborBE = level.getBlockEntity(neighborPos);
                if (neighborBE != null && !(neighborBE instanceof WirelessEnergyTowerBlockEntity)) {
                    long neighborRemaining = amount - totalInserted;
                    
                    // 优先尝试Flux Networks接口（支持Long，无限制）
                    long fluxInserted = tryInsertFluxEnergy(neighborBE, dir.getOpposite(), neighborRemaining, simulate);
                    if (fluxInserted > 0) {
                        totalInserted += fluxInserted;
                        continue;
                    }
                    
                    // 尝试Long接口（支持超大值传输）
                    try {
                        LazyOptional<ILongEnergyStorage> longCap = neighborBE.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir.getOpposite());
                        if (longCap.isPresent()) {
                            ILongEnergyStorage storage = longCap.resolve().orElse(null);
                            if (storage != null && storage.canReceive()) {
                                long inserted = storage.receiveEnergyL(neighborRemaining, simulate);
                                totalInserted += inserted;
                                continue;
                            }
                        }
                    } catch (ClassCastException e) {
                        // 跳过不兼容的能力实现
                    }
                    
                    // 回退到标准接口（分批传输突破INT_MAX）
                    try {
                        LazyOptional<IEnergyStorage> normalCap = neighborBE.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite());
                        if (normalCap.isPresent()) {
                            IEnergyStorage storage = normalCap.resolve().orElse(null);
                            if (storage != null && storage.canReceive()) {
                                if (simulate) {
                                    int batchSize = (int) Math.min(neighborRemaining, Integer.MAX_VALUE);
                                    int inserted = storage.receiveEnergy(batchSize, true);
                                    totalInserted += inserted;
                                } else {
                                    long batchRemaining = neighborRemaining;
                                    while (batchRemaining > 0 && totalInserted < amount) {
                                        int batchSize = (int) Math.min(batchRemaining, Integer.MAX_VALUE);
                                        int inserted = storage.receiveEnergy(batchSize, false);
                                        if (inserted == 0) break;
                                        totalInserted += inserted;
                                        batchRemaining -= inserted;
                                    }
                                }
                            }
                        }
                    } catch (ClassCastException e) {
                        // 跳过不兼容的能力实现
                    }
                }
            }
            
            // 2. 分配给当前塔连接的普通设备（非感应塔）
            if (totalInserted < amount && !currentTower.links.isEmpty()) {
                for (BlockPos targetPos : new HashSet<>(currentTower.links)) {
                    if (totalInserted >= amount) break;
                    
                    BlockEntity targetBE = level.getBlockEntity(targetPos);
                    if (targetBE == null || targetBE instanceof WirelessEnergyTowerBlockEntity) {
                        continue;
                    }
                    
                    long targetRemaining = amount - totalInserted;
                    long inserted = pushEnergyToTargetDirect(targetBE, targetRemaining, simulate);
                    totalInserted += inserted;
                }
            }
            
            // 3. 将连接的其他感应塔加入队列（非递归）
            if (totalInserted < amount && !currentTower.links.isEmpty()) {
                for (BlockPos targetPos : currentTower.links) {
                    if (visited.contains(targetPos)) continue;
                    
                    BlockEntity targetBE = level.getBlockEntity(targetPos);
                    if (targetBE instanceof WirelessEnergyTowerBlockEntity targetTower) {
                        visited.add(targetPos);
                        towerQueue.add(targetTower);
                    }
                }
            }
        }
        
        return totalInserted;
    }
    
    /**
     * 直接推送能量到目标设备（用于能量分配）
     * 返回实际插入的能量
     * 支持超过INT_MAX的传输
     * 优先级：Flux Networks (Long) > GregTech > Long接口 > 标准接口（分批）
     */
    private long pushEnergyToTargetDirect(BlockEntity target, long amount, boolean simulate) {
        if (level == null || amount <= 0) return 0;
        
        // 优先尝试Flux Networks接口（支持Long，无限制）
        for (Direction dir : Direction.values()) {
            long fluxInserted = tryInsertFluxEnergy(target, dir, amount, simulate);
            if (fluxInserted > 0) return fluxInserted;
        }
        
        // 尝试格雷科技（支持Long）
        long inserted = tryPushGTEnergyDirect(target, amount, simulate);
        if (inserted > 0) return inserted;
        
        // 尝试Long能量接口（支持超大值）
        for (Direction dir : Direction.values()) {
            try {
                LazyOptional<ILongEnergyStorage> longCap = target.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir);
                if (longCap.isPresent()) {
                    ILongEnergyStorage storage = longCap.resolve().orElse(null);
                    if (storage != null && storage.canReceive()) {
                        return storage.receiveEnergyL(amount, simulate);
                    }
                }
            } catch (ClassCastException e) {
                // 跳过不兼容的能力实现
            }
        }
        
        // 回退到标准Forge Energy（分批传输突破INT_MAX）
        for (Direction dir : Direction.values()) {
            try {
                LazyOptional<IEnergyStorage> cap = target.getCapability(ForgeCapabilities.ENERGY, dir);
                if (cap.isPresent()) {
                    IEnergyStorage storage = cap.resolve().orElse(null);
                    if (storage != null && storage.canReceive()) {
                        if (simulate) {
                            // 模拟模式：单次传输，取最大可能值
                            int batchSize = (int) Math.min(amount, Integer.MAX_VALUE);
                            return storage.receiveEnergy(batchSize, true);
                        } else {
                            // 实际传输：分批传输直到完成或设备满
                            long totalInserted = 0;
                            long remaining = amount;
                            
                            while (remaining > 0) {
                                int batchSize = (int) Math.min(remaining, Integer.MAX_VALUE);
                                int batchInserted = storage.receiveEnergy(batchSize, false);
                                if (batchInserted == 0) break; // 设备已满
                                totalInserted += batchInserted;
                                remaining -= batchInserted;
                            }
                            
                            return totalInserted;
                        }
                    }
                }
            } catch (ClassCastException e) {
                // 跳过不兼容的能力实现
            }
        }
        
        return 0;
    }
    
    /**
     * 尝试使用 Flux Networks 接口插入能量。
     */
    private long tryInsertFluxEnergy(BlockEntity target, Direction side, long amount, boolean simulate) {
        initFluxReflection(); // 确保已初始化
        if (FLUX_CAPABILITY == null) return 0; // Flux Networks 未安装
        
        try {
            LazyOptional<?> cap = target.getCapability(FLUX_CAPABILITY, side);
            if (cap.isPresent()) {
                Object storage = cap.resolve().orElse(null);
                if (storage != null) {
                    // 使用缓存的 Method 对象
                    if ((Boolean) FLUX_CAN_RECEIVE_METHOD.invoke(storage)) {
                        return (Long) FLUX_RECEIVE_METHOD.invoke(storage, amount, simulate);
                    }
                }
            }
        } catch (Exception ignored) {
            // 调用失败，静默处理
        }
        return 0;
    }
    
    /**
     * 直接推送能量到 GregTech 设备。
     */
    private long tryPushGTEnergyDirect(BlockEntity target, long amountFE, boolean simulate) {
        initGTReflection(); // 确保已初始化
        if (GT_CAPABILITY == null) return 0; // GregTech 未安装
        
        try {
            for (Direction dir : Direction.values()) {
                LazyOptional<?> cap = target.getCapability(GT_CAPABILITY, dir);
                if (cap.isPresent()) {
                    Object container = cap.resolve().orElse(null);
                    if (container != null) {
                        // 动态获取方法（不同的GT容器实现可能不同）
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
     * 尝试推送能量到 GregTech CEu 机器（4 FE = 1 EU）。
     */
    private boolean tryPushGTEnergy(BlockEntity target) {
        initGTReflection(); // 确保已初始化
        if (GT_CAPABILITY == null) return false; // GregTech 未安装
        
        try {
            // 检查目标是否有格雷能力
            for (Direction dir : Direction.values()) {
                LazyOptional<?> cap = target.getCapability(GT_CAPABILITY, dir);
                if (cap.isPresent()) {
                    Object container = cap.resolve().orElse(null);
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
            // 格雷科技调用失败
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
     * 使用 Flux Networks 接口向 GregTech 容器推送能量。
     */
    private boolean pushFluxToGT(Object sourceFlux, Object container, Direction side,
                                  long voltage, long amperage, long demand) {
        try {
            // 使用缓存的 Flux 方法
            Method acceptEnergyMethod = container.getClass().getMethod("acceptEnergyFromNetwork", Direction.class, long.class, long.class);
            
            // 尝试提取尽可能多的能量（Long.MAX_VALUE）
            long extractedFE = (Long) FLUX_EXTRACT_METHOD.invoke(sourceFlux, Long.MAX_VALUE, true);
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
                // 从Flux源实际提取对应的FE（使用缓存的方法）
                long actualExtractFE = transferredEU << 2;
                FLUX_EXTRACT_METHOD.invoke(sourceFlux, actualExtractFE, false);
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
            try {
                LazyOptional<ILongEnergyStorage> cap = target.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir);
                if (cap.isPresent()) {
                    targetLongEnergy = cap.resolve().orElse(null);
                    if (targetLongEnergy != null && targetLongEnergy.canReceive()) {
                        break;
                    }
                    targetLongEnergy = null;
                }
            } catch (ClassCastException e) {
                // 某些模组使用代理包装能力，跳过不兼容的实现
                continue;
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
            try {
                LazyOptional<IEnergyStorage> cap = target.getCapability(ForgeCapabilities.ENERGY, dir);
                if (cap.isPresent()) {
                    targetEnergy = cap.resolve().orElse(null);
                    if (targetEnergy != null && targetEnergy.canReceive()) {
                        break;
                    }
                    targetEnergy = null;
                }
            } catch (ClassCastException e) {
                // 跳过不兼容的能力实现
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
     * 获取邻居能量源（带缓存）。
     */
    private Object getNeighborEnergySourceCached() {
        if (level == null) return null;
        
        long currentTick = level.getGameTime();
        
        // 检查缓存是否有效
        if (energySourceCache != null) {
            long age = currentTick - energySourceCache.lastValidatedTick;
            if (age < CACHE_VALIDITY_TICKS) {
                // 快速验证：检查方块实体是否仍然存在
                BlockEntity be = level.getBlockEntity(energySourceCache.position);
                if (be != null && !be.isRemoved()) {
                    // 缓存仍然有效
                    return energySourceCache.energyHandler;
                }
            }
            // 缓存失效，清空
            energySourceCache = null;
        }
        
        // 初始化反射（如果尚未初始化）
        initFluxReflection();
        initGTReflection();
        
        // 扫描邻居并建立缓存（按优先级：Flux > Long > Forge）
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE == null || neighborBE == this || neighborBE instanceof WirelessEnergyTowerBlockEntity) {
                continue;
            }
            
            // 优先级 1: Flux Networks（支持 Long，无限制）
            if (FLUX_CAPABILITY != null) {
                LazyOptional<?> fluxCap = neighborBE.getCapability(FLUX_CAPABILITY, dir.getOpposite());
                if (fluxCap.isPresent()) {
                    Object storage = fluxCap.resolve().orElse(null);
                    if (storage != null) {
                        try {
                            if ((Boolean) FLUX_CAN_EXTRACT_METHOD.invoke(storage)) {
                                energySourceCache = new NeighborEnergyCache();
                                energySourceCache.direction = dir;
                                energySourceCache.position = neighborPos;
                                energySourceCache.energyHandler = storage;
                                energySourceCache.type = EnergySourceType.FLUX_NETWORKS;
                                energySourceCache.lastValidatedTick = currentTick;
                                return storage;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            
            // 优先级 2: Long Energy（支持超大值）
            try {
                LazyOptional<ILongEnergyStorage> longCap = neighborBE.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir.getOpposite());
                if (longCap.isPresent()) {
                    ILongEnergyStorage storage = longCap.resolve().orElse(null);
                    if (storage != null && storage.canExtract()) {
                        energySourceCache = new NeighborEnergyCache();
                        energySourceCache.direction = dir;
                        energySourceCache.position = neighborPos;
                        energySourceCache.energyHandler = storage;
                        energySourceCache.type = EnergySourceType.LONG_ENERGY;
                        energySourceCache.lastValidatedTick = currentTick;
                        return storage;
                    }
                }
            } catch (Exception ignored) {}
            
            // 优先级 3: 标准 Forge Energy
            try {
                LazyOptional<IEnergyStorage> forgeCap = neighborBE.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite());
                if (forgeCap.isPresent()) {
                    IEnergyStorage storage = forgeCap.resolve().orElse(null);
                    if (storage != null && storage.canExtract()) {
                        energySourceCache = new NeighborEnergyCache();
                        energySourceCache.direction = dir;
                        energySourceCache.position = neighborPos;
                        energySourceCache.energyHandler = storage;
                        energySourceCache.type = EnergySourceType.FORGE_ENERGY;
                        energySourceCache.lastValidatedTick = currentTick;
                        return storage;
                    }
                }
            } catch (Exception ignored) {}
        }
        
        return null;
    }
    
    /**
     * 从邻居获取Flux Networks能量接口（使用缓存）
     */
    private Object getNeighborFluxEnergy() {
        Object cached = getNeighborEnergySourceCached();
        if (cached != null && energySourceCache != null && energySourceCache.type == EnergySourceType.FLUX_NETWORKS) {
            return cached;
        }
        return null;
    }
    
    /**
     * 使用 Flux Networks 接口推送能量。
     */
    private boolean pushFluxEnergy(Object sourceFlux, BlockEntity target) {
        if (FLUX_CAPABILITY == null) return false; // Flux 未安装
        
        try {
            // 尝试获取目标的Flux接口
            Object targetFlux = null;
            for (Direction dir : Direction.values()) {
                LazyOptional<?> cap = target.getCapability(FLUX_CAPABILITY, dir);
                if (cap.isPresent()) {
                    targetFlux = cap.resolve().orElse(null);
                    if (targetFlux != null) {
                        if ((Boolean) FLUX_CAN_RECEIVE_METHOD.invoke(targetFlux)) {
                            break;
                        }
                        targetFlux = null;
                    }
                }
            }
            
            if (targetFlux != null) {
                // 双方都支持Flux，使用Long传输（使用缓存的方法）
                long extracted = (Long) FLUX_EXTRACT_METHOD.invoke(sourceFlux, Long.MAX_VALUE, true);
                if (extracted > 0) {
                    // 向目标插入能量（实际）
                    long inserted = (Long) FLUX_RECEIVE_METHOD.invoke(targetFlux, extracted, false);
                    if (inserted > 0) {
                        // 从源实际提取能量
                        FLUX_EXTRACT_METHOD.invoke(sourceFlux, inserted, false);
                        return true;
                    }
                }
            } else {
                // 目标不支持Flux，尝试标准接口
                IEnergyStorage targetEnergy = null;
                for (Direction dir : Direction.values()) {
                    try {
                        LazyOptional<IEnergyStorage> cap = target.getCapability(ForgeCapabilities.ENERGY, dir);
                        if (cap.isPresent()) {
                            targetEnergy = cap.resolve().orElse(null);
                            if (targetEnergy != null && targetEnergy.canReceive()) {
                                break;
                            }
                            targetEnergy = null;
                        }
                    } catch (ClassCastException e) {
                        // 跳过不兼容的能力实现
                    }
                }
                
                if (targetEnergy != null) {
                    // 源支持Flux Long，目标只支持int（使用缓存的方法）
                    long extracted = (Long) FLUX_EXTRACT_METHOD.invoke(sourceFlux, (long) Integer.MAX_VALUE, true);
                    if (extracted > 0) {
                        int inserted = targetEnergy.receiveEnergy((int) Math.min(extracted, Integer.MAX_VALUE), false);
                        if (inserted > 0) {
                            FLUX_EXTRACT_METHOD.invoke(sourceFlux, (long) inserted, false);
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
     * 从邻居获取Long能量存储（使用缓存）
     */
    private ILongEnergyStorage getNeighborLongEnergy() {
        Object cached = getNeighborEnergySourceCached();
        if (cached instanceof ILongEnergyStorage) {
            return (ILongEnergyStorage) cached;
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
            try {
                LazyOptional<IEnergyStorage> cap = target.getCapability(ForgeCapabilities.ENERGY, dir);
                if (cap.isPresent()) {
                    targetEnergy = cap.resolve().orElse(null);
                    if (targetEnergy != null && targetEnergy.canReceive()) {
                        break;
                    }
                    targetEnergy = null;
                }
            } catch (ClassCastException e) {
                // 跳过不兼容的能力实现
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
     * 从邻居获取标准Forge能量存储（使用缓存）
     */
    private IEnergyStorage getNeighborForgeEnergy() {
        Object cached = getNeighborEnergySourceCached();
        if (cached instanceof IEnergyStorage) {
            return (IEnergyStorage) cached;
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
            // 立即同步到客户端（用于渲染）
            if (level != null && !level.isClientSide) {
                this.lastSyncedLinks.clear();
                this.lastSyncedLinks.addAll(this.links);
                this.markForUpdate();
            }
        }
    }

    /**
     * 移除连接
     */
    public void removeLink(BlockPos other) {
        if (this.links.remove(other)) {
            this.setChanged();
            // 立即同步到客户端（用于渲染）
            if (level != null && !level.isClientSide) {
                this.lastSyncedLinks.clear();
                this.lastSyncedLinks.addAll(this.links);
                this.markForUpdate();
            }
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
            final int index = side == null ? 0 : side.get3DDataValue() + 1;
            
            // 检查是否为Flux Networks的能力 - 只在明确请求时返回
            if (isFluxEnergyCapability(cap)) {
                LazyOptional<?> handler = fluxEnergyCaps[index];
                if (handler == null) {
                    Object fluxAdapter = createFluxEnergyAdapter(side);
                    if (fluxAdapter != null) {
                        handler = LazyOptional.of(() -> fluxAdapter);
                        fluxEnergyCaps[index] = handler;
                    }
                }
                if (handler != null) {
                    return handler.cast();
                }
            }
            
            // 标准能量能力 - 返回支持多接口的通用能量存储（兼容AppliedFlux）
            if (cap == ForgeCapabilities.ENERGY) {
                // 返回同时支持 IEnergyStorage 和 IFNEnergyStorage 的代理对象
                // 防止 AppliedFlux 尝试强制转换时崩溃
                LazyOptional<?> handler = forgeEnergyCaps[index];
                if (handler == null) {
                    Object storage = createUniversalEnergyStorage(side);
                    handler = LazyOptional.of(() -> storage);
                    forgeEnergyCaps[index] = handler;
                }
                return handler.cast();
            }
            
            // Long 能量能力
            if (cap == MEBFCapabilities.LONG_ENERGY_STORAGE) {
                LazyOptional<?> handler = longEnergyCaps[index];
                if (handler == null) {
                    Object storage = createUniversalEnergyStorage(side);
                    handler = LazyOptional.of(() -> storage);
                    longEnergyCaps[index] = handler;
                }
                return handler.cast();
            }
        }
        return super.getCapability(cap, side);
    }
    
    /**
     * 判断是否为 Flux Networks 能量能力。
     */
    private boolean isFluxEnergyCapability(Capability<?> cap) {
        initFluxReflection(); // 确保已初始化
        return FLUX_CAPABILITY != null && cap == FLUX_CAPABILITY;
    }
    
    /**
     * 创建同时支持 IEnergyStorage/ILongEnergyStorage/IFNEnergyStorage 的代理（兼容 AppliedFlux）。
     */
    private Object createUniversalEnergyStorage(Direction side) {
        TowerEnergyStorage baseStorage = new TowerEnergyStorage(side);
        
        initFluxReflection(); // 确保已初始化
        
        // 检查是否安装了Flux Networks
        if (FLUX_CAPABILITY != null) {
            try {
                Class<?> fluxInterface = Class.forName("sonar.fluxnetworks.api.energy.IFNEnergyStorage");
                
                // 创建动态代理，同时实现IEnergyStorage、ILongEnergyStorage和IFNEnergyStorage
                return java.lang.reflect.Proxy.newProxyInstance(
                    fluxInterface.getClassLoader(),
                    new Class<?>[]{IEnergyStorage.class, ILongEnergyStorage.class, fluxInterface},
                    (proxy, method, args) -> {
                        String methodName = method.getName();
                        
                        // 处理Flux Networks接口方法
                        switch (methodName) {
                            case "extractEnergyL":
                                if (args != null && args.length >= 2) {
                                    return baseStorage.extractEnergyL((Long) args[0], (Boolean) args[1]);
                                }
                                return 0L;
                            case "receiveEnergyL":
                                if (args != null && args.length >= 2) {
                                    return baseStorage.receiveEnergyL((Long) args[0], (Boolean) args[1]);
                                }
                                return 0L;
                            case "getEnergyStoredL":
                                return baseStorage.getEnergyStoredL();
                            case "getMaxEnergyStoredL":
                                return baseStorage.getMaxEnergyStoredL();
                            case "canExtract":
                                return baseStorage.canExtract();
                            case "canReceive":
                                return baseStorage.canReceive();
                            // 标准接口方法
                            case "extractEnergy":
                                if (args != null && args.length >= 2) {
                                    return baseStorage.extractEnergy((Integer) args[0], (Boolean) args[1]);
                                }
                                return 0;
                            case "receiveEnergy":
                                if (args != null && args.length >= 2) {
                                    return baseStorage.receiveEnergy((Integer) args[0], (Boolean) args[1]);
                                }
                                return 0;
                            case "getEnergyStored":
                                return baseStorage.getEnergyStored();
                            case "getMaxEnergyStored":
                                return baseStorage.getMaxEnergyStored();
                            default:
                                return null;
                        }
                    }
                );
            } catch (Exception e) {
                // Flux Networks 代理创建失败，返回基础存储
            }
        }
        
        // Flux Networks未安装，返回基础存储
        return baseStorage;
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
                return handleReceiveEnergyL(args); // 被动接收模式：接收并立即转发
            case "getEnergyStoredL":
                return getFluxEnergyStoredL();
            case "getMaxEnergyStoredL":
                return getFluxMaxEnergyStoredL();
            case "canExtract":
                return canNeighborExtract();
            case "canReceive":
                return canReceiveFromFlux(); // 如果有目标就可以接收
            default:
                return null;
        }
    }
    
    /**
     * 接收能量并立即转发（Flux Networks long版本，被动接收模式）
     */
    private long handleReceiveEnergyL(Object[] args) {
        if (args == null || args.length < 2) return 0L;
        long maxReceive = (Long) args[0];
        boolean simulate = (Boolean) args[1];
        
        if (maxReceive <= 0 || level == null || links.isEmpty()) return 0L;
        
        long totalInserted = 0;
        
        // 将能量分配给所有绑定的目标
        for (BlockPos targetPos : new HashSet<>(links)) {
            if (totalInserted >= maxReceive) break;
            
            BlockEntity targetBE = level.getBlockEntity(targetPos);
            if (targetBE == null) continue;
            
            long remaining = maxReceive - totalInserted;
            
            // 特殊处理：如果目标是另一个感应塔
            if (targetBE instanceof WirelessEnergyTowerBlockEntity targetTower) {
                Set<BlockPos> visited = new HashSet<>();
                visited.add(worldPosition);
                long inserted = targetTower.receiveEnergyFromTower(remaining, simulate, visited);
                totalInserted += inserted;
            } else {
                // 直接推送给普通设备
                long inserted = pushEnergyToTargetDirect(targetBE, remaining, simulate);
                totalInserted += inserted;
            }
        }
        
        return totalInserted;
    }
    
    /**
     * 判断是否可以从Flux Networks接收能量
     */
    private boolean canReceiveFromFlux() {
        return level != null && !links.isEmpty();
    }
    
    /**
     * 获取能量存储量（Flux Networks接口）
     * 返回0，因为感应塔不存储能量
     */
    private long getFluxEnergyStoredL() {
        return 0L; // 无缓存设计，不存储能量
    }
    
    /**
     * 获取最大能量存储量（Flux Networks接口）
     * 返回目标设备的总容量作为参考
     */
    private long getFluxMaxEnergyStoredL() {
        if (level == null || links.isEmpty()) return 0L;
        
        long totalCapacity = 0;
        for (BlockPos targetPos : new HashSet<>(links)) {
            BlockEntity targetBE = level.getBlockEntity(targetPos);
            if (targetBE == null) continue;
            
            // 尝试获取目标的最大容量
            for (Direction dir : Direction.values()) {
                try {
                    // 优先尝试Flux Networks接口
                    long fluxMax = tryGetFluxMaxEnergyStored(targetBE, dir);
                    if (fluxMax > 0) {
                        totalCapacity += fluxMax;
                        break;
                    }
                    
                    // 尝试Long接口
                    LazyOptional<ILongEnergyStorage> longCap = targetBE.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir);
                    if (longCap.isPresent()) {
                        ILongEnergyStorage storage = longCap.resolve().orElse(null);
                        if (storage != null) {
                            totalCapacity += storage.getMaxEnergyStoredL();
                            break;
                        }
                    }
                    
                    // 回退到标准接口
                    LazyOptional<IEnergyStorage> normalCap = targetBE.getCapability(ForgeCapabilities.ENERGY, dir);
                    if (normalCap.isPresent()) {
                        IEnergyStorage storage = normalCap.resolve().orElse(null);
                        if (storage != null) {
                            totalCapacity += storage.getMaxEnergyStored();
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        
        return Math.max(totalCapacity, Long.MAX_VALUE / 2); // 返回目标总容量，至少返回一个大值
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
     * 尝试使用 Flux Networks 接口提取能量。
     */
    private long tryExtractFluxEnergy(BlockEntity be, Direction side, long maxExtract, boolean simulate) {
        if (FLUX_CAPABILITY == null) return 0L; // Flux 未安装
        
        try {
            LazyOptional<?> cap = be.getCapability(FLUX_CAPABILITY, side.getOpposite());
            if (cap.isPresent()) {
                Object storage = cap.resolve().orElse(null);
                if (storage != null) {
                    return (Long) FLUX_EXTRACT_METHOD.invoke(storage, maxExtract, simulate);
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
     * 尝试获取 Flux Networks 的能量存储量。
     */
    private long tryGetFluxEnergyStored(BlockEntity be, Direction side) {
        if (FLUX_CAPABILITY == null) return 0L; // Flux 未安装
        
        try {
            LazyOptional<?> cap = be.getCapability(FLUX_CAPABILITY, side.getOpposite());
            if (cap.isPresent()) {
                Object storage = cap.resolve().orElse(null);
                if (storage != null) {
                    return (Long) FLUX_GET_ENERGY_STORED_METHOD.invoke(storage);
                }
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }
    
    /**
     * 尝试获取 Flux Networks 的最大能量存储量。
     */
    private long tryGetFluxMaxEnergyStored(BlockEntity be, Direction side) {
        if (FLUX_CAPABILITY == null) return 0L; // Flux 未安装
        
        try {
            LazyOptional<?> cap = be.getCapability(FLUX_CAPABILITY, side.getOpposite());
            if (cap.isPresent()) {
                Object storage = cap.resolve().orElse(null);
                if (storage != null) {
                    return (Long) FLUX_GET_MAX_ENERGY_STORED_METHOD.invoke(storage);
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
        try {
            LazyOptional<IEnergyStorage> cap = be.getCapability(ForgeCapabilities.ENERGY, side);
            return cap.resolve().orElse(null);
        } catch (ClassCastException e) {
            // 跳过不兼容的能力实现
            return null;
        }
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
                // 跳过自己和其他无线能源感应塔（避免无限递归）
                if (neighborBE != null && neighborBE != WirelessEnergyTowerBlockEntity.this 
                        && !(neighborBE instanceof WirelessEnergyTowerBlockEntity)) {
                    // 优先尝试Long能量接口
                    try {
                        LazyOptional<ILongEnergyStorage> longCap = neighborBE.getCapability(MEBFCapabilities.LONG_ENERGY_STORAGE, dir.getOpposite());
                        if (longCap.isPresent()) {
                            return longCap.resolve().orElse(null);
                        }
                    } catch (ClassCastException e) {
                        // 跳过不兼容的能力实现
                    }
                    // 回退到标准能量接口
                    try {
                        LazyOptional<IEnergyStorage> normalCap = neighborBE.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite());
                        if (normalCap.isPresent()) {
                            return normalCap.resolve().orElse(null);
                        }
                    } catch (ClassCastException e) {
                        // 跳过不兼容的能力实现
                    }
                }
            }
            return null;
        }
        
        ///// 标准 Forge Energy 接口 \\\\\
        
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            // 被动接收模式：从外部（如Flux Point）接收能量，立即转发给目标
            return (int) Math.min(receiveEnergyL(maxReceive, simulate), Integer.MAX_VALUE);
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
            // 如果有绑定的目标，就可以接收能量（被动接收模式）
            if (level != null && !links.isEmpty()) {
                return true;
            }
            return false;
        }
        
        ///// Long 能量接口 \\\\\
        
        @Override
        public long receiveEnergyL(long maxReceive, boolean simulate) {
            // 被动接收模式：从外部（如Flux Point）接收能量，立即转发给绑定的目标
            if (level == null || maxReceive <= 0 || links.isEmpty()) {
                return 0;
            }
            
            long totalInserted = 0;
            
            // 遍历所有绑定的目标，将能量分配出去
            for (BlockPos targetPos : new HashSet<>(links)) {
                if (totalInserted >= maxReceive) break;
                
                BlockEntity targetBE = level.getBlockEntity(targetPos);
                if (targetBE == null) continue;
                
                long remaining = maxReceive - totalInserted;
                
                // 特殊处理：如果目标是另一个感应塔，递归转发
                if (targetBE instanceof WirelessEnergyTowerBlockEntity targetTower) {
                    // 递归转发给目标塔的绑定设备
                    Set<BlockPos> visited = new HashSet<>();
                    visited.add(worldPosition); // 防止循环
                    long inserted = targetTower.receiveEnergyFromTower(remaining, simulate, visited);
                    totalInserted += inserted;
                } else {
                    // 直接推送给普通设备
                    long inserted = pushEnergyToTargetDirect(targetBE, remaining, simulate);
                    totalInserted += inserted;
                }
            }
            
            return totalInserted; // 返回实际转发的能量
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