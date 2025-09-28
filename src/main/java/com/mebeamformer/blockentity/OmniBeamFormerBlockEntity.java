package com.mebeamformer.blockentity;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;
import appeng.blockentity.grid.AENetworkBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

import com.mebeamformer.ME_Beam_Former;
import com.mebeamformer.block.OmniBeamFormerBlock;

public class OmniBeamFormerBlockEntity extends AENetworkBlockEntity {
    // 持久化：绑定目标集合
    private final Set<BlockPos> links = new HashSet<>();
    // 运行时：每个目标的 AE 连接
    private final Map<BlockPos, IGridConnection> connections = new HashMap<>();
    // 客户端渲染缓存：当前“在线&供电”的目标列表（服务端同步）
    private List<BlockPos> clientActiveTargets = Collections.emptyList();
    // 上一次服务端可见集合，用于决定是否 markForUpdate()
    private final Set<BlockPos> lastActiveSet = new HashSet<>();
    // 缓存当前暴露的背面方向，避免每 tick 重复设置
    private Direction lastExposedBack = null;

    public OmniBeamFormerBlockEntity(BlockPos pos, BlockState state) {
        super(ME_Beam_Former.OMNI_BEAM_FORMER_BE.get(), pos, state);
        this.getMainNode().setFlags(GridFlags.DENSE_CAPACITY);
    }

    // 仅背面允许连接致密/智能线缆（与普通成型器一致）
    @Override
    public AECableType getCableConnectionType(Direction dir) {
        Direction facing = this.getBlockState().getValue(OmniBeamFormerBlock.FACING);
        return dir == facing.getOpposite() ? AECableType.SMART : AECableType.NONE;
    }

    @Override
    public Set<Direction> getGridConnectableSides(appeng.api.orientation.BlockOrientation orientation) {
        Direction facing = this.getBlockState().getValue(OmniBeamFormerBlock.FACING);
        return EnumSet.of(facing.getOpposite());
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, OmniBeamFormerBlockEntity be) {
        if (be.isRemoved()) return;

        // 强制仅暴露背面为可连接面
        Direction facing = state.getValue(OmniBeamFormerBlock.FACING);
        Direction back = facing.getOpposite();
        if (be.lastExposedBack != back) {
            var managed = be.getMainNode();
            if (managed != null) {
                managed.setExposedOnSides(EnumSet.of(back));
            }
            be.lastExposedBack = back;
        }

        IManagedGridNode myManaged = be.getMainNode();
        var myNode = myManaged != null ? myManaged.getNode() : null;
        if (myNode == null) {
            // 等待节点准备好
            if (!be.lastActiveSet.isEmpty()) {
                be.lastActiveSet.clear();
                be.markForUpdate();
            }
            return;
        }

        // 维护与所有链接目标的连接
        Set<BlockPos> activeNow = new HashSet<>();
        for (BlockPos targetPos : new HashSet<>(be.links)) {
            var tBe = level.getBlockEntity(targetPos);
            if (!(tBe instanceof OmniBeamFormerBlockEntity other)) {
                // 目标不存在，移除绑定
                be.removeLink(targetPos);
                continue;
            }
            IManagedGridNode otherManaged = other.getMainNode();
            var otherNode = otherManaged != null ? otherManaged.getNode() : null;
            if (otherNode == null) {
                // 对端未就绪，跳过
                continue;
            }

            // 确保单一连接存在
            IGridConnection conn = be.connections.get(targetPos);
            if (conn == null || conn.getOtherSide(myNode) != otherNode) {
                // 查找现有连接
                IGridConnection existing = myNode.getConnections().stream()
                        .filter(c -> c.getOtherSide(myNode) == otherNode)
                        .findFirst().orElse(null);
                if (existing == null) {
                    try {
                        existing = GridHelper.createConnection(myNode, otherNode);
                    } catch (IllegalStateException ignored) {
                        existing = myNode.getConnections().stream()
                                .filter(c -> c.getOtherSide(myNode) == otherNode)
                                .findFirst().orElse(null);
                    }
                }
                if (existing != null) {
                    be.connections.put(targetPos, existing);
                    // 对端也缓存此连接（便于清理）
                    other.connections.put(pos, existing);
                }
            }

            // 是否“在线&供电”以供渲染
            boolean aOk = myManaged.isOnline() && myManaged.isPowered();
            boolean bOk = otherManaged.isOnline() && otherManaged.isPowered();
            if (aOk && bOk) {
                activeNow.add(targetPos);
            }
        }

        // 若可见集合变化，触发客户端同步
        if (!be.lastActiveSet.equals(activeNow)) {
            be.lastActiveSet.clear();
            be.lastActiveSet.addAll(activeNow);
            be.markForUpdate();
        }

        // 更新方块状态显示：有任何活跃连接则显示 BEAMING，否则 ON
        var curState = level.getBlockState(pos);
        OmniBeamFormerBlock.Status targetStatus = activeNow.isEmpty() ? OmniBeamFormerBlock.Status.ON : OmniBeamFormerBlock.Status.BEAMING;
        if (curState.getBlock() instanceof OmniBeamFormerBlock && curState.getValue(OmniBeamFormerBlock.STATUS) != targetStatus) {
            level.setBlock(pos, curState.setValue(OmniBeamFormerBlock.STATUS, targetStatus), 3);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, OmniBeamFormerBlockEntity be) {
        // 客户端仅渲染，数据由 readFromStream 获取
    }

    public void addLink(BlockPos other) {
        if (other.equals(this.getBlockPos())) return;
        if (this.links.add(other)) {
            this.setChanged();
        }
    }

    public void removeLink(BlockPos other) {
        if (this.links.remove(other)) {
            IGridConnection c = this.connections.remove(other);
            if (c != null) {
                try { c.destroy(); } catch (Throwable ignored) {}
            }
            this.setChanged();
        }
    }

    public Set<BlockPos> getLinks() { return Collections.unmodifiableSet(links); }

    public List<BlockPos> getClientActiveTargets() { return clientActiveTargets; }

    @Override
    protected void writeToStream(FriendlyByteBuf data) {
        // 同步当前活跃目标集合
        data.writeVarInt(this.lastActiveSet.size());
        for (BlockPos p : this.lastActiveSet) {
            data.writeBlockPos(p);
        }
    }

    @Override
    protected boolean readFromStream(FriendlyByteBuf data) {
        int n = data.readVarInt();
        List<BlockPos> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(data.readBlockPos());
        boolean changed = !list.equals(this.clientActiveTargets);
        this.clientActiveTargets = list;
        return changed;
    }

    @Override
    public void onChunkUnloaded() {
        this.disconnectAll();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        this.disconnectAll();
    }

    private void disconnectAll() {
        for (IGridConnection c : this.connections.values()) {
            try { c.destroy(); } catch (Throwable ignored) {}
        }
        this.connections.clear();
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
        // 读取持久化的链接数据 - 修复重新进入游戏后连接断开的问题
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
}
