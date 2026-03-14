package com.mebeamformer.blockentity;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import com.mebeamformer.MEBeamFormer;
import com.mebeamformer.block.OmniBeamFormerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OmniBeamFormerBlockEntity extends AENetworkedBlockEntity implements ILinkable {
    private static final Comparator<BlockPos> TARGET_ORDER = Comparator.comparingLong(BlockPos::asLong);

    private final Set<BlockPos> links = new HashSet<>();
    private final Map<BlockPos, IGridConnection> connections = new HashMap<>();
    private List<BlockPos> activeTargets = List.of();
    private List<BlockPos> clientActiveTargets = List.of();
    @Nullable
    private Direction lastExposedBack;

    public OmniBeamFormerBlockEntity(BlockPos pos, BlockState state) {
        super(MEBeamFormer.OMNI_BEAM_FORMER_BE.get(), pos, state);
        this.getMainNode().setFlags(GridFlags.DENSE_CAPACITY);
    }

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
        if (be.isRemoved()) {
            return;
        }

        Direction facing = state.getValue(OmniBeamFormerBlock.FACING);
        be.syncExposedBack(facing.getOpposite());

        IManagedGridNode myManaged = be.getMainNode();
        IGridNode myNode = myManaged.getNode();
        if (myNode == null) {
            be.clearRuntimeState();
            be.updateOwnStatus(OmniBeamFormerBlock.Status.ON);
            return;
        }

        List<BlockPos> activeNow = new ArrayList<>();
        for (BlockPos targetPos : be.getSortedLinks()) {
            if (!level.hasChunkAt(targetPos)) {
                be.releaseConnection(targetPos, myNode, true);
                continue;
            }

            BlockState targetState = level.getBlockState(targetPos);
            if (!(targetState.getBlock() instanceof OmniBeamFormerBlock)) {
                be.removeLink(targetPos);
                continue;
            }

            var targetEntity = level.getBlockEntity(targetPos);
            if (!(targetEntity instanceof OmniBeamFormerBlockEntity other) || other == be || other.isRemoved()) {
                be.releaseConnection(targetPos, myNode, true);
                continue;
            }

            IManagedGridNode otherManaged = other.getMainNode();
            IGridNode otherNode = otherManaged.getNode();
            if (otherNode == null) {
                be.releaseConnection(targetPos, myNode, true);
                continue;
            }

            if (be.ensureConnection(targetPos, myNode, otherNode) != null
                    && myManaged.isOnline() && myManaged.isPowered()
                    && otherManaged.isOnline() && otherManaged.isPowered()) {
                activeNow.add(targetPos);
            }
        }

        be.syncActiveTargets(activeNow);
        be.updateOwnStatus(activeNow.isEmpty() ? OmniBeamFormerBlock.Status.ON : OmniBeamFormerBlock.Status.BEAMING);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, OmniBeamFormerBlockEntity be) {
    }

    @Override
    public void addLink(BlockPos other) {
        if (other.equals(this.getBlockPos())) {
            return;
        }

        if (this.links.add(other)) {
            this.setChanged();
        }
    }

    @Override
    public void removeLink(BlockPos other) {
        if (!this.links.remove(other)) {
            return;
        }

        this.releaseConnection(other, this.getMainNode().getNode(), true);
        this.removeActiveTarget(other);
        this.setChanged();
    }

    @Override
    public Set<BlockPos> getLinks() {
        return Collections.unmodifiableSet(this.links);
    }

    public List<BlockPos> getClientActiveTargets() {
        return this.clientActiveTargets;
    }

    @Override
    protected void writeToStream(RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeVarInt(this.activeTargets.size());
        for (BlockPos p : this.activeTargets) {
            data.writeBlockPos(p);
        }
    }

    @Override
    protected boolean readFromStream(RegistryFriendlyByteBuf data) {
        boolean changed = super.readFromStream(data);
        int count = data.readVarInt();
        List<BlockPos> updatedTargets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            updatedTargets.add(data.readBlockPos());
        }

        List<BlockPos> immutableTargets = updatedTargets.isEmpty() ? List.of() : List.copyOf(updatedTargets);
        boolean targetsChanged = !immutableTargets.equals(this.clientActiveTargets);
        this.clientActiveTargets = immutableTargets;
        return changed || targetsChanged;
    }

    @Override
    public void onChunkUnloaded() {
        this.disconnectAll();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        this.disconnectAll();
        super.setRemoved();
    }

    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (BlockPos p : this.links) {
            CompoundTag targetTag = new CompoundTag();
            targetTag.putInt("x", p.getX());
            targetTag.putInt("y", p.getY());
            targetTag.putInt("z", p.getZ());
            list.add(targetTag);
        }
        tag.put("links", list);
    }

    @Override
    public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTag(tag, registries);
        this.links.clear();
        this.connections.clear();
        this.activeTargets = List.of();
        this.clientActiveTargets = List.of();
        this.lastExposedBack = null;

        if (tag.contains("links", Tag.TAG_LIST)) {
            ListTag list = tag.getList("links", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag targetTag = list.getCompound(i);
                this.links.add(new BlockPos(
                        targetTag.getInt("x"),
                        targetTag.getInt("y"),
                        targetTag.getInt("z")));
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    public AABB getRenderBoundingBox() {
        if (this.clientActiveTargets.isEmpty()) {
            BlockPos pos = this.getBlockPos();
            return new AABB(
                    pos.getX() - 5, pos.getY() - 5, pos.getZ() - 5,
                    pos.getX() + 6, pos.getY() + 6, pos.getZ() + 6);
        }

        BlockPos pos = this.getBlockPos();
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = pos.getX() + 1;
        double maxY = pos.getY() + 1;
        double maxZ = pos.getZ() + 1;

        for (BlockPos target : this.clientActiveTargets) {
            minX = Math.min(minX, target.getX());
            minY = Math.min(minY, target.getY());
            minZ = Math.min(minZ, target.getZ());
            maxX = Math.max(maxX, target.getX() + 1);
            maxY = Math.max(maxY, target.getY() + 1);
            maxZ = Math.max(maxZ, target.getZ() + 1);
        }

        double expansion = 2.0;
        return new AABB(
                minX - expansion, minY - expansion, minZ - expansion,
                maxX + expansion, maxY + expansion, maxZ + expansion);
    }

    private void disconnectAll() {
        IGridNode myNode = this.getMainNode().getNode();
        if (myNode != null) {
            for (IGridConnection connection : new HashSet<>(this.connections.values())) {
                if (this.isLiveConnection(connection, myNode, null)) {
                    this.destroyConnection(connection);
                }
            }
        }

        this.clearRuntimeState();
    }

    private void clearRuntimeState() {
        this.connections.clear();
        this.syncActiveTargets(List.of());
    }

    @Nullable
    private IGridConnection ensureConnection(BlockPos targetPos, IGridNode myNode, IGridNode otherNode) {
        IGridConnection cachedConnection = this.connections.get(targetPos);
        if (this.isLiveConnection(cachedConnection, myNode, otherNode)) {
            return cachedConnection;
        }

        if (this.isLiveConnection(cachedConnection, myNode, null)) {
            this.destroyConnection(cachedConnection);
        }
        this.connections.remove(targetPos);

        IGridConnection liveConnection = this.findLiveConnection(myNode, otherNode);
        if (liveConnection == null) {
            try {
                liveConnection = GridHelper.createConnection(myNode, otherNode);
            } catch (IllegalStateException ignored) {
                liveConnection = this.findLiveConnection(myNode, otherNode);
            }
        }

        if (liveConnection != null) {
            this.connections.put(targetPos, liveConnection);
        }
        return liveConnection;
    }

    private void releaseConnection(BlockPos targetPos, @Nullable IGridNode myNode, boolean destroyLiveConnection) {
        IGridConnection cachedConnection = this.connections.remove(targetPos);
        if (!destroyLiveConnection || myNode == null) {
            return;
        }

        if (this.isLiveConnection(cachedConnection, myNode, null)) {
            this.destroyConnection(cachedConnection);
            return;
        }

        IGridNode targetNode = this.getTargetNode(targetPos);
        if (targetNode == null) {
            return;
        }

        IGridConnection liveConnection = this.findLiveConnection(myNode, targetNode);
        if (liveConnection != null) {
            this.destroyConnection(liveConnection);
        }
    }

    @Nullable
    private IGridNode getTargetNode(BlockPos targetPos) {
        if (this.level == null || !this.level.hasChunkAt(targetPos)) {
            return null;
        }

        var targetEntity = this.level.getBlockEntity(targetPos);
        if (targetEntity instanceof OmniBeamFormerBlockEntity other && !other.isRemoved()) {
            return other.getMainNode().getNode();
        }

        return null;
    }

    private void syncActiveTargets(List<BlockPos> activeNow) {
        List<BlockPos> immutableTargets = activeNow.isEmpty() ? List.of() : List.copyOf(activeNow);
        if (!immutableTargets.equals(this.activeTargets)) {
            this.activeTargets = immutableTargets;
            this.markForUpdate();
        }
    }

    private void removeActiveTarget(BlockPos targetPos) {
        if (!this.activeTargets.contains(targetPos)) {
            return;
        }

        List<BlockPos> updatedTargets = new ArrayList<>(this.activeTargets);
        updatedTargets.remove(targetPos);
        this.syncActiveTargets(updatedTargets);
        this.updateOwnStatus(updatedTargets.isEmpty()
                ? OmniBeamFormerBlock.Status.ON
                : OmniBeamFormerBlock.Status.BEAMING);
    }

    private void syncExposedBack(Direction back) {
        if (this.lastExposedBack != back) {
            this.getMainNode().setExposedOnSides(EnumSet.of(back));
            this.lastExposedBack = back;
        }
    }

    private void updateOwnStatus(OmniBeamFormerBlock.Status status) {
        if (this.level == null || this.isRemoved()) {
            return;
        }

        BlockState currentState = this.getBlockState();
        if (currentState.getBlock() instanceof OmniBeamFormerBlock
                && currentState.getValue(OmniBeamFormerBlock.STATUS) != status) {
            this.level.setBlock(this.getBlockPos(), currentState.setValue(OmniBeamFormerBlock.STATUS, status), 3);
        }
    }

    private List<BlockPos> getSortedLinks() {
        List<BlockPos> sortedLinks = new ArrayList<>(this.links);
        sortedLinks.sort(TARGET_ORDER);
        return sortedLinks;
    }

    @Nullable
    private IGridConnection findLiveConnection(IGridNode myNode, IGridNode otherNode) {
        for (IGridConnection connection : myNode.getConnections()) {
            if (this.getOtherSide(connection, myNode) == otherNode) {
                return connection;
            }
        }
        return null;
    }

    private boolean isLiveConnection(
            @Nullable IGridConnection connection,
            IGridNode myNode,
            @Nullable IGridNode expectedOtherNode) {
        if (connection == null || !myNode.getConnections().contains(connection)) {
            return false;
        }

        IGridNode actualOtherNode = this.getOtherSide(connection, myNode);
        if (actualOtherNode == null) {
            return false;
        }

        return expectedOtherNode == null || actualOtherNode == expectedOtherNode;
    }

    @Nullable
    private IGridNode getOtherSide(IGridConnection connection, IGridNode myNode) {
        try {
            return connection.getOtherSide(myNode);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return null;
        }
    }

    private void destroyConnection(IGridConnection connection) {
        try {
            connection.destroy();
        } catch (IllegalArgumentException | IllegalStateException ignored) {
        }
    }
}
