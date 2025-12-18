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
    // 缓存当前暴露的背面方向，用于限制连接面
    private Direction lastExposedBack = null;

    public BeamFormerBlockEntity(BlockPos pos, BlockState state) {
        super(MEBeamFormer.BEAM_FORMER_BE.get(), pos, state);
        this.getMainNode().setFlags(GridFlags.DENSE_CAPACITY);
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        Direction facing = this.getBlockState().getValue(BeamFormerBlock.FACING);
        if (dir == facing.getOpposite()) {
            return AECableType.SMART;
        }
        return AECableType.NONE;
    }

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
        if (be.isRemoved()) {
            return;
        }
        Direction facing = state.getValue(BeamFormerBlock.FACING);
        Direction back = facing.getOpposite();
        if (be.lastExposedBack != back) {
            be.getMainNode().setExposedOnSides(EnumSet.of(back));
            be.lastExposedBack = back;
        }
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

        BlockPos cur = pos;
        int max = 32;
        int len = 0;
        BeamFormerBlockEntity target = null;

        for (int i = 0; i < max; i++) {
            cur = cur.relative(facing);
            BlockState bs = level.getBlockState(cur);
            if (bs.canOcclude() && !bs.isAir()) {
                var obe = level.getBlockEntity(cur);
                if (obe instanceof BeamFormerBlockEntity other) {
                    Direction otherFacing = level.getBlockState(cur).getValue(BeamFormerBlock.FACING);
                    if (otherFacing == facing.getOpposite()) {
                        target = other;
                        len = i; // 距离为中间空气格数
                    }
                }
                break;
            }
            len = i + 1;
            var pbe = level.getBlockEntity(cur);
            if (pbe instanceof BeamFormerBlockEntity other) {
                Direction otherFacing = level.getBlockState(cur).getValue(BeamFormerBlock.FACING);
                if (otherFacing == facing) { target = null; break; }
                if (otherFacing == facing.getOpposite()) { target = other; break; }
            }
        }

        if (target != null) {
            var tManaged = target.getMainNode();
            var tNode = (tManaged == null) ? null : tManaged.getNode();
            if (tNode != null) {
                be.tryConnect(target, len);

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

        var existing = aNode.getConnections().stream()
                .filter(c -> c.getOtherSide(aNode) == bNode)
                .findFirst()
                .orElse(null);
        if (existing == null) {
            try {
                existing = GridHelper.createConnection(aNode, bNode);
            } catch (IllegalStateException ignored) {
                existing = aNode.getConnections().stream()
                        .filter(c -> c.getOtherSide(aNode) == bNode)
                        .findFirst()
                        .orElse(null);
                if (existing == null) {
                    int oldA = this.beamLength;
                    this.beamLength = len;
                    if (this.level != null && oldA != this.beamLength) this.markForUpdate();
                    return;
                }
            }
        }

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
        this.disconnect();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
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

    @OnlyIn(Dist.CLIENT)
    public AABB getRenderBoundingBox() {
        BlockPos pos = getBlockPos();
        BlockState state = getBlockState();
        
        if (!(state.getBlock() instanceof BeamFormerBlock)) {
            return new AABB(pos.getX() - 5, pos.getY() - 5, pos.getZ() - 5, 
                           pos.getX() + 6, pos.getY() + 6, pos.getZ() + 6);
        }
        
        Direction dir = state.getValue(BeamFormerBlock.FACING);
        int len = Math.max(0, this.beamLength);
        
        if (len <= 0) {
            return new AABB(pos.getX() - 5, pos.getY() - 5, pos.getZ() - 5, 
                           pos.getX() + 6, pos.getY() + 6, pos.getZ() + 6);
        }
        
        BlockPos endPos = pos.relative(dir, len);
        
        double minX = Math.min(pos.getX(), endPos.getX());
        double minY = Math.min(pos.getY(), endPos.getY());
        double minZ = Math.min(pos.getZ(), endPos.getZ());
        double maxX = Math.max(pos.getX() + 1, endPos.getX() + 1);
        double maxY = Math.max(pos.getY() + 1, endPos.getY() + 1);
        double maxZ = Math.max(pos.getZ() + 1, endPos.getZ() + 1);
        
        double expansion = 2.0;
        return new AABB(minX - expansion, minY - expansion, minZ - expansion, 
                       maxX + expansion, maxY + expansion, maxZ + expansion);
    }
}
