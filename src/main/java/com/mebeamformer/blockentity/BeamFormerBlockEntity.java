package com.mebeamformer.blockentity;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;
import appeng.blockentity.grid.AENetworkBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import com.mebeamformer.block.BeamFormerBlock;
import com.mebeamformer.ME_Beam_Former;

public class BeamFormerBlockEntity extends AENetworkBlockEntity {
    private int beamLength = 0;
    private IGridConnection connection = null;

    public BeamFormerBlockEntity(BlockPos pos, BlockState state) {
        super(ME_Beam_Former.BEAM_FORMER_BE.get(), pos, state);
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.DENSE_SMART; // 32 频道（致密智能）
    }

    public int getBeamLength() { return beamLength; }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BeamFormerBlockEntity be) {
        Direction facing = state.getValue(BeamFormerBlock.FACING);
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
            // 同向的阻挡
            var pbe = level.getBlockEntity(cur);
            if (pbe instanceof BeamFormerBlockEntity other) {
                Direction otherFacing = level.getBlockState(cur).getValue(BeamFormerBlock.FACING);
                if (otherFacing == facing) {
                    target = null;
                    break;
                }
            }
        }

        if (target != null) {
            be.tryConnect(target, len);
            // 更新状态
            if (state.getValue(BeamFormerBlock.STATUS) != BeamFormerBlock.Status.BEAMING) {
                level.setBlock(pos, state.setValue(BeamFormerBlock.STATUS, BeamFormerBlock.Status.BEAMING), 3);
            }
        } else {
            be.disconnect();
            if (state.getValue(BeamFormerBlock.STATUS) != BeamFormerBlock.Status.ON) {
                level.setBlock(pos, state.setValue(BeamFormerBlock.STATUS, BeamFormerBlock.Status.ON), 3);
            }
            be.beamLength = 0;
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
                this.beamLength = len;
                target.beamLength = len;
                // 同步对端的缓存
                if (target.connection == null) target.connection = this.connection;
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
                this.beamLength = len;
                return;
            }
            existing = GridHelper.createConnection(aNode, bNode);
        }

        // 缓存连接并刷新长度
        this.connection = existing;
        target.connection = existing;
        this.beamLength = len;
        target.beamLength = len;
    }

    public void disconnect() {
        if (this.connection != null) {
            this.connection.destroy();
            this.connection = null;
        }
    }
}
