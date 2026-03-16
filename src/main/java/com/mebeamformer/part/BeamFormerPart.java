package com.mebeamformer.part;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.util.AECableType;
import appeng.items.parts.PartModels;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.mebeamformer.ME_Beam_Former.MODID;

public class BeamFormerPart extends AEBasePart implements IGridTickable {
    private static final ResourceLocation MODEL_BASE_LOC = new ResourceLocation(MODID, "part/beam_former_base");
    private static final IPartModel MODEL = new PartModel(MODEL_BASE_LOC);
    private static final int MAX_BEAM_RANGE = 32;
    private static final TickingRequest TICKING_REQUEST =
            new TickingRequest(appeng.core.settings.TickRates.LightTunnel, false, true);

    private int beamLength;
    @Nullable
    private BeamFormerPart other;
    @Nullable
    private IGridConnection connection;
    private boolean hideBeam;

    public BeamFormerPart(IPartItem<?> partItem) {
        super(partItem);
        getMainNode().setFlags(GridFlags.DENSE_CAPACITY);
        getMainNode().addService(IGridTickable.class, this);
    }

    @PartModels
    public static List<IPartModel> getModels() {
        return List.of(MODEL);
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(4, 4, 17, 12, 12, 11);
        bch.addBox(5, 5, 19, 11, 11, 17);
        bch.addBox(6, 6, 21, 10, 10, 19);
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return 5f;
    }

    @Override
    public AECableType getExternalCableConnectionType() {
        return AECableType.SMART;
    }

    @Override
    public boolean onPartActivate(Player player, InteractionHand hand, Vec3 pos) {
        if (!player.isShiftKeyDown()) {
            return false;
        }

        if (!player.level().isClientSide) {
            setBeamHidden(!hideBeam);
        }

        return true;
    }

    @Override
    public IPartModel getStaticModels() {
        return MODEL;
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return TICKING_REQUEST;
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        Level level = getLevelOrNull();
        BlockEntity blockEntity = getBlockEntity();
        Direction side = getSide();
        if (level == null || blockEntity == null || side == null) {
            disconnect();
            return TickRateModulation.SLEEP;
        }

        ScanResult scan = scanForTarget(level, blockEntity.getBlockPos(), side);
        if (scan.target == null) {
            disconnect();
            return TickRateModulation.SLOWER;
        }

        ensureConnection(scan.target, scan.length);
        return TickRateModulation.SLOWER;
    }

    public int getBeamLength() {
        return beamLength;
    }

    public boolean shouldRenderBeam() {
        return !hideBeam && beamLength > 0 && isPowered();
    }

    public AABB getExtendedRenderBoundingBox(AABB baseBox) {
        if (beamLength <= 0 || !shouldRenderBeam()) {
            return baseBox;
        }

        BlockEntity blockEntity = getBlockEntity();
        Direction direction = getSide();
        if (blockEntity == null || direction == null) {
            return baseBox;
        }

        BlockPos startPos = blockEntity.getBlockPos();
        BlockPos endPos = startPos.relative(direction, beamLength);
        double minX = Math.min(startPos.getX(), endPos.getX());
        double minY = Math.min(startPos.getY(), endPos.getY());
        double minZ = Math.min(startPos.getZ(), endPos.getZ());
        double maxX = Math.max(startPos.getX() + 1, endPos.getX() + 1);
        double maxY = Math.max(startPos.getY() + 1, endPos.getY() + 1);
        double maxZ = Math.max(startPos.getZ() + 1, endPos.getZ() + 1);
        return baseBox.minmax(new AABB(minX - 2.0, minY - 2.0, minZ - 2.0, maxX + 2.0, maxY + 2.0, maxZ + 2.0));
    }

    @Override
    public int getLightLevel() {
        return shouldRenderBeam() ? 15 : 0;
    }

    @Override
    public boolean requireDynamicRender() {
        return true;
    }

