package com.mebeamformer.integration;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

public class AE2FluxIntegration {
    
    private static final boolean APPFLUX_LOADED = ModList.get().isLoaded("appflux");
    
    // appflux（软依赖，反射）
    private static Class<?> fluxKeyClass;
    private static Class<?> energyTypeClass;
    private static Method fluxKeyOfMethod;
    private static Object energyTypeFE;
    
    static {
        if (APPFLUX_LOADED) {
            try {
                initializeAppfluxReflection();
            } catch (Exception e) {
            }
        }
    }
    
    private static void initializeAppfluxReflection() throws Exception {
        fluxKeyClass = Class.forName("com.glodblock.github.appflux.common.me.key.FluxKey");
        energyTypeClass = Class.forName("com.glodblock.github.appflux.common.me.key.type.EnergyType");
        
        fluxKeyOfMethod = fluxKeyClass.getMethod("of", energyTypeClass);
        
        energyTypeFE = energyTypeClass.getField("FE").get(null);
    }
    
    public static boolean isAvailable() {
        return APPFLUX_LOADED && fluxKeyClass != null;
    }
    
    public static long extractEnergyFromOwnNetwork(AENetworkedBlockEntity aeBlockEntity, long amount, boolean simulate) {
        if (!isAvailable() || amount <= 0) {
            return 0;
        }
        
        try {
            return extractFromAEBlockEntity(aeBlockEntity, amount, simulate);
        } catch (Exception e) {
            return 0;
        }
    }
    
    public static long extractEnergyFromNetwork(@Nullable Level level, BlockPos pos, long amount, boolean simulate) {
        if (!isAvailable() || level == null || amount <= 0) {
            return 0;
        }
        
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos checkPos = pos.offset(dx, dy, dz);
                        
                        BlockEntity be = level.getBlockEntity(checkPos);
                        
                        if (be instanceof AENetworkedBlockEntity aeBlockEntity) {
                            long extracted = extractFromAEBlockEntity(aeBlockEntity, amount, simulate);
                            if (extracted > 0) {
                                return extracted;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        
        return 0;
    }
    
    private static long extractFromAEBlockEntity(AENetworkedBlockEntity be, long amount, boolean simulate) throws Exception {
        IManagedGridNode mainNode = be.getMainNode();
        if (mainNode == null || !mainNode.isReady()) {
            return 0;
        }
        
        IGrid grid = mainNode.getGrid();
        if (grid == null) {
            return 0;
        }
        
        IStorageService storageService = grid.getStorageService();
        if (storageService == null) {
            return 0;
        }
        
        MEStorage inventory = storageService.getInventory();
        if (inventory == null) {
            return 0;
        }
        
        Object fluxKeyObj = fluxKeyOfMethod.invoke(null, energyTypeFE);
        if (!(fluxKeyObj instanceof AEKey fluxKey)) {
            return 0;
        }
        
        Actionable actionable = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
        
        IActionSource actionSource = IActionSource.ofMachine(be);
        
        long result = inventory.extract(fluxKey, amount, actionable, actionSource);
        
        return result;
    }
    
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
                        
                        if (be instanceof AENetworkedBlockEntity aeBlockEntity) {
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
        }
        
        return false;
    }
}