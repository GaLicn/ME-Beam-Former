package com.mebeamformer.blockentity;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.GridFlags;
import appeng.api.util.AECableType;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.mebeamformer.block.BeamFormerBlock;
import com.mebeamformer.MEBeamFormer;
import java.util.EnumSet;
import java.util.Set;
import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.RelativeSide;

public class BeamFormerBlockEntity extends AENetworkedBlockEntity {
    private int beamLength = 0;
    private IGridConnection connection = null;
    private boolean hideBeam = false;
    // 缓存当前暴露的背面方向；由于我们的朝向来自 BlockState.FACING，不一定触发 AE 的 onOrientationChanged，
    // 因此在 serverTick 中检测并刷新暴露面，确保只有背面可连接。
    private Direction lastExposedBack = null;

    public BeamFormerBlockEntity(BlockPos pos, BlockState state) {
        super(MEBeamFormer.BEAM_FORMER_BE.get(), pos, state);
        // 关键：宣告本节点具备“致密容量”
        this.getMainNode().setFlags(GridFlags.DENSE_CAPACITY);
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        // 仅允许“背面”连接线缆：背面指方块朝向(FACING)的反方向
        Direction facing = this.getBlockState().getValue(BeamFormerBlock.FACING);
        if (dir == facing.getOpposite()) {
            // 返回 SMART 以兼容普通智能/致密智能线缆，容量由 GridFlags.DENSE_CAPACITY 提供
            return AECableType.SMART;
        }
        return AECableType.NONE; // 其他朝向不允许连接
    }

    // 限制可暴露的 ME 连接面：仅“方块FACING的反面”。
    // 直接依据本方块状态的 FACING 计算，而非 AE 的 BlockOrientation，避免二者坐标系不一致导致朝向错乱。
    @Override
    public Set<Direction> getGridConnectableSides(BlockOrientation orientation) {
        Direction facing = this.getBlockState().getValue(BeamFormerBlock.FACING);
        return EnumSet.of(facing.getOpposite());
    }

    public int getBeamLength() { return beamLength; }

    public boolean isHideBeam() { return hideBeam; }

    public boolean shouldRenderBeam() {
        return !hideBeam && beamLength > 0;
    }

