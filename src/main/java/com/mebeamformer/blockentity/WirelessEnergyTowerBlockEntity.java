package com.mebeamformer.blockentity;

import appeng.api.networking.GridFlags;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import com.mebeamformer.MEBeamFormer;
import com.mebeamformer.connection.WirelessEnergyNetwork;
import com.mebeamformer.energy.ILongEnergyStorage;
import com.mebeamformer.integration.AE2FluxIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.*;
import java.lang.reflect.Method;

/**
 * 无线能源塔，负责绑定目标并转发能量。
 */
public class WirelessEnergyTowerBlockEntity extends AENetworkedBlockEntity implements ILinkable, IEnergyStorage, ILongEnergyStorage {
    // Flux Networks 反射缓存
    private static volatile boolean FLUX_INITIALIZED = false;
    private static Class<?> FLUX_CAP_CLASS = null;
    private static Object FLUX_CAPABILITY = null; // NeoForge: 改用Object
    private static Method FLUX_EXTRACT_METHOD = null;
    private static Method FLUX_RECEIVE_METHOD = null;
    private static Method FLUX_CAN_EXTRACT_METHOD = null;
    private static Method FLUX_CAN_RECEIVE_METHOD = null;
    private static Method FLUX_GET_ENERGY_STORED_METHOD = null;
    private static Method FLUX_GET_MAX_ENERGY_STORED_METHOD = null;
    
    // GregTech 反射缓存
    private static volatile boolean GT_INITIALIZED = false;
    private static Class<?> GT_CAP_CLASS = null;
    private static Object GT_CAPABILITY = null; // NeoForge: 改用Object
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
    
    public WirelessEnergyTowerBlockEntity(BlockPos pos, BlockState state) {
        super(MEBeamFormer.WIRELESS_ENERGY_TOWER_BE.get(), pos, state);
        
        this.getMainNode()
            .setFlags(GridFlags.REQUIRE_CHANNEL)
            .setIdlePowerUsage(0.0);
    }
    
    // 反射初始化方法（只在第一次使用时调用一次）
    