    @Override
    public void renderDynamic(float partialTicks, PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource buffers, int combinedLightIn, int combinedOverlayIn) {
        if (!shouldRenderBeam()) {
            return;
        }

        BlockEntity blockEntity = getBlockEntity();
        Level level = getLevelOrNull();
        Direction side = getSide();
        if (blockEntity == null || level == null || side == null) {
            return;
        }

        if (!isPathClearForRender(level, blockEntity.getBlockPos(), side, beamLength)) {
            return;
        }

        float[] sourceColor = com.mebeamformer.client.render.BeamRenderHelper.getPartHostColor(getHost());
        ScanResult scan = scanForTarget(level, blockEntity.getBlockPos(), side);
        float[] targetColor = scan.target != null && scan.length == beamLength
                ? com.mebeamformer.client.render.BeamRenderHelper.getPartHostColor(scan.target.getHost())
                : null;
        float[] beamColor = com.mebeamformer.client.render.BeamRenderHelper.blendEndpointColors(sourceColor, targetColor);

        com.mebeamformer.client.render.BeamRenderHelper.renderColoredBeamForPart(
                poseStack,
                buffers,
                side,
                beamLength,
                beamColor[0],
                beamColor[1],
                beamColor[2],
                combinedLightIn,
                combinedOverlayIn);
    }

    @Override
    public void removeFromWorld() {
        disconnect(false);
        super.removeFromWorld();
    }

    @Override
    public void writeToStream(FriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeVarInt(beamLength);
        data.writeBoolean(hideBeam);
    }

    @Override
    public boolean readFromStream(FriendlyByteBuf data) {
        boolean redraw = super.readFromStream(data);
        int oldLength = beamLength;
        boolean oldHidden = hideBeam;
        beamLength = data.readVarInt();
        hideBeam = data.readBoolean();
        return redraw || oldLength != beamLength || oldHidden != hideBeam;
    }

    @Override
    public void writeVisualStateToNBT(CompoundTag data) {
        super.writeVisualStateToNBT(data);
        data.putInt("beamLength", beamLength);
        data.putBoolean("hideBeam", hideBeam);
    }

    @Override
    public void readVisualStateFromNBT(CompoundTag data) {
        super.readVisualStateFromNBT(data);
        beamLength = data.getInt("beamLength");
        hideBeam = data.getBoolean("hideBeam");
    }

    @Override
    public void writeToNBT(CompoundTag tag) {
        super.writeToNBT(tag);
        CompoundTag beamFormer = new CompoundTag();
        beamFormer.putBoolean("hideBeam", hideBeam);
        tag.put("beamFormer", beamFormer);
    }

    @Override
    public void readFromNBT(CompoundTag tag) {
        super.readFromNBT(tag);
        beamLength = 0;
        hideBeam = false;
        if (tag.contains("beamFormer")) {
            hideBeam = tag.getCompound("beamFormer").getBoolean("hideBeam");
        }
    }

    private void ensureConnection(BeamFormerPart target, int length) {
        if (target == this) {
            disconnect();
            return;
        }

        IGridNode myNode = getGridNode();
        IGridNode targetNode = target.getGridNode();
        if (myNode == null || targetNode == null) {
            disconnect();
            return;
        }

        if (other != null && other != target) {
            disconnect();
        }
        if (target.other != null && target.other != this) {
            target.disconnect();
        }

        IGridConnection activeConnection = findConnection(myNode, targetNode);
        if (activeConnection == null) {
            if (isConnectionToDifferentNode(connection, myNode, targetNode)) {
                disconnect();
            }
            if (target.isConnectionToDifferentNode(target.connection, targetNode, myNode)) {
                target.disconnect();
            }

            activeConnection = findConnection(myNode, targetNode);
            if (activeConnection == null) {
                try {
                    activeConnection = GridHelper.createConnection(myNode, targetNode);
                } catch (IllegalStateException ignored) {
                    activeConnection = findConnection(myNode, targetNode);
                }
            }
        }

        if (activeConnection == null) {
            disconnect();
            return;
        }

        bindConnection(target, activeConnection, length);
    }

    private void bindConnection(BeamFormerPart target, IGridConnection activeConnection, int length) {
        boolean hidden = hideBeam || target.hideBeam;

        boolean thisChanged = connection != activeConnection || other != target || beamLength != length;
        boolean targetChanged = target.connection != activeConnection || target.other != this
                || target.beamLength != length;
        boolean thisHiddenChanged = hideBeam != hidden;
        boolean targetHiddenChanged = target.hideBeam != hidden;

        connection = activeConnection;
        other = target;
        beamLength = length;
        hideBeam = hidden;

        target.connection = activeConnection;
        target.other = this;
        target.beamLength = length;
        target.hideBeam = hidden;

        if (thisChanged || thisHiddenChanged) {
            markStateChanged(thisHiddenChanged);
        }
        if (targetChanged || targetHiddenChanged) {
            target.markStateChanged(targetHiddenChanged);
        }
    }

    public boolean disconnect() {
        return disconnect(true);
    }

