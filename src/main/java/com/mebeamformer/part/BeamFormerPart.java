package com.mebeamformer.part;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartItem;
import appeng.api.parts.IPartModel;
import appeng.api.util.AECableType;
import appeng.api.networking.GridHelper;
import appeng.items.parts.PartModels;
import appeng.parts.AEBasePart;
import appeng.parts.PartModel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import appeng.api.parts.IPartHost;
import appeng.api.networking.IGridConnection;
import appeng.api.util.AEColor;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import static com.mebeamformer.ME_Beam_Former.MODID;

public class BeamFormerPart extends AEBasePart implements IGridTickable {
    // 先以最小可用模型占位，后续再根据状态切换
    private static final ResourceLocation MODEL_PRISM = ResourceLocation.fromNamespaceAndPath(MODID, "part/beam_former_prism");
    private static final IPartModel MODEL = new PartModel(MODEL_PRISM);

    private int beamLength = 0;
    private BeamFormerPart other = null;
    private IGridConnection connection = null;
    private boolean hideBeam = false;

    public BeamFormerPart(IPartItem<?> partItem) {
        super(partItem);
        // 暂不设置特殊 GridFlags，等逻辑补齐后再调优
        getMainNode().addService(IGridTickable.class, this);
    }

    @PartModels
    public static List<IPartModel> getModels() {
        return List.of(MODEL);
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        // 使用简单碰撞盒，后续按旧版形状还原
        bch.addBox(6, 6, 6, 10, 10, 10);
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return 5f;
    }

    @Override
    public boolean onPartActivate(Player player, InteractionHand hand, Vec3 pos) {
        // Shift 右键切换可见性（无需外部工具）
        if (player.isShiftKeyDown()) {
            hideBeam = !hideBeam;
            if (other != null) {
                other.hideBeam = hideBeam;
                other.getHost().markForUpdate();
                other.getHost().markForSave();
                other.getHost().partChanged();
            }
            getHost().markForUpdate();
            getHost().markForSave();
            getHost().partChanged();
            return true;
        }
        return false;
    }

    @Override
    public IPartModel getStaticModels() {
        // 先固定返回占位模型
        return MODEL;
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        // 简化：选择接近旧版用途的节奏，后续可根据需要单独新增 TickRates 常量
        return new TickingRequest(appeng.core.settings.TickRates.LightTunnel, false, true);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        var host = getHost();
        var be = host.getBlockEntity();
        Level level = be.getLevel();
        if (level == null) return TickRateModulation.SLEEP;

        var dir = getSide();
        BlockPos cur = be.getBlockPos();
        Direction facing = dir;
        Set<BlockPos> path = new LinkedHashSet<>();

        boolean found = false;
        BeamFormerPart target = null;

        for (int i = 0; i < 32; i++) {
            cur = cur.relative(facing);
            BlockState state = level.getBlockState(cur);

            // 阻挡判断：不可遮挡方块则阻挡
            if (state.canOcclude() && !state.isAir()) {
                // 碰到实体宿主也要进一步判断是否 Part 本身
                BlockEntity otherBe = level.getBlockEntity(cur);
                if (otherBe instanceof IPartHost ph) {
                    var opposite = facing.getOpposite();
                    var p = ph.getPart(opposite);
                    if (p instanceof BeamFormerPart bf) {
                        target = bf;
                        found = true;
                        break;
                    }
                }
                // 被遮挡，断开
                disconnect(null);
                return TickRateModulation.SLOWER;
            }

            // 非遮挡，记录路径并继续
            path.add(cur);

            // 检查是否遇到线缆总线带有对向 Part
            BlockEntity otherBe = level.getBlockEntity(cur);
            if (otherBe instanceof IPartHost ph) {
                var opposite = facing.getOpposite();
                var p = ph.getPart(opposite);
                if (p instanceof BeamFormerPart bf) {
                    target = bf;
                    found = true;
                    break;
                }
                // 不要穿过另一个同向的 BeamFormer
                if (ph.getPart(facing) instanceof BeamFormerPart) {
                    disconnect(null);
                    return TickRateModulation.SLOWER;
                }
            }
        }

        if (found && target != null) {
            // 已连接且仍然指向对方，直接休眠
            if (this.other == target && target.other == this && this.connection != null) {
                return TickRateModulation.SLEEP;
            }

            // 否则尝试建立连接
            tryConnect(target, path);
            return TickRateModulation.SLEEP;
        }

        // 未找到对端，若原本有连接则断开
        if (this.connection != null || this.other != null || this.beamLength != 0) {
            disconnect(null);
            return TickRateModulation.SLOWER;
        }

        return TickRateModulation.SLOWER;
    }

    // 便于客户端判定朝向与世界
    public Direction getDirection() {
        return getSide();
    }

