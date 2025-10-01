package com.mebeamformer.integration;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.blockentity.grid.AENetworkBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * AE2 + appflux 集成
 * 允许能源塔从 AE2 网络中提取 FE 能量
 * appflux 使用反射实现软依赖
 */
public class AE2FluxIntegration {
    
    private static final boolean APPFLUX_LOADED = ModList.get().isLoaded("appflux");
    
    // appflux 反射类和方法
    private static Class<?> fluxKeyClass;
    private static Class<?> energyTypeClass;
    private static Method fluxKeyOfMethod;
    private static Object energyTypeFE;
    
    static {
        if (APPFLUX_LOADED) {
            try {
                initializeAppfluxReflection();
            } catch (Exception e) {
                // appflux 反射初始化失败，静默处理
            }
        }
    }
    
    private static void initializeAppfluxReflection() throws Exception {
        // appflux 类
        fluxKeyClass = Class.forName("com.glodblock.github.appflux.common.me.key.FluxKey");
        energyTypeClass = Class.forName("com.glodblock.github.appflux.common.me.key.type.EnergyType");
        
        // appflux 方法
        fluxKeyOfMethod = fluxKeyClass.getMethod("of", energyTypeClass);
        
        // 获取常量
        energyTypeFE = energyTypeClass.getField("FE").get(null);
    }
    
    /**
     * 检查 appflux 是否已安装
     */
    public static boolean isAvailable() {
        return APPFLUX_LOADED && fluxKeyClass != null;
    }
    
    /**
     * 从 AE2 网络实体自己的网络中提取 FE 能量
     * 
     * @param aeBlockEntity AE2 网络方块实体（例如感应塔本身）
     * @param amount 要提取的 FE 数量
     * @param simulate 是否仅模拟
     * @return 实际提取的能量数量，如果失败返回 0
     */
    public static long extractEnergyFromOwnNetwork(AENetworkBlockEntity aeBlockEntity, long amount, boolean simulate) {
        if (!isAvailable() || amount <= 0) {
            return 0;
        }
        
        try {
            return extractFromAEBlockEntity(aeBlockEntity, amount, simulate);
        } catch (Exception e) {
            // 静默处理异常
            return 0;
        }
    }
    
    /**
     * 从 AE2 网络中提取 FE 能量（通过查找附近的网络节点）
     * 
     * @param level 世界
     * @param pos 要查找 AE2 网络的位置
     * @param amount 要提取的 FE 数量
     * @param simulate 是否仅模拟
     * @return 实际提取的能量数量，如果失败返回 0
     */
    public static long extractEnergyFromNetwork(@Nullable Level level, BlockPos pos, long amount, boolean simulate) {
        if (!isAvailable() || level == null || amount <= 0) {
            return 0;
        }
        
        try {
            // 查找附近的 AE2 网络节点
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos checkPos = pos.offset(dx, dy, dz);
                        BlockEntity be = level.getBlockEntity(checkPos);
                        
                        if (be instanceof AENetworkBlockEntity aeBlockEntity) {
                            long extracted = extractFromAEBlockEntity(aeBlockEntity, amount, simulate);
                            if (extracted > 0) {
                                return extracted;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 静默处理异常
        }
        
        return 0;
    }
    
    /**
     * 从 AE2 网络方块实体中提取能量
     */
    private static long extractFromAEBlockEntity(AENetworkBlockEntity be, long amount, boolean simulate) throws Exception {
        // 获取网络节点
        IManagedGridNode mainNode = be.getMainNode();
        if (mainNode == null || !mainNode.isReady()) {
            return 0;
        }
        
        // 获取网格
        IGrid grid = mainNode.getGrid();
        if (grid == null) {
            return 0;
        }
        
        // 获取存储服务
        IStorageService storageService = grid.getStorageService();
        if (storageService == null) {
            return 0;
        }
        
        // 获取库存
        MEStorage inventory = storageService.getInventory();
        if (inventory == null) {
            return 0;
        }
        
        // 创建 FluxKey.of(EnergyType.FE) - 使用反射
        Object fluxKeyObj = fluxKeyOfMethod.invoke(null, energyTypeFE);
        if (!(fluxKeyObj instanceof AEKey fluxKey)) {
            return 0;
        }
        
        // 创建 Actionable
        Actionable actionable = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
        
        // 创建 IActionSource
        IActionSource actionSource = IActionSource.ofMachine(be);
        
        // 提取能量
        long result = inventory.extract(fluxKey, amount, actionable, actionSource);
        
        return result;
    }
    
    /**
     * 检查指定位置附近是否有活跃的 AE2 网络
     */
    public static boolean hasNearbyNetwork(@Nullable Level level, BlockPos pos) {
        if (!isAvailable() || level == null) {
            return false;
        }
        
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos checkPos = pos.offset(dx, dy, dz);
                        BlockEntity be = level.getBlockEntity(checkPos);
                        
                        if (be instanceof AENetworkBlockEntity aeBlockEntity) {
                            IManagedGridNode mainNode = aeBlockEntity.getMainNode();
                            if (mainNode != null && mainNode.isReady()) {
                                IGrid grid = mainNode.getGrid();
                                if (grid != null) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 静默处理异常
        }
        
        return false;
    }
} 