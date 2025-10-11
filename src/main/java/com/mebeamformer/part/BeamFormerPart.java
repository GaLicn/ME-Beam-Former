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
    // 模型结构：base + status(overlay) + prism
    private static final ResourceLocation MODEL_BASE_LOC = new ResourceLocation(MODID, "part/beam_former_base");
    private static final ResourceLocation STATUS_OFF_LOC = new ResourceLocation(MODID, "part/beam_former_status_off");
    private static final ResourceLocation STATUS_ON_LOC = new ResourceLocation(MODID, "part/beam_former_status_on");
    private static final ResourceLocation STATUS_BEAMING_LOC = new ResourceLocation(MODID, "part/beam_former_status_beaming");
    private static final ResourceLocation PRISM_LOC = new ResourceLocation(MODID, "part/beam_former_prism");

    private static final IPartModel MODEL_BEAMING = new PartModel(STATUS_BEAMING_LOC, MODEL_BASE_LOC);
    private static final IPartModel MODEL_ON = new PartModel(STATUS_ON_LOC, MODEL_BASE_LOC, PRISM_LOC);
    private static final IPartModel MODEL_OFF = new PartModel(STATUS_OFF_LOC, MODEL_BASE_LOC, PRISM_LOC);

    private int beamLength = 0;
    private BeamFormerPart other = null;
    private IGridConnection connection = null;
    private boolean hideBeam = false;

    public BeamFormerPart(IPartItem<?> partItem) {
        super(partItem);
        getMainNode().addService(IGridTickable.class, this);
    }

    @PartModels
    public static List<IPartModel> getModels() {
        return List.of(MODEL_OFF, MODEL_ON, MODEL_BEAMING);
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(10, 10, 12, 6, 6, 11);
        bch.addBox(10, 10, 13, 6, 6, 12);
        bch.addBox(10, 6, 14, 6, 5, 13);
        bch.addBox(11, 9, 17, 10, 7, 14);
        bch.addBox(9, 11, 17, 7, 10, 14);
        bch.addBox(6, 9, 17, 5, 7, 14);
        bch.addBox(9, 6, 17, 7, 5, 14);
        bch.addBox(10, 11, 14, 6, 10, 13);
        bch.addBox(6, 10, 14, 5, 6, 13);
        bch.addBox(11, 9, 13, 10, 7, 12);
        bch.addBox(6, 9, 13, 5, 7, 12);
        bch.addBox(9, 6, 13, 7, 5, 12);
        bch.addBox(9, 11, 13, 7, 10, 12);
        bch.addBox(11, 10, 14, 10, 6, 13);
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
        // 已配对（有光束）-> BEAMING；通电但未配对 -> ON；否则 OFF
        // 注意：即使Part未激活（如缺频道），只要有光束连接就显示BEAMING状态
        if (beamLength > 0 || other != null) {
            return MODEL_BEAMING;
        }
        if (this.isActive()) {
            return MODEL_ON;
        }
        return MODEL_OFF;
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
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

        // 总是完整扫描路径，即使已连接也要检查是否有新的阻挡
        for (int i = 0; i < 32; i++) {
            cur = cur.relative(facing);
            BlockState state = level.getBlockState(cur);

            // 检查方块实体，看是否有BeamFormerPart
            BlockEntity otherBe = level.getBlockEntity(cur);
            boolean hasBeamFormerPart = false;
            
            if (otherBe instanceof IPartHost ph) {
                // 检查对向是否有BeamFormerPart - 如果有就是目标
                var opposite = facing.getOpposite();
                var p = ph.getPart(opposite);
                if (p instanceof BeamFormerPart bf) {
                    target = bf;
                    found = true;
                    // 不要break，继续检查路径完整性
                }
                
                // 检查是否有任何BeamFormerPart（用于判断是否允许穿过）
                for (Direction d : Direction.values()) {
                    if (ph.getPart(d) instanceof BeamFormerPart) {
                        hasBeamFormerPart = true;
                        break;
                    }
                }
            }
            
            // 阻挡判断：可遮挡的实体方块
            if (state.canOcclude() && !state.isAir()) {
                // 如果这个位置有BeamFormerPart（线缆总线），允许光束穿过
                if (hasBeamFormerPart) {
                    path.add(cur);
                    // 如果已经找到目标且就是这个位置，结束扫描
                    if (found && target != null && otherBe == target.getBlockEntity()) {
                        break;
                    }
                    continue;
                }
                // 否则被实体方块阻挡，断开连接
                disconnect(null);
                return TickRateModulation.SLOWER;
            }

            // 非遮挡方块，记录路径并继续
            path.add(cur);
            
            // 如果已经找到目标，结束扫描
            if (found && target != null) {
                break;
            }
        }

        if (found && target != null) {
            // 已连接且仍然指向对方，更新路径长度
            if (this.other == target && target.other == this && this.connection != null) {
                // 更新光束长度（路径可能因为中间添加/移除了BeamFormerPart而变化）
                int oldLen = this.beamLength;
                this.beamLength = path.size();
                if (oldLen != this.beamLength) {
                    this.getHost().markForUpdate();
                }
                return TickRateModulation.SLOWER;
            }

            // 否则尝试建立连接
            tryConnect(target, path);
            return TickRateModulation.SLOWER;
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
        // 渲染光束的条件：
        // 1. 没有隐藏光束
        // 2. 有光束长度或有连接的目标
        // 3. 本端有电（isPowered）
        // 4. 如果有连接目标，目标也要有电
        // 注意：不检查频道（isMissingChannel），因为频道不足不应影响物理连接的可见性
        if (hideBeam) return false;
        if (beamLength <= 0 && other == null) return false;
        
        // 本端必须有电
        if (!isPowered()) return false;
        
        // 如果有连接目标，目标也必须有电
        if (other != null && !other.isPowered()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取包含光束路径的扩展渲染边界框。
     * 这个方法用于被Mixin调用，以扩展CableBusBlockEntity的渲染边界框。
     * 
     * @param baseBox 原始的边界框（通常是Part所在方块的AABB）
     * @return 扩展后的AABB，包含整个光束路径
     */
    public net.minecraft.world.phys.AABB getExtendedRenderBoundingBox(net.minecraft.world.phys.AABB baseBox) {
        // 如果没有光束，返回原始边界框
        if (beamLength <= 0 || !shouldRenderBeam()) {
            return baseBox;
        }
        
        var be = getBlockEntity();
        if (be == null) {
            return baseBox;
        }
        
        BlockPos pos = be.getBlockPos();
        Direction dir = getSide();
        
        // 计算光束终点位置
        BlockPos endPos = pos.relative(dir, beamLength);
        
        // 创建包含起点和终点的AABB，并稍微扩大以确保完整可见
        return new net.minecraft.world.phys.AABB(pos, endPos).inflate(1.0);
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
        
        // 客户端路径检查：检测实体方块阻挡，但允许路径中有其他BeamFormerPart
        var be = getBlockEntity();
        Level levelRD = be != null ? be.getLevel() : null;
        Direction dirRD = getSide();
        int checkLen = this.beamLength > 0 ? this.beamLength : 1;
        if (levelRD != null && !isPathClearForRender(levelRD, be.getBlockPos(), dirRD, checkLen)) {
            return;
        }
        
        // 颜色来自线缆颜色最鲜艳变体
        AEColor color = getHost().getColor();
        float scale = 255f;
        float r = ((color.blackVariant >> 16) & 0xFF) / scale;
        float g = ((color.blackVariant >> 8) & 0xFF) / scale;
        float b = (color.blackVariant & 0xFF) / scale;

        var dir = getSide();
        double visibleLen = beamLength > 0 ? beamLength : 0.5d;
        com.mebeamformer.client.render.BeamRenderHelper.renderColoredBeamForPart(
                poseStack, buffers, dir, visibleLen, r, g, b, combinedLightIn, combinedOverlayIn);
    }

    /**
     * 客户端渲染前的路径清晰度检查（只读，不修改状态）。
     * 允许光束穿过其他BeamFormerPart，但阻挡实体方块。
     */
    private boolean isPathClearForRender(Level level, BlockPos start, Direction dir, int length) {
        BlockPos cur = start;
        for (int i = 0; i < length; i++) {
            cur = cur.relative(dir);
            BlockState state = level.getBlockState(cur);
            
            // 如果是可遮挡的实体方块
            if (state.canOcclude() && !state.isAir()) {
                BlockEntity otherBe = level.getBlockEntity(cur);
                
                // 如果是PartHost，检查是否有BeamFormerPart
                if (otherBe instanceof IPartHost ph) {
                    boolean hasBeamFormerPart = false;
                    for (Direction d : Direction.values()) {
                        if (ph.getPart(d) instanceof BeamFormerPart) {
                            hasBeamFormerPart = true;
                            break;
                        }
                    }
                    // 如果有BeamFormerPart，允许光束穿过
                    if (hasBeamFormerPart) {
                        continue;
                    }
                }
                
                // 其他实体方块阻挡光束
                return false;
            }
        }
        return true;
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
        // 为了保证任意视角/位置都可见：两端都渲染完整长度，避免因视锥裁剪只渲染近端导致远端不可见
        this.beamLength = path.size();
        target.beamLength = path.size();
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