    // 初始化 Flux Networks 反射缓存（只做一次）
    private static void initFluxReflection() {
        if (FLUX_INITIALIZED) return;
        synchronized (WirelessEnergyTowerBlockEntity.class) {
            if (FLUX_INITIALIZED) return;
            try {
                FLUX_CAP_CLASS = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
                java.lang.reflect.Field field = FLUX_CAP_CLASS.getField("FN_ENERGY_STORAGE");
                FLUX_CAPABILITY = field.get(null); // NeoForge: 移除Capability类型转换
                
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
    
    // 初始化 GregTech 反射缓存（只做一次）
    private static void initGTReflection() {
        if (GT_INITIALIZED) return;
        synchronized (WirelessEnergyTowerBlockEntity.class) {
            if (GT_INITIALIZED) return;
            try {
                GT_CAP_CLASS = Class.forName("com.gregtechceu.gtceu.api.capability.GTCapability");
                java.lang.reflect.Field field = GT_CAP_CLASS.getField("CAPABILITY_ENERGY_CONTAINER");
                GT_CAPABILITY = field.get(null); // NeoForge: 移除Capability类型转换
                
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
        if (level != null && !level.isClientSide) {
            WirelessEnergyNetwork.getInstance().registerTower(this);
        }
    }
    
    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide) {
            WirelessEnergyNetwork.getInstance().unregisterTower(this);
        }
    }
    
    // NeoForge 1.21.1：caps 自动失效，tick 由全局管理器处理
    
    public Set<BlockPos> getLastSyncedLinks() {
        return Collections.unmodifiableSet(lastSyncedLinks);
    }
    
    public void updateSyncedLinks(Set<BlockPos> validLinks) {
        this.lastSyncedLinks.clear();
        this.lastSyncedLinks.addAll(validLinks);
        this.markForUpdate();
    }

    public void pushEnergyToTarget(BlockEntity target) {
        if (level == null || target == null) return;

        if (target instanceof WirelessEnergyTowerBlockEntity targetTower) {
            pushEnergyToTower(targetTower);
            return;
        }

        if (AE2FluxIntegration.isAvailable()) {
            long extracted = AE2FluxIntegration.extractEnergyFromOwnNetwork(this, MAX_TRANSFER, true);
            if (extracted > 0) {
                long inserted = pushToTargetAllSides(target, extracted, true);
                if (inserted > 0) {
                    AE2FluxIntegration.extractEnergyFromOwnNetwork(this, inserted, false);
                    pushToTargetAllSides(target, inserted, false);
                    return;
                }
            }
        }

        for (Direction sourceDir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(sourceDir);
            BlockEntity sourceBE = level.getBlockEntity(neighborPos);
            if (sourceBE == null || sourceBE instanceof WirelessEnergyTowerBlockEntity) continue;
            
            long extracted = com.mebeamformer.energy.EnergyStorageHelper.extractEnergy(
                sourceBE, sourceDir.getOpposite(), MAX_TRANSFER, true
            );
            if (extracted > 0) {
                long inserted = pushToTargetAllSides(target, extracted, true);
                if (inserted > 0) {
                    long actualExtracted = com.mebeamformer.energy.EnergyStorageHelper.extractEnergy(
                        sourceBE, sourceDir.getOpposite(), inserted, false
                    );
                    if (actualExtracted > 0) {
                        pushToTargetAllSides(target, actualExtracted, false);
                        return;
                    }
                }
            }
        }
    }
    
    private long pushToTargetAllSides(BlockEntity target, long amount, boolean simulate) {
        for (Direction targetDir : Direction.values()) {
            long inserted = com.mebeamformer.energy.EnergyStorageHelper.insertEnergy(
                target, targetDir, amount, simulate
            );
            if (inserted > 0) {
                return inserted; // 只要有一个面成功就返回
            }
        }
        return 0;
    }
    
    // 塔间转发：先尝试 AE2，再尝试邻居
    private void pushEnergyToTower(WirelessEnergyTowerBlockEntity targetTower) {
        if (level == null) return;
        
        // 优先级1：尝试从自己的AE2网络提取能量（如果安装了appflux）
        if (AE2FluxIntegration.isAvailable()) {
            long extracted = AE2FluxIntegration.extractEnergyFromOwnNetwork(this, MAX_TRANSFER, true);
            if (extracted > 0) {
                // 模拟阶段：使用临时visited
                Set<BlockPos> visitedSimulate = new java.util.HashSet<>();
                visitedSimulate.add(this.worldPosition);
                long distributed = targetTower.distributeEnergyInNetwork(extracted, true, visitedSimulate);
                
                if (distributed > 0) {
                    // 实际执行：使用新的visited
                    Set<BlockPos> visitedActual = new java.util.HashSet<>();
                    visitedActual.add(this.worldPosition);
                    AE2FluxIntegration.extractEnergyFromOwnNetwork(this, distributed, false);
                    targetTower.distributeEnergyInNetwork(distributed, false, visitedActual);
                    return;
                }
            }
        }
        
        // 优先级2：从邻居提取能量
        for (Direction sourceDir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(sourceDir);
            BlockEntity sourceBE = level.getBlockEntity(neighborPos);
            if (sourceBE == null || sourceBE instanceof WirelessEnergyTowerBlockEntity) continue;
            
            long extracted = com.mebeamformer.energy.EnergyStorageHelper.extractEnergy(
                sourceBE, sourceDir.getOpposite(), MAX_TRANSFER, true
            );
            if (extracted > 0) {
                // 模拟阶段：使用临时visited
                Set<BlockPos> visitedSimulate = new java.util.HashSet<>();
                visitedSimulate.add(this.worldPosition);
                long distributed = targetTower.distributeEnergyInNetwork(extracted, true, visitedSimulate);
                
                if (distributed > 0) {
                    // 实际执行：使用新的visited
                    Set<BlockPos> visitedActual = new java.util.HashSet<>();
                    visitedActual.add(this.worldPosition);
                    long actualExtracted = com.mebeamformer.energy.EnergyStorageHelper.extractEnergy(
                        sourceBE, sourceDir.getOpposite(), distributed, false
                    );
                    targetTower.distributeEnergyInNetwork(actualExtracted, false, visitedActual);
                    return;
                }
            }
        }
    }
    
    // BFS 遍历塔网络分配能量，避免递归
    private long distributeEnergyInNetwork(long amount, boolean simulate, Set<BlockPos> visited) {
        if (level == null || amount <= 0) return 0;
        
        // 使用队列进行广度优先遍历，避免递归
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
                    long inserted = pushToTargetAllSides(neighborBE, neighborRemaining, simulate);
                    totalInserted += inserted;
                }
            }
            
            // 2. 分配给当前塔绑定的普通设备（非感应塔）
            if (totalInserted < amount && !currentTower.links.isEmpty()) {
                for (BlockPos targetPos : new HashSet<>(currentTower.links)) {
                    if (totalInserted >= amount) break;
                    
                    BlockEntity targetBE = level.getBlockEntity(targetPos);
                    if (targetBE == null || targetBE instanceof WirelessEnergyTowerBlockEntity) {
                        continue;
                    }
                    
                    long targetRemaining = amount - totalInserted;
                    long inserted = pushToTargetAllSides(targetBE, targetRemaining, simulate);
                    totalInserted += inserted;
                }
            }
            
            // 3. 将连接的其他感应塔加入队列（BFS扩展）
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
    
    public void addLink(BlockPos other) {
        if (other.equals(this.getBlockPos())) return;
        if (this.links.add(other)) {
            this.setChanged();
        }
    }

    public void removeLink(BlockPos other) {
        if (this.links.remove(other)) {
            this.setChanged();
        }
    }

    public Set<BlockPos> getLinks() {
        return Collections.unmodifiableSet(links);
    }
    
    public boolean hasTargets() {
        return !links.isEmpty();
    }
    
    public List<BlockPos> getClientLinks() {
        return clientLinks;
    }
    
    @Override
    protected void writeToStream(RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        // 同步当前连接目标集合
        data.writeVarInt(this.lastSyncedLinks.size());
        for (BlockPos p : this.lastSyncedLinks) {
            data.writeBlockPos(p);
        }
    }

    @Override
    protected boolean readFromStream(RegistryFriendlyByteBuf data) {
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
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
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
    public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
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

    /**
     *  优化：检查是否为Flux Networks的能量能力
     * 使用缓存的 Capability 对象
     */
    private boolean isFluxEnergyCapability(Object cap) {
        initFluxReflection(); // 确保已初始化
        return FLUX_CAPABILITY != null && cap == FLUX_CAPABILITY;
    }
    
    
    /**
     * 处理Flux Networks接口方法调用
     */
    private Object handleFluxMethod(String methodName, Object[] args, Direction side) {
        switch (methodName) {
            case "extractEnergyL":
                return handleExtractEnergyL(args);
            case "receiveEnergyL":
                return 0L; // TODO: handleReceiveEnergyL已注释
            case "getEnergyStoredL":
                return 0L; // TODO: getFluxEnergyStoredL已注释
            case "getMaxEnergyStoredL":
                return 0L; // TODO: getFluxMaxEnergyStoredL已注释
            case "canExtract":
                return canNeighborExtract();
            case "canReceive":
                return false; // TODO: canReceiveFromFlux已注释
            default:
                return null;
        }
    }
    
    
    /**
     * 获取最大能量存储量（Flux Networks接口）
     * 返回目标设备的总容量作为参考
     */
    private long getFluxMaxEnergyStoredL() {
        return 0L; // TODO: NeoForge - 需要重写
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
        return 0L;
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
    
    
    // NeoForge 1.21.1: 使用新的Capability API
    private IEnergyStorage getForgeEnergyStorage(BlockEntity be, Direction side) {
        if (be == null || be.getLevel() == null) return null;
        try {
            return be.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, 
                be.getBlockPos(), be.getBlockState(), be, side);
        } catch (Exception e) {
            // 跳过不兼容的能力实现
            return null;
        }
    }
    
    
    @OnlyIn(Dist.CLIENT)
    public AABB getRenderBoundingBox() {
        if (clientLinks == null || clientLinks.isEmpty()) {
            BlockPos pos = getBlockPos();
            return new AABB(pos.getX() - 5, pos.getY() - 5, pos.getZ() - 5, 
                           pos.getX() + 6, pos.getY() + 8, pos.getZ() + 6); // Y轴考虑塔的高度
        }

        BlockPos pos = getBlockPos();
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

        double expansion = 5.0;
        return new AABB(minX - expansion, minY - expansion, minZ - expansion, 
                       maxX + expansion, maxY + expansion, maxZ + expansion);
    }
    
    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        return (int) Math.min(receiveEnergyL(maxReceive, simulate), Integer.MAX_VALUE);
    }
    
    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        return (int) Math.min(extractEnergyL(maxExtract, simulate), Integer.MAX_VALUE);
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
        if (level == null) return false;
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE != null && !(neighborBE instanceof WirelessEnergyTowerBlockEntity)) {
                if (com.mebeamformer.energy.EnergyStorageHelper.canExtract(neighborBE, dir.getOpposite())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    @Override
    public boolean canReceive() {
        return level != null && !links.isEmpty();
    }
    
    public long receiveEnergyL(long maxReceive, boolean simulate) {
        if (level == null || maxReceive <= 0 || links.isEmpty()) {
            return 0;
        }
        
        long totalInserted = 0;
        
        for (BlockPos targetPos : new ArrayList<>(links)) {
            if (totalInserted >= maxReceive) break;
            
            BlockEntity targetBE = level.getBlockEntity(targetPos);
            if (targetBE == null) continue;
            
            long remaining = maxReceive - totalInserted;
            
            // 特殊处理：如果目标是另一个感应塔，递归转发
            if (targetBE instanceof WirelessEnergyTowerBlockEntity targetTower) {
                // 防止循环
                Set<BlockPos> visited = new HashSet<>();
                visited.add(this.worldPosition);
                long inserted = targetTower.receiveEnergyFromTower(remaining, simulate, visited);
                totalInserted += inserted;
            } else {
                long inserted = com.mebeamformer.energy.EnergyStorageHelper.insertEnergy(
                    targetBE, null, remaining, simulate
                );
                if (inserted > 0) {
                    totalInserted += inserted;
                } else {
                    for (Direction dir : Direction.values()) {
                        inserted = com.mebeamformer.energy.EnergyStorageHelper.insertEnergy(
                            targetBE, dir, remaining, simulate
                        );
                        if (inserted > 0) {
                            totalInserted += inserted;
                            break;
                        }
                    }
                }
            }
        }
        
        return totalInserted; // 返回实际转发的能量
    }
    
    /**
     * 从另一个塔接收能量（BFS迭代）
     * 功能与 distributeEnergyInNetwork 相同，都使用BFS遍历整个塔网络
     */
    private long receiveEnergyFromTower(long amount, boolean simulate, Set<BlockPos> visited) {
        // 直接复用 distributeEnergyInNetwork 的BFS逻辑
        // 两个方法功能完全相同：在塔网络中分配能量
        return distributeEnergyInNetwork(amount, simulate, visited);
    }
    
    /**
     * 提取Long能量（Flux Networks接口）
     * 从邻居能量源提取能量
     */
    public long extractEnergyL(long maxExtract, boolean simulate) {
        if (level == null || maxExtract <= 0) return 0;
        
        // 从所有邻居方向尝试提取
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE == null || neighborBE instanceof WirelessEnergyTowerBlockEntity) continue;
            
            long extracted = com.mebeamformer.energy.EnergyStorageHelper.extractEnergy(
                neighborBE, dir.getOpposite(), maxExtract, simulate
            );
            if (extracted > 0) {
                return extracted;
            }
        }
        
        return 0;
    }
    
    /**
     * Flux Networks接口：获取存储的能量
     * 返回邻居能量源的存储量
     */
    public long getEnergyStoredL() {
        if (level == null) return 0;
        
        // 返回所有邻居能量源的总存储量
        long total = 0;
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = worldPosition.relative(dir);
            BlockEntity neighborBE = level.getBlockEntity(neighborPos);
            if (neighborBE != null && !(neighborBE instanceof WirelessEnergyTowerBlockEntity)) {
                long stored = com.mebeamformer.energy.EnergyStorageHelper.getEnergyStored(
                    neighborBE, dir.getOpposite()
                );
                total += stored;
            }
        }
        return total;
    }
    
    /**
     * Flux Networks接口：获取最大能量容量
     */
    public long getMaxEnergyStoredL() {
        // 返回一个很大的值，表示可以处理Long级别的传输
        return Long.MAX_VALUE;
    }
}