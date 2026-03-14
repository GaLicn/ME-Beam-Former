package com.mebeamformer.blockentity;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.orientation.BlockOrientation;
import appeng.api.util.AECableType;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import com.mebeamformer.MEBeamFormer;
import com.mebeamformer.block.BeamFormerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

public class BeamFormerBlockEntity extends AENetworkedBlockEntity {
    private static final int MAX_BEAM_RANGE = 32;

    private int beamLength;
    @Nullable
    private IGridConnection connection;
    @Nullable
    private BeamFormerBlockEntity other;
    private boolean hideBeam;
    @Nullable
    private Direction lastExposedBack;

    public BeamFormerBlockEntity(BlockPos pos, BlockState state) {
        super(MEBeamFormer.BEAM_FORMER_BE.get(), pos, state);
        getMainNode().setFlags(GridFlags.DENSE_CAPACITY);
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return dir == getFacing().getOpposite() ? AECableType.SMART : AECableType.NONE;
    }

    @Override
    public Set<Direction> getGridConnectableSides(BlockOrientation orientation) {
        return EnumSet.of(getFacing().getOpposite());
    }

    public int getBeamLength() {
        return beamLength;
    }

    public boolean isHideBeam() {
        return hideBeam;
    }

    public boolean shouldRenderBeam() {
        return !hideBeam && beamLength > 0;
    }

    public void toggleBeamVisibility() {
        setBeamHidden(!hideBeam);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BeamFormerBlockEntity be) {
        if (be.isRemoved()) {
            return;
        }

        Direction facing = state.getValue(BeamFormerBlock.FACING);
        be.syncExposedBack(facing.getOpposite());

        IGridNode myNode = be.getMainNode().getNode();
        if (myNode == null) {
            BeamFormerBlockEntity partner = be.other;
            be.disconnect();
            be.applyVisualState(partner, 0);
            be.updateStatus(partner, BeamFormerBlock.Status.ON);
            return;
        }

        ScanResult scan = be.scanForTarget(level, pos, facing);
        if (!be.hasConnectableTarget(scan.target)) {
            BeamFormerBlockEntity partner = scan.target != null ? scan.target : be.other;
            be.disconnect();
            be.applyVisualState(partner, 0);
            be.updateStatus(partner, BeamFormerBlock.Status.ON);
            return;
        }

        if (!be.ensureConnection(scan.target, scan.length)) {
            BeamFormerBlockEntity partner = scan.target;
            be.disconnect();
            be.applyVisualState(partner, 0);
            be.updateStatus(partner, BeamFormerBlock.Status.ON);
            return;
        }

        if (be.hasActiveBeam(scan.target)) {
            be.applyVisualState(scan.target, scan.length);
            be.updateStatus(scan.target, BeamFormerBlock.Status.BEAMING);
        } else {
            be.applyVisualState(scan.target, 0);
            be.updateStatus(scan.target, BeamFormerBlock.Status.ON);
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, BeamFormerBlockEntity be) {
        // 客户端无需逻辑，渲染器会直接读取同步后的可视状态。
    }

    public void disconnect() {
        BeamFormerBlockEntity partner = other != null ? other : findConnectedPeer();
        IGridConnection activeConnection = connection;

        boolean selfChanged = clearRuntimeState();
        boolean partnerChanged = false;
        if (partner != null && (partner.other == this || partner.connection == activeConnection)) {
            partnerChanged = partner.clearRuntimeState();
        }

        if (activeConnection != null) {
            try {
                activeConnection.destroy();
            } catch (IllegalStateException ignored) {
            }
        }

        if (selfChanged) {
            markVisualChanged();
        }
        if (partnerChanged) {
            partner.markVisualChanged();
        }
    }

    @Override
    protected void writeToStream(RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeVarInt(beamLength);
        data.writeBoolean(hideBeam);
    }

    @Override
    protected boolean readFromStream(RegistryFriendlyByteBuf data) {
        boolean changed = super.readFromStream(data);
        int oldLength = beamLength;
        boolean oldHide = hideBeam;
        beamLength = data.readVarInt();
        hideBeam = data.readBoolean();
        return changed || oldLength != beamLength || oldHide != hideBeam;
    }

    @Override
    public void onChunkUnloaded() {
        disconnect();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        disconnect();
        super.setRemoved();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("hideBeam", hideBeam);
    }

    @Override
    public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        beamLength = 0;
        connection = null;
        other = null;
        hideBeam = tag.getBoolean("hideBeam");
    }