    public boolean isValidClient() {
        var be = getBlockEntity();
        var level = be != null ? be.getLevel() : null;
        return level != null && level == Minecraft.getInstance().level;
    }

    public int getBeamLength() { return beamLength; }
    public boolean shouldRenderBeam() {
        // 邻接时 beamLength 可能为 0，但如果已连接，也允许渲染一个最小长度
        return !hideBeam && (beamLength > 0 || other != null) && isActive();
    }

    @Override
    public int getLightLevel() {
        return shouldRenderBeam() ? 15 : 0;
    }

    @Override
    public boolean requireDynamicRender() {
        // 始终启用动态渲染，以确保 AE2 的 CableBus 切换到 TESR 渲染路径。
        // 实际是否绘制由 renderDynamic 内部的 shouldRenderBeam() 决定。
        return true;
    }

    @Override
    public void renderDynamic(float partialTicks, com.mojang.blaze3d.vertex.PoseStack poseStack,
                              net.minecraft.client.renderer.MultiBufferSource buffers,
                              int combinedLightIn, int combinedOverlayIn) {
        if (!shouldRenderBeam()) return;
        // 颜色来自线缆颜色中等变体
        AEColor color = getHost().getColor();
        float scale = 255f;
        float r = ((color.mediumVariant >> 16) & 0xFF) / scale;
        float g = ((color.mediumVariant >> 8) & 0xFF) / scale;
        float b = (color.mediumVariant & 0xFF) / scale;

        var dir = getSide();
        double visibleLen = beamLength > 0 ? beamLength : 0.5d;
        com.mebeamformer.client.render.BeamRenderHelper.renderColoredBeam(
                poseStack, buffers, dir, visibleLen, r, g, b, combinedLightIn, combinedOverlayIn);
    }

    private void tryConnect(BeamFormerPart target, Set<BlockPos> path) {
        // 建立 AE2 直接连接（距离视为 1）
        var a = this.getGridNode();
        var b = target.getGridNode();
        if (a == null || b == null) return;
        // 先断开旧连接
        disconnect(null);
        target.disconnect(null);
        this.connection = GridHelper.createConnection(a, b);
        this.other = target;
        target.other = this;
        // 一端显示长度，另一端长度为 0（与旧版一致）
        this.beamLength = path.size();
        target.beamLength = 0;
        // 同步隐藏标志
        if (this.hideBeam || target.hideBeam) {
            this.hideBeam = true;
            target.hideBeam = true;
        }
        // 刷新可见
        this.getHost().markForUpdate();
        this.getHost().markForSave();
        this.getHost().partChanged();
        target.getHost().markForUpdate();
        target.getHost().markForSave();
        target.getHost().partChanged();
    }

    public boolean disconnect(BlockPos breakPos) {
        boolean changed = false;
        if (this.connection != null) {
            this.connection.destroy();
            this.connection = null;
            changed = true;
        }
        if (this.other != null) {
            this.other.other = null;
            this.other.getHost().markForUpdate();
            this.other.getHost().markForSave();
            this.other = null;
            changed = true;
        }
        if (breakPos != null) {
            // 粗略处理：根据断点缩短长度
            this.beamLength = Math.max(0, Math.min(this.beamLength, breakPos.distManhattan(getBlockEntity().getBlockPos())));
        } else {
            this.beamLength = 0;
        }
        if (changed) {
            this.getHost().markForUpdate();
            this.getHost().markForSave();
            this.getHost().partChanged();
        }
        return changed;
    }

    // NBT/网络同步（只同步最少可视所需数据）
    @Override
    public void writeToStream(net.minecraft.network.FriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeVarInt(beamLength);
        data.writeBoolean(hideBeam);
    }

    @Override
    public boolean readFromStream(net.minecraft.network.FriendlyByteBuf data) {
        boolean redraw = super.readFromStream(data);
        int oldLen = this.beamLength;
        boolean oldHide = this.hideBeam;
        this.beamLength = data.readVarInt();
        this.hideBeam = data.readBoolean();
        return redraw || oldLen != beamLength || oldHide != hideBeam;
    }

    @Override
    public void writeToNBT(net.minecraft.nbt.CompoundTag tag) {
        super.writeToNBT(tag);
        var v = new net.minecraft.nbt.CompoundTag();
        v.putInt("beamLength", beamLength);
        v.putBoolean("hideBeam", hideBeam);
        tag.put("beamFormer", v);
    }

    @Override
    public void readFromNBT(net.minecraft.nbt.CompoundTag tag) {
        super.readFromNBT(tag);
        if (tag.contains("beamFormer")) {
            var v = tag.getCompound("beamFormer");
            this.beamLength = v.getInt("beamLength");
            this.hideBeam = v.getBoolean("hideBeam");
        }
    }
}