    private boolean disconnect(boolean notifySelf) {
        BeamFormerPart partner = other;
        IGridConnection activeConnection = connection;
        IGridNode myNode = getGridNode();

        boolean selfChanged = clearRuntimeState();
        boolean partnerChanged = false;

        if (partner != null && (partner.other == this || partner.connection == activeConnection)) {
            partnerChanged = partner.clearRuntimeState();
        }

        if (activeConnection != null && myNode != null && getOtherSide(activeConnection, myNode) != null) {
            try {
                activeConnection.destroy();
            } catch (IllegalArgumentException | IllegalStateException ignored) {
            }
        }

        if (notifySelf && selfChanged) {
            markStateChanged(false);
        }
        if (partnerChanged) {
            partner.markStateChanged(false);
        }

        return selfChanged || partnerChanged;
    }

    private boolean clearRuntimeState() {
        boolean changed = connection != null || other != null || beamLength != 0;
        connection = null;
        other = null;
        beamLength = 0;
        return changed;
    }

    private void setBeamHidden(boolean hidden) {
        boolean selfChanged = hideBeam != hidden;
        hideBeam = hidden;
        if (selfChanged) {
            markStateChanged(true);
        }

        if (other != null && other.hideBeam != hidden) {
            other.hideBeam = hidden;
            other.markStateChanged(true);
        }
    }

    private void markStateChanged(boolean persist) {
        IPartHost host = getHost();
        if (host == null) {
            return;
        }

        host.markForUpdate();
        if (persist) {
            host.markForSave();
        }
        host.partChanged();
    }

    @Nullable
    private Level getLevelOrNull() {
        BlockEntity blockEntity = getBlockEntity();
        return blockEntity != null ? blockEntity.getLevel() : null;
    }

    private ScanResult scanForTarget(Level level, BlockPos startPos, Direction direction) {
        BlockPos cursor = startPos;

        for (int i = 0; i < MAX_BEAM_RANGE; i++) {
            cursor = cursor.relative(direction);
            BlockState state = level.getBlockState(cursor);
            PartHostScan partHostScan = inspectPartHost(level.getBlockEntity(cursor), direction);

            if (state.canOcclude() && !state.isAir() && !partHostScan.hasBeamFormer) {
                return ScanResult.none();
            }

            if (partHostScan.target != null) {
                return new ScanResult(partHostScan.target, i + 1);
            }
        }

        return ScanResult.none();
    }

    private boolean isPathClearForRender(Level level, BlockPos startPos, Direction direction, int length) {
        BlockPos cursor = startPos;

        for (int i = 0; i < length; i++) {
            cursor = cursor.relative(direction);
            BlockState state = level.getBlockState(cursor);
            if (state.canOcclude() && !state.isAir()
                    && !inspectPartHost(level.getBlockEntity(cursor), direction).hasBeamFormer) {
                return false;
            }
        }

        return true;
    }

    private PartHostScan inspectPartHost(@Nullable BlockEntity blockEntity, Direction direction) {
        if (!(blockEntity instanceof IPartHost partHost)) {
            return PartHostScan.NONE;
        }

        BeamFormerPart target = null;
        boolean hasBeamFormer = false;
        Direction opposite = direction.getOpposite();

        for (Direction side : Direction.values()) {
            var part = partHost.getPart(side);
            if (part instanceof BeamFormerPart beamFormerPart) {
                hasBeamFormer = true;
                if (side == opposite) {
                    target = beamFormerPart;
                }
            }
        }

        return new PartHostScan(target, hasBeamFormer);
    }

    @Nullable
    private IGridConnection findConnection(IGridNode from, IGridNode to) {
        return from.getConnections().stream()
                .filter(activeConnection -> getOtherSide(activeConnection, from) == to)
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
    private IGridNode getOtherSide(IGridConnection activeConnection, IGridNode node) {
        try {
            return activeConnection.getOtherSide(node);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return null;
        }
    }

    private static final class ScanResult {
        private static final ScanResult NONE = new ScanResult(null, 0);

        @Nullable
        private final BeamFormerPart target;
        private final int length;

        private ScanResult(@Nullable BeamFormerPart target, int length) {
            this.target = target;
            this.length = length;
        }

        private static ScanResult none() {
            return NONE;
        }
    }

    private static final class PartHostScan {
        private static final PartHostScan NONE = new PartHostScan(null, false);

        @Nullable
        private final BeamFormerPart target;
        private final boolean hasBeamFormer;

        private PartHostScan(@Nullable BeamFormerPart target, boolean hasBeamFormer) {
            this.target = target;
            this.hasBeamFormer = hasBeamFormer;
        }
    }
}