    @OnlyIn(Dist.CLIENT)
    public AABB getRenderBoundingBox() {
        BlockPos pos = getBlockPos();
        int len = Math.max(0, beamLength);
        if (len <= 0) {
            return new AABB(pos.getX() - 5, pos.getY() - 5, pos.getZ() - 5,
                    pos.getX() + 6, pos.getY() + 6, pos.getZ() + 6);
        }

        Direction dir = getFacing();
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

    private boolean ensureConnection(BeamFormerBlockEntity target, int len) {
        IGridNode myNode = getMainNode().getNode();
        IGridNode otherNode = target.getMainNode().getNode();
        if (myNode == null || otherNode == null) {
            return false;
        }

        if (other != null && other != target) {
            disconnect();
        }
        if (target.other != null && target.other != this) {
            target.disconnect();
        }

        if (isConnectionToDifferentNode(connection, myNode, otherNode)) {
            disconnect();
        }
        if (target.isConnectionToDifferentNode(target.connection, otherNode, myNode)) {
            target.disconnect();
        }

        IGridConnection activeConnection = findConnection(myNode, otherNode);
        if (activeConnection == null) {
            try {
                activeConnection = GridHelper.createConnection(myNode, otherNode);
            } catch (IllegalStateException ignored) {
                activeConnection = findConnection(myNode, otherNode);
            }
        }

        if (activeConnection == null) {
            return false;
        }

        connection = activeConnection;
        other = target;
        target.connection = activeConnection;
        target.other = this;
        return true;
    }

    private boolean hasConnectableTarget(@Nullable BeamFormerBlockEntity target) {
        if (target == null || target == this || target.isRemoved()) {
            return false;
        }

        return getMainNode().getNode() != null && target.getMainNode().getNode() != null;
    }

    private boolean hasActiveBeam(BeamFormerBlockEntity target) {
        IManagedGridNode myManaged = getMainNode();
        IManagedGridNode targetManaged = target.getMainNode();
        return myManaged.isOnline()
                && myManaged.isPowered()
                && targetManaged.isOnline()
                && targetManaged.isPowered();
    }

    private void applyVisualState(@Nullable BeamFormerBlockEntity target, int newLength) {
        boolean selfChanged = beamLength != newLength;
        beamLength = newLength;
        if (selfChanged) {
            markVisualChanged();
        }

        if (target != null) {
            boolean targetChanged = target.beamLength != newLength;
            target.beamLength = newLength;
            if (targetChanged) {
                target.markVisualChanged();
            }
        }
    }

    private void setBeamHidden(boolean hidden) {
        boolean selfChanged = hideBeam != hidden;
        hideBeam = hidden;
        if (selfChanged) {
            markVisualChanged();
            setChanged();
        }

        BeamFormerBlockEntity partner = other != null ? other : findConnectedPeer();
        if (partner != null && partner.hideBeam != hidden) {
            partner.hideBeam = hidden;
            partner.markVisualChanged();
            partner.setChanged();
        }
    }

    private void syncExposedBack(Direction back) {
        if (lastExposedBack != back) {
            getMainNode().setExposedOnSides(EnumSet.of(back));
            lastExposedBack = back;
        }
    }

    private void updateStatus(@Nullable BeamFormerBlockEntity target, BeamFormerBlock.Status status) {
        updateOwnStatus(status);
        if (target != null) {
            target.updateOwnStatus(status);
        }
    }

    private void updateOwnStatus(BeamFormerBlock.Status status) {
        if (level == null || isRemoved()) {
            return;
        }

        BlockState state = getBlockState();
        if (state.getBlock() instanceof BeamFormerBlock && state.getValue(BeamFormerBlock.STATUS) != status) {
            level.setBlock(getBlockPos(), state.setValue(BeamFormerBlock.STATUS, status), 3);
        }
    }

    private ScanResult scanForTarget(Level level, BlockPos pos, Direction facing) {
        BlockPos cur = pos;

        for (int i = 0; i < MAX_BEAM_RANGE; i++) {
            cur = cur.relative(facing);
            BlockState state = level.getBlockState(cur);
            var blockEntity = level.getBlockEntity(cur);

            if (state.canOcclude() && !state.isAir()) {
                if (blockEntity instanceof BeamFormerBlockEntity otherBe) {
                    Direction otherFacing = state.getValue(BeamFormerBlock.FACING);
                    if (otherFacing == facing.getOpposite()) {
                        return new ScanResult(otherBe, i);
                    }
                }
                return ScanResult.NONE;
            }

            if (blockEntity instanceof BeamFormerBlockEntity otherBe) {
                Direction otherFacing = otherBe.getFacing();
                if (otherFacing == facing) {
                    return ScanResult.NONE;
                }
                if (otherFacing == facing.getOpposite()) {
                    return new ScanResult(otherBe, i + 1);
                }
            }
        }

        return ScanResult.NONE;
    }

    private Direction getFacing() {
        return getBlockState().getValue(BeamFormerBlock.FACING);
    }

    private boolean clearRuntimeState() {
        boolean changed = connection != null || other != null || beamLength != 0;
        connection = null;
        other = null;
        beamLength = 0;
        return changed;
    }

    @Nullable
    private BeamFormerBlockEntity findConnectedPeer() {
        IGridConnection activeConnection = connection;
        IGridNode myNode = getMainNode().getNode();
        if (activeConnection == null || myNode == null) {
            return null;
        }

        try {
            IGridNode otherNode = activeConnection.getOtherSide(myNode);
            if (otherNode != null && otherNode.getOwner() instanceof BeamFormerBlockEntity otherBe) {
                return otherBe;
            }
        } catch (IllegalStateException ignored) {
        }

        return null;
    }

    @Nullable
    private IGridConnection findConnection(IGridNode aNode, IGridNode bNode) {
        return aNode.getConnections().stream()
                .filter(connection -> getOtherSide(connection, aNode) == bNode)
                .findFirst()
                .orElse(null);
    }

    private boolean isConnectionToDifferentNode(@Nullable IGridConnection activeConnection, IGridNode selfNode,
            IGridNode expectedOtherNode) {
        if (activeConnection == null) {
            return false;
        }

        IGridNode actualOtherNode = getOtherSide(activeConnection, selfNode);
        return actualOtherNode != null && actualOtherNode != expectedOtherNode;
    }

    @Nullable
    private IGridNode getOtherSide(IGridConnection activeConnection, IGridNode selfNode) {
        try {
            return activeConnection.getOtherSide(selfNode);
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private void markVisualChanged() {
        if (level != null) {
            markForUpdate();
        }
    }

    private static final class ScanResult {
        private static final ScanResult NONE = new ScanResult(null, 0);

        @Nullable
        private final BeamFormerBlockEntity target;
        private final int length;

        private ScanResult(@Nullable BeamFormerBlockEntity target, int length) {
            this.target = target;
            this.length = length;
        }
    }
}
