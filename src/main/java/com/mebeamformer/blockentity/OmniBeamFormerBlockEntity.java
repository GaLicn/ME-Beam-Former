package com.mebeamformer.blockentity;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;
import appeng.blockentity.grid.AENetworkBlockEntity;
import com.mebeamformer.ME_Beam_Former;
import com.mebeamformer.block.OmniBeamFormerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
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

public class OmniBeamFormerBlockEntity extends AENetworkBlockEntity implements ILinkable {
    private static final Comparator<BlockPos> TARGET_ORDER = Comparator.comparingLong(BlockPos::asLong);

    private final Set<BlockPos> links = new HashSet<>();
    private final Map<BlockPos, IGridConnection> connections = new HashMap<>();
    private List<BlockPos> activeTargets = List.of();
    private List<BlockPos> clientActiveTargets = List.of();
    @Nullable
    private Direction lastExposedBack;

    public OmniBeamFormerBlockEntity(BlockPos pos, BlockState state) {
        super(ME_Beam_Former.OMNI_BEAM_FORMER_BE.get(), pos, state);
        getMainNode().setFlags(GridFlags.DENSE_CAPACITY);
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        Direction facing = getBlockState().getValue(OmniBeamFormerBlock.FACING);
        return dir == facing.getOpposite() ? AECableType.SMART : AECableType.NONE;
    }

    @Override
    public Set<Direction> getGridConnectableSides(appeng.api.orientation.BlockOrientation orientation) {
        Direction facing = getBlockState().getValue(OmniBeamFormerBlock.FACING);
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
        if (other.equals(getBlockPos())) {
            return;
        }

        if (links.add(other)) {
            setChanged();
        }
    }

    @Override
    public void removeLink(BlockPos other) {
        if (!links.remove(other)) {
            return;
        }

        releaseConnection(other, getMainNode().getNode(), true);
        removeActiveTarget(other);
        setChanged();
    }

    @Override
    public Set<BlockPos> getLinks() {
        return Collections.unmodifiableSet(links);
    }

    public List<BlockPos> getClientActiveTargets() {
        return clientActiveTargets;
    }

    @Override
    protected void writeToStream(FriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeVarInt(activeTargets.size());
        for (BlockPos pos : activeTargets) {
            data.writeBlockPos(pos);
        }
    }

    @Override
    protected boolean readFromStream(FriendlyByteBuf data) {
        boolean changed = super.readFromStream(data);
        int count = data.readVarInt();
        List<BlockPos> updatedTargets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            updatedTargets.add(data.readBlockPos());
        }

