package com.mebeamformer.blockentity;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;
import appeng.blockentity.grid.AENetworkBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;

import com.mebeamformer.block.BeamFormerBlock;
import com.mebeamformer.ME_Beam_Former;
import java.util.EnumSet;
import java.util.Set;
import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.RelativeSide;

public class BeamFormerBlockEntity extends AENetworkBlockEntity {
    private int beamLength = 0;
    private IGridConnection connection = null;
    // 缓存当前暴露的背面方向；由于我们的朝向来自 BlockState.FACING，不一定触发 AE 的 onOrientationChanged，
    // 因此在 serverTick 中检测并刷新暴露面，确保只有背面可连接。
    private Direction lastExposedBack = null;

    public BeamFormerBlockEntity(BlockPos pos, BlockState state) {
        super(ME_Beam_Former.BEAM_FORMER_BE.get(), pos, state);
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        // 仅允许“背面”连接线缆：背面指方块朝向(FACING)的反方向
        Direction facing = this.getBlockState().getValue(BeamFormerBlock.FACING);
        if (dir == facing.getOpposite()) {
            return AECableType.DENSE_SMART; // 32 频道（致密智能）
        }
        return AECableType.NONE; // 其他朝向不允许连接
    }

    // 限制可暴露的 ME 连接面：仅背面（使用 AE 的 BlockOrientation 将“背面”映射为绝对朝向）
    @Override
    public Set<Direction> getGridConnectableSides(BlockOrientation orientation) {
        // AE2 定义 RelativeSide.FRONT/ BACK 等相对面，orientation.getSide 将其转换为世界绝对 Direction
        return EnumSet.of(orientation.getSide(RelativeSide.BACK));
    }

    public int getBeamLength() { return beamLength; }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BeamFormerBlockEntity be) {
        Direction facing = state.getValue(BeamFormerBlock.FACING);
        // 强制仅暴露背面为可连接面
        Direction back = facing.getOpposite();
        if (be.lastExposedBack != back) {
            be.getMainNode().setExposedOnSides(EnumSet.of(back));
            be.lastExposedBack = back;
        }
        BlockPos cur = pos;
        int max = 32;
        int len = 0;
        BeamFormerBlockEntity target = null;

        for (int i = 0; i < max; i++) {
            cur = cur.relative(facing);
            BlockState bs = level.getBlockState(cur);
            // 阻挡：可遮挡且非空气
            if (bs.canOcclude() && !bs.isAir()) {
                var obe = level.getBlockEntity(cur);
                if (obe instanceof BeamFormerBlockEntity other) {
                    // 必须对向
                    Direction otherFacing = level.getBlockState(cur).getValue(BeamFormerBlock.FACING);
                    if (otherFacing == facing.getOpposite()) {
                        target = other;
                        len = i; // 距离为中间空气格数
                    }
                }
                break;
            }
            len = i + 1;
            // 中途遇到成型器：
            var pbe = level.getBlockEntity(cur);
            if (pbe instanceof BeamFormerBlockEntity other) {
                Direction otherFacing = level.getBlockState(cur).getValue(BeamFormerBlock.FACING);
                // 同向：阻挡，终止，不形成连接
                if (otherFacing == facing) { target = null; break; }
                // 反向：即便对方不是可遮挡方块，也直接确定为目标并终止
                if (otherFacing == facing.getOpposite()) { target = other; break; }
            }
        }

        if (target != null) {
            be.tryConnect(target, len);
            // 更新状态
            if (state.getValue(BeamFormerBlock.STATUS) != BeamFormerBlock.Status.BEAMING) {
                level.setBlock(pos, state.setValue(BeamFormerBlock.STATUS, BeamFormerBlock.Status.BEAMING), 3);
            }
        } else {
            int old = be.beamLength;
            be.disconnect();
            if (state.getValue(BeamFormerBlock.STATUS) != BeamFormerBlock.Status.ON) {
                level.setBlock(pos, state.setValue(BeamFormerBlock.STATUS, BeamFormerBlock.Status.ON), 3);
            }
            be.beamLength = 0;
            if (old != 0) be.markForUpdate();
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, BeamFormerBlockEntity be) {
        // 客户端无需逻辑，渲染器会读取 beamLength
    }

    private void tryConnect(BeamFormerBlockEntity target, int len) {
        IManagedGridNode a = this.getMainNode();
        IManagedGridNode b = target.getMainNode();
        if (a == null || b == null) return;
        var aNode = a.getNode();
        var bNode = b.getNode();
        if (aNode == null || bNode == null) return; // not ready yet

        // 若本端已持有连接且指向对端，直接刷新长度
        if (this.connection != null) {
            var other = this.connection.getOtherSide(aNode);
            if (other == bNode) {
                int oldA = this.beamLength;
                int oldB = target.beamLength;
                this.beamLength = len;
                target.beamLength = len;
                // 同步对端的缓存
                if (target.connection == null) target.connection = this.connection;
                if (this.level != null && oldA != this.beamLength) this.markForUpdate();
                if (target.level != null && oldB != target.beamLength) target.markForUpdate();
                return;
            } else {
                this.disconnect();
            }
        }

        // 检查是否已经存在两节点之间的连接（可能由对端或先前 tick 创建）
        var existing = aNode.getConnections().stream()
                .filter(c -> c.getOtherSide(aNode) == bNode)
                .findFirst()
                .orElse(null);
        if (existing == null) {
            // 仅由坐标较小的一端创建连接，避免双端同时创建导致 Already exists 异常
            if (this.getBlockPos().compareTo(target.getBlockPos()) > 0) {
                // 让另一端创建，本端只刷新长度缓存
                int oldA = this.beamLength;
                this.beamLength = len;
                if (this.level != null && oldA != this.beamLength) this.markForUpdate();
                return;
            }
            existing = GridHelper.createConnection(aNode, bNode);
        }

        // 缓存连接并刷新长度
        this.connection = existing;
        target.connection = existing;
        int oldA = this.beamLength;
        int oldB = target.beamLength;
        this.beamLength = len;
        target.beamLength = len;
        if (this.level != null && oldA != this.beamLength) this.markForUpdate();
        if (target.level != null && oldB != target.beamLength) target.markForUpdate();
    }

    public void disconnect() {
        if (this.connection != null) {
            this.connection.destroy();
            this.connection = null;
        }
    }

    // ---- Client Sync: send beamLength to clients for BER rendering ----
    @Override
    protected void writeToStream(FriendlyByteBuf data) {
        data.writeVarInt(this.beamLength);
    }

    @Override
    protected boolean readFromStream(FriendlyByteBuf data) {
        int old = this.beamLength;
        this.beamLength = data.readVarInt();
        return old != this.beamLength;
    }
}