    public void toggleBeamVisibility() {
        this.hideBeam = !this.hideBeam;
        
        // 同步到连接的另一端
        if (this.connection != null) {
            try {
                var myNode = this.getMainNode().getNode();
                if (myNode != null) {
                    var otherNode = this.connection.getOtherSide(myNode);
                    if (otherNode != null) {
                        var owner = otherNode.getOwner();
                        if (owner instanceof BeamFormerBlockEntity other) {
                            other.hideBeam = this.hideBeam;
                            other.markForUpdate();
                            other.setChanged();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        
        this.markForUpdate();
        this.setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BeamFormerBlockEntity be) {
        // 若方块实体已被标记移除，避免任何状态写回，防止被挖掘时“复活”
        if (be.isRemoved()) {
            return;
        }
        Direction facing = state.getValue(BeamFormerBlock.FACING);
        // 初始未接入网络或临时断电时，也应继续执行扫描/连接逻辑，由 AE2 负责合并电网并恢复供电。
        // 强制仅暴露背面为可连接面
        Direction back = facing.getOpposite();
        if (be.lastExposedBack != back) {
            be.getMainNode().setExposedOnSides(EnumSet.of(back));
            be.lastExposedBack = back;
        }
        // 若主节点尚未创建（客户端/服务端初始化早期），清空光束并等待下一 tick 再尝试
        var mainNode = be.getMainNode();
        var aNode = (mainNode == null) ? null : mainNode.getNode();
        if (aNode == null) {
            int old = be.beamLength;
            be.beamLength = 0;
            if (!be.isRemoved() && state.getValue(BeamFormerBlock.STATUS) != BeamFormerBlock.Status.ON) {
                level.setBlock(pos, state.setValue(BeamFormerBlock.STATUS, BeamFormerBlock.Status.ON), 3);
            }
            if (old != 0) be.markForUpdate();
            // 不强制断开 AE 连接；节点未创建时并不存在可销毁连接
            return;
        }

        // 注意：即使本端离线/不供电，也允许继续向前扫描并尝试建立 AE 连接；
        // 渲染仍然受在线/供电状态门控。
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

        // 对端必须至少已创建节点；连接的建立不要求在线/上电（避免电力依赖的鸡生蛋问题）
        if (target != null) {
            var tManaged = target.getMainNode();
            var tNode = (tManaged == null) ? null : tManaged.getNode();
            if (tNode != null) {
                // 建立/复用连接
                be.tryConnect(target, len);

                // 仅当两端都在线且上电时才渲染（保持 BEAMING）；否则清零渲染长度但保留连接
                var aManaged = be.getMainNode();
                boolean aOk = aManaged != null && aManaged.isOnline() && aManaged.isPowered();
                boolean bOk = tManaged.isOnline() && tManaged.isPowered();
                if (aOk && bOk) {
                    if (!be.isRemoved() && state.getValue(BeamFormerBlock.STATUS) != BeamFormerBlock.Status.BEAMING) {
                        level.setBlock(pos, state.setValue(BeamFormerBlock.STATUS, BeamFormerBlock.Status.BEAMING), 3);
                    }
                } else {
                    int oldA = be.beamLength;
                    be.beamLength = 0;
                    if (!be.isRemoved() && state.getValue(BeamFormerBlock.STATUS) != BeamFormerBlock.Status.ON) {
                        level.setBlock(pos, state.setValue(BeamFormerBlock.STATUS, BeamFormerBlock.Status.ON), 3);
                    }
                    if (oldA != 0) be.markForUpdate();

                    BlockState tState = target.getBlockState();
                    int oldB = target.beamLength;
                    target.beamLength = 0;
                    if (!target.isRemoved() && tState.getValue(BeamFormerBlock.STATUS) != BeamFormerBlock.Status.ON) {
                        level.setBlock(target.getBlockPos(), tState.setValue(BeamFormerBlock.STATUS, BeamFormerBlock.Status.ON), 3);
                    }
                    if (oldB != 0) target.markForUpdate();
                }
            }
        } else {
            int old = be.beamLength;
            be.disconnect();
            if (!be.isRemoved() && state.getValue(BeamFormerBlock.STATUS) != BeamFormerBlock.Status.ON) {
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
            // 乐观创建，若另一端同时创建会抛 Already exists，忽略即可
            try {
                existing = GridHelper.createConnection(aNode, bNode);
            } catch (IllegalStateException ignored) {
                // 另一端可能已创建；重新查询一次获取现有连接
                existing = aNode.getConnections().stream()
                        .filter(c -> c.getOtherSide(aNode) == bNode)
                        .findFirst()
                        .orElse(null);
                if (existing == null) {
                    // 尚未建立，先缓存长度，等待下一 tick 再尝试
                    int oldA = this.beamLength;
                    this.beamLength = len;
                    if (this.level != null && oldA != this.beamLength) this.markForUpdate();
                    return;
                }
            }
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
            try {
                var myNode = this.getMainNode().getNode();
                if (myNode != null) {
                    var otherNode = this.connection.getOtherSide(myNode);
                    if (otherNode != null) {
                        var owner = otherNode.getOwner();
                        if (owner instanceof BeamFormerBlockEntity otherBe) {
                            int old = otherBe.beamLength;
                            otherBe.beamLength = 0;
                            if (otherBe.level != null && old != 0) otherBe.markForUpdate();
                            // 对端状态同步回退为 ON（若仍为 BEAMING）
                            var st = otherBe.getBlockState();
                            if (!otherBe.isRemoved() && st.getValue(BeamFormerBlock.STATUS) == BeamFormerBlock.Status.BEAMING) {
                                otherBe.level.setBlock(otherBe.getBlockPos(), st.setValue(BeamFormerBlock.STATUS, BeamFormerBlock.Status.ON), 3);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            this.connection.destroy();
            this.connection = null;
        }
    }

    // ---- Client Sync: send beamLength to clients for BER rendering ----
    @Override
    protected void writeToStream(RegistryFriendlyByteBuf data) {
        data.writeVarInt(this.beamLength);
        data.writeBoolean(this.hideBeam);
    }

    @Override
    protected boolean readFromStream(RegistryFriendlyByteBuf data) {
        int oldLength = this.beamLength;
        boolean oldHide = this.hideBeam;
        this.beamLength = data.readVarInt();
        this.hideBeam = data.readBoolean();
        return oldLength != this.beamLength || oldHide != this.hideBeam;
    }

    @Override
    public void onChunkUnloaded() {
        // 区块卸载时断开连接并确保客户端不会继续渲染
        this.disconnect();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        // 保证在移除时清理连接，避免状态在客户端残留
        super.setRemoved();
        this.disconnect();
    }

    @Override
    public void saveAdditional(net.minecraft.nbt.CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("hideBeam", this.hideBeam);
    }

    @Override
    public void loadTag(net.minecraft.nbt.CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        this.hideBeam = tag.getBoolean("hideBeam");
    }

    /**
     * 重写渲染边界框以防止视锥体剔除导致的光束消失问题
     */
    @OnlyIn(Dist.CLIENT)
    public AABB getRenderBoundingBox() {
        BlockPos pos = getBlockPos();
        BlockState state = getBlockState();
        
        if (!(state.getBlock() instanceof BeamFormerBlock)) {
            // 默认较大边界框
            return new AABB(pos.getX() - 5, pos.getY() - 5, pos.getZ() - 5, 
                           pos.getX() + 6, pos.getY() + 6, pos.getZ() + 6);
        }
        
        Direction dir = state.getValue(BeamFormerBlock.FACING);
        int len = Math.max(0, this.beamLength);
        
        if (len <= 0) {
            // 没有光束时也使用较大边界框
            return new AABB(pos.getX() - 5, pos.getY() - 5, pos.getZ() - 5, 
                           pos.getX() + 6, pos.getY() + 6, pos.getZ() + 6);
        }
        
        BlockPos endPos = pos.relative(dir, len);
        
        // 计算包含光束起点和终点的边界框
        // 关键：渲染边界框必须覆盖整条光束，这样即使玩家只能看到光束中间部分，
        // 至少有一个端点的BlockEntity会被触发渲染
        double minX = Math.min(pos.getX(), endPos.getX());
        double minY = Math.min(pos.getY(), endPos.getY());
        double minZ = Math.min(pos.getZ(), endPos.getZ());
        double maxX = Math.max(pos.getX() + 1, endPos.getX() + 1);
        double maxY = Math.max(pos.getY() + 1, endPos.getY() + 1);
        double maxZ = Math.max(pos.getZ() + 1, endPos.getZ() + 1);
        
        // 适度扩展以处理光束的粗细和视角边缘情况
        // 不需要过大，因为边界框已经覆盖了整条光束
        double expansion = 2.0;
        return new AABB(minX - expansion, minY - expansion, minZ - expansion, 
                       maxX + expansion, maxY + expansion, maxZ + expansion);
    }
}