        List<BlockPos> immutableTargets = updatedTargets.isEmpty() ? List.of() : List.copyOf(updatedTargets);
        boolean targetsChanged = !immutableTargets.equals(clientActiveTargets);
        clientActiveTargets = immutableTargets;
        return changed || targetsChanged;
    }

    @Override
    public void onChunkUnloaded() {
        disconnectAll();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        disconnectAll();
        super.setRemoved();
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag list = new ListTag();
        for (BlockPos pos : links) {
            CompoundTag targetTag = new CompoundTag();
            targetTag.putInt("x", pos.getX());
            targetTag.putInt("y", pos.getY());
            targetTag.putInt("z", pos.getZ());
            list.add(targetTag);
        }
        tag.put("links", list);
    }

    @Override
    public void loadTag(CompoundTag tag) {
        super.loadTag(tag);
        links.clear();
        connections.clear();
        activeTargets = List.of();
        clientActiveTargets = List.of();
        lastExposedBack = null;

        if (tag.contains("links", Tag.TAG_LIST)) {
            ListTag list = tag.getList("links", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag targetTag = list.getCompound(i);
                links.add(new BlockPos(
                        targetTag.getInt("x"),
                        targetTag.getInt("y"),
                        targetTag.getInt("z")));
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public AABB getRenderBoundingBox() {
        if (clientActiveTargets.isEmpty()) {
            BlockPos pos = getBlockPos();
            return new AABB(
                    pos.getX() - 5, pos.getY() - 5, pos.getZ() - 5,
                    pos.getX() + 6, pos.getY() + 6, pos.getZ() + 6);
        }

        BlockPos pos = getBlockPos();
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        double maxX = pos.getX() + 1;
        double maxY = pos.getY() + 1;
        double maxZ = pos.getZ() + 1;

        for (BlockPos target : clientActiveTargets) {
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
        IGridNode myNode = getMainNode().getNode();
        if (myNode != null) {
            for (IGridConnection connection : new HashSet<>(connections.values())) {
                if (isLiveConnection(connection, myNode, null)) {
                    destroyConnection(connection);
                }
            }
        }

        clearRuntimeState();
    }

    private void clearRuntimeState() {
        connections.clear();
        syncActiveTargets(List.of());
    }

    @Nullable
    private IGridConnection ensureConnection(BlockPos targetPos, IGridNode myNode, IGridNode otherNode) {
        IGridConnection cachedConnection = connections.get(targetPos);
        if (isLiveConnection(cachedConnection, myNode, otherNode)) {
            return cachedConnection;
        }

        if (isLiveConnection(cachedConnection, myNode, null)) {
            destroyConnection(cachedConnection);
        }
        connections.remove(targetPos);

        IGridConnection liveConnection = findLiveConnection(myNode, otherNode);
        if (liveConnection == null) {
            try {
                liveConnection = GridHelper.createConnection(myNode, otherNode);
            } catch (IllegalStateException ignored) {
                liveConnection = findLiveConnection(myNode, otherNode);
            }
        }

        if (liveConnection != null) {
            connections.put(targetPos, liveConnection);
        }
        return liveConnection;
    }

    private void releaseConnection(BlockPos targetPos, @Nullable IGridNode myNode, boolean destroyLiveConnection) {
        IGridConnection cachedConnection = connections.remove(targetPos);
        if (!destroyLiveConnection || myNode == null) {
            return;
        }

        if (isLiveConnection(cachedConnection, myNode, null)) {
            destroyConnection(cachedConnection);
            return;
        }

        IGridNode targetNode = getTargetNode(targetPos);
        if (targetNode == null) {
            return;
        }

        IGridConnection liveConnection = findLiveConnection(myNode, targetNode);
        if (liveConnection != null) {
            destroyConnection(liveConnection);
        }
    }

    @Nullable
    private IGridNode getTargetNode(BlockPos targetPos) {
        if (level == null || !level.hasChunkAt(targetPos)) {
            return null;
        }

        var targetEntity = level.getBlockEntity(targetPos);
        if (targetEntity instanceof OmniBeamFormerBlockEntity other && !other.isRemoved()) {
            return other.getMainNode().getNode();
        }

        return null;
    }

    private void syncActiveTargets(List<BlockPos> activeNow) {
        List<BlockPos> immutableTargets = activeNow.isEmpty() ? List.of() : List.copyOf(activeNow);
        if (!immutableTargets.equals(activeTargets)) {
            activeTargets = immutableTargets;
            markForUpdate();
        }
    }

    private void removeActiveTarget(BlockPos targetPos) {
        if (!activeTargets.contains(targetPos)) {
            return;
        }

        List<BlockPos> updatedTargets = new ArrayList<>(activeTargets);
        updatedTargets.remove(targetPos);
        syncActiveTargets(updatedTargets);
        updateOwnStatus(updatedTargets.isEmpty()
                ? OmniBeamFormerBlock.Status.ON
                : OmniBeamFormerBlock.Status.BEAMING);
    }

    private void syncExposedBack(Direction back) {
        if (lastExposedBack != back) {
            getMainNode().setExposedOnSides(EnumSet.of(back));
            lastExposedBack = back;
        }
    }

    private void updateOwnStatus(OmniBeamFormerBlock.Status status) {
        if (level == null || isRemoved()) {
            return;
        }

        BlockState currentState = getBlockState();
        if (currentState.getBlock() instanceof OmniBeamFormerBlock
                && currentState.getValue(OmniBeamFormerBlock.STATUS) != status) {
            level.setBlock(getBlockPos(), currentState.setValue(OmniBeamFormerBlock.STATUS, status), 3);
        }
    }

    private List<BlockPos> getSortedLinks() {
        List<BlockPos> sortedLinks = new ArrayList<>(links);
        sortedLinks.sort(TARGET_ORDER);
        return sortedLinks;
    }

    @Nullable
    private IGridConnection findLiveConnection(IGridNode myNode, IGridNode otherNode) {
        for (IGridConnection connection : myNode.getConnections()) {
            if (getOtherSide(connection, myNode) == otherNode) {
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

        IGridNode actualOtherNode = getOtherSide(connection, myNode);
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
