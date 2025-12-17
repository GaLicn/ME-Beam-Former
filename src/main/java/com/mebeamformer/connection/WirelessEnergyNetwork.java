package com.mebeamformer.connection;

import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一处理已加载的能源塔，避免每个方块实体自己 tick。
 */
@Mod.EventBusSubscriber(modid = "me_beam_former")
public class WirelessEnergyNetwork {

    private static volatile WirelessEnergyNetwork instance;

    private final Map<BlockPos, WirelessEnergyTowerBlockEntity> registeredTowers = new ConcurrentHashMap<>();

    private final Map<Level, List<WirelessEnergyTowerBlockEntity>> towersByLevel = new ConcurrentHashMap<>();

    private long lastExecutedTick = -1;      // 上次执行能量传输的游戏时间
    private boolean executedByMonitor = false; // 标记本次 tick 是否由监控方块触发
    
    private WirelessEnergyNetwork() {
    }

    public static WirelessEnergyNetwork getInstance() {
        if (instance == null) {
            synchronized (WirelessEnergyNetwork.class) {
                if (instance == null) {
                    instance = new WirelessEnergyNetwork();
                }
            }
        }
        return instance;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            getInstance().tickIfNeeded(false); // false = 由事件触发
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        getInstance().clear();
    }

    public void registerTower(WirelessEnergyTowerBlockEntity tower) {
        if (tower == null || tower.isRemoved()) {
            return;
        }
        
        BlockPos pos = tower.getBlockPos();
        Level level = tower.getLevel();
        
        if (level == null) {
            return;
        }

        registeredTowers.put(pos, tower);

        towersByLevel.computeIfAbsent(level, k -> new ArrayList<>()).add(tower);
    }

    public void unregisterTower(WirelessEnergyTowerBlockEntity tower) {
        if (tower == null) {
            return;
        }
        
        BlockPos pos = tower.getBlockPos();
        Level level = tower.getLevel();

        registeredTowers.remove(pos);

        if (level != null) {
            List<WirelessEnergyTowerBlockEntity> towers = towersByLevel.get(level);
            if (towers != null) {
                towers.remove(tower);
                if (towers.isEmpty()) {
                    towersByLevel.remove(level);
                }
            }
        }
    }

    private void tickIfNeeded(boolean fromMonitor) {
        if (registeredTowers.isEmpty()) {
            return;
        }

        long currentTick = getCurrentGameTime();

        if (currentTick == lastExecutedTick) {
            return;
        }

        lastExecutedTick = currentTick;
        executedByMonitor = fromMonitor;

        tick();
    }

    private void tick() {
        if (registeredTowers.isEmpty()) {
            return;
        }

        for (Map.Entry<Level, List<WirelessEnergyTowerBlockEntity>> entry : towersByLevel.entrySet()) {
            Level level = entry.getKey();
            List<WirelessEnergyTowerBlockEntity> towers = entry.getValue();
            
            if (towers.isEmpty()) {
                continue;
            }

            processTowersInLevel(level, towers);
        }
    }

    private long getCurrentGameTime() {
        for (WirelessEnergyTowerBlockEntity tower : registeredTowers.values()) {
            if (tower.getLevel() != null) {
                return tower.getLevel().getGameTime();
            }
        }
        return System.currentTimeMillis() / 50; // 后备方案
    }

    private void processTowersInLevel(Level level, List<WirelessEnergyTowerBlockEntity> towers) {
        towers.removeIf(tower -> tower.isRemoved() || tower.getLevel() == null);

        for (WirelessEnergyTowerBlockEntity tower : towers) {
            processSingleTower(tower);
        }
    }

    private void processSingleTower(WirelessEnergyTowerBlockEntity tower) {
        if (tower.isRemoved()) {
            return;
        }
        
        Level level = tower.getLevel();
        if (level == null) {
            return;
        }

        Set<BlockPos> links = tower.getLinks();
        if (links.isEmpty()) {
            return;
        }

        Set<BlockPos> validLinks = new HashSet<>();
        for (BlockPos targetPos : new HashSet<>(links)) {
            BlockEntity targetBE = level.getBlockEntity(targetPos);
            if (targetBE == null) {
                tower.removeLink(targetPos);
                continue;
            }

            tower.pushEnergyToTarget(targetBE);
            validLinks.add(targetPos);
        }

        if (!validLinks.equals(tower.getLastSyncedLinks())) {
            tower.updateSyncedLinks(validLinks);
        }
    }

    private void clear() {
        registeredTowers.clear();
        towersByLevel.clear();
    }

    public int getRegisteredTowerCount() {
        return registeredTowers.size();
    }

    public int getTowerCountInLevel(Level level) {
        List<WirelessEnergyTowerBlockEntity> towers = towersByLevel.get(level);
        return towers == null ? 0 : towers.size();
    }

    public void triggerPerformanceCheck() {
        tickIfNeeded(true);
    }
}

