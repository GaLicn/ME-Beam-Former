package com.mebeamformer.item;

import com.mebeamformer.block.WirelessEnergyTowerBlock;
import com.mebeamformer.blockentity.ILinkable;
import com.mebeamformer.blockentity.OmniBeamFormerBlockEntity;
import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

public class LaserBindingTool extends Item {
    private static final String TAG_SOURCE = "SourcePos";
    private static final String TAG_SOURCE_TYPE = "SourceType";
    private static final String TAG_RANGE_MODE = "RangeMode";
    private static final String TAG_RANGE_START = "RangeStart";

    private static final String TYPE_TOWER = "tower";
    private static final String TYPE_OMNI = "omni";

    private static final int TOWER_RANGE_XZ = 20;
    private static final int TOWER_RANGE_Y = 256;

    public LaserBindingTool(Properties props) {
        super(props);
    }

    public static void toggleRangeMode(Player player) {
        ItemStack stack = getHeldBindingTool(player);
        if (!(stack.getItem() instanceof LaserBindingTool)) {
            return;
        }

        CompoundTag tag = stack.getOrCreateTag();
        boolean enabled = !tag.getBoolean(TAG_RANGE_MODE);
        tag.putBoolean(TAG_RANGE_MODE, enabled);
        tag.remove(TAG_RANGE_START);

        if (enabled) {
            sendMessage(player, "tooltip.me_beam_former.binding.range_mode_enabled");
        } else {
            sendMessage(player, "tooltip.me_beam_former.binding.range_mode_disabled");
        }
    }

    private static ItemStack getHeldBindingTool(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof LaserBindingTool) {
            return mainHand;
        }

        ItemStack offHand = player.getOffhandItem();
        if (offHand.getItem() instanceof LaserBindingTool) {
            return offHand;
        }

        return ItemStack.EMPTY;
    }

    private static void sendMessage(Player player, String key, Object... args) {
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args), true);
        }
    }

    private static boolean hasStoredPos(CompoundTag tag, String key) {
        return tag != null && tag.contains(key, Tag.TAG_COMPOUND);
    }

    private static BlockPos readPos(CompoundTag tag, String key) {
        CompoundTag posTag = tag.getCompound(key);
        return new BlockPos(posTag.getInt("x"), posTag.getInt("y"), posTag.getInt("z"));
    }

    private static void writePos(CompoundTag tag, String key, BlockPos pos) {
        CompoundTag posTag = new CompoundTag();
        posTag.putInt("x", pos.getX());
        posTag.putInt("y", pos.getY());
        posTag.putInt("z", pos.getZ());
        tag.put(key, posTag);
    }

    private static void clearBindingSelection(CompoundTag tag) {
        tag.remove(TAG_SOURCE);
        tag.remove(TAG_SOURCE_TYPE);
        tag.remove(TAG_RANGE_START);
    }

    private static boolean isTowerSource(CompoundTag tag) {
        return TYPE_TOWER.equals(tag.getString(TAG_SOURCE_TYPE));
    }

    private static boolean isRangeMode(CompoundTag tag) {
        return tag.getBoolean(TAG_RANGE_MODE);
    }

    private static boolean isWithinTowerRange(BlockPos source, BlockPos target) {
        int dx = Math.abs(target.getX() - source.getX());
        int dy = Math.abs(target.getY() - source.getY());
        int dz = Math.abs(target.getZ() - source.getZ());
        return dx <= TOWER_RANGE_XZ && dz <= TOWER_RANGE_XZ && dy <= TOWER_RANGE_Y;
    }

    /**
     * 获取感应塔底部的位置。
     */
    private BlockPos getTowerBasePos(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof WirelessEnergyTowerBlock) {
            int part = state.getValue(WirelessEnergyTowerBlock.PART);
            return pos.below(part);
        }
        return pos;
    }

    /**
     * 获取感应塔底部的方块实体。
     */
    private WirelessEnergyTowerBlockEntity getTowerBlockEntity(Level level, BlockPos pos) {
        BlockPos basePos = getTowerBasePos(level, pos);
        BlockEntity be = level.getBlockEntity(basePos);
        if (be instanceof WirelessEnergyTowerBlockEntity tower) {
            return tower;
        }
        return null;
    }

    /**
     * 检查方块实体是否有能量存储能力。
     */
    private boolean hasEnergyCapability(BlockEntity be) {
        if (be == null) {
            return false;
        }

        for (Direction dir : Direction.values()) {
            if (be.getCapability(ForgeCapabilities.ENERGY, dir).isPresent()) {
                return true;
            }
        }

        try {
            Class<?> gtCapClass = Class.forName("com.gregtechceu.gtceu.api.capability.forge.GTCapability");
            java.lang.reflect.Field field = gtCapClass.getField("CAPABILITY_ENERGY_CONTAINER");
            net.minecraftforge.common.capabilities.Capability<?> gtCap =
                    (net.minecraftforge.common.capabilities.Capability<?>) field.get(null);

            for (Direction dir : Direction.values()) {
                if (be.getCapability(gtCap, dir).isPresent()) {
                    return true;
                }
            }
        } catch (Exception e) {
            // GregTech CEu 未安装或不兼容
        }

        try {
            Class<?> fluxCapClass = Class.forName("sonar.fluxnetworks.api.FluxCapabilities");
            java.lang.reflect.Field field = fluxCapClass.getField("FN_ENERGY_STORAGE");
            net.minecraftforge.common.capabilities.Capability<?> fluxCap =
                    (net.minecraftforge.common.capabilities.Capability<?>) field.get(null);

            for (Direction dir : Direction.values()) {
                if (be.getCapability(fluxCap, dir).isPresent()) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Flux Networks 未安装或不兼容
        }

        return false;
    }

    private boolean isBindableTowerMachine(BlockEntity be) {
        return be != null && !(be instanceof WirelessEnergyTowerBlockEntity) && !(be instanceof ILinkable) && hasEnergyCapability(be);
    }

    private void setSource(CompoundTag tag, BlockPos pos, boolean isTower, BlockEntity be, Player player) {
        writePos(tag, TAG_SOURCE, pos);
        tag.remove(TAG_RANGE_START);

        if (isTower) {
            tag.putString(TAG_SOURCE_TYPE, TYPE_TOWER);
            sendMessage(player, "tooltip.me_beam_former.binding.set_tower", pos.getX(), pos.getY(), pos.getZ());
            return;
        }

        tag.putString(TAG_SOURCE_TYPE, TYPE_OMNI);
        if (be instanceof OmniBeamFormerBlockEntity) {
            sendMessage(player, "tooltip.me_beam_former.binding.set_omni", pos.getX(), pos.getY(), pos.getZ());
        } else {
            sendMessage(player, "tooltip.me_beam_former.binding.set", pos.getX(), pos.getY(), pos.getZ());
        }
    }

    private InteractionResult handleInvalidSource(CompoundTag tag, Player player) {
        clearBindingSelection(tag);
        sendMessage(player, "tooltip.me_beam_former.binding.invalid");
        return InteractionResult.SUCCESS;
    }

    private InteractionResult handleTowerMachineBinding(Level level, CompoundTag tag, Player player, BlockPos targetPos) {
        BlockPos source = readPos(tag, TAG_SOURCE);
        if (source.equals(targetPos)) {
            sendMessage(player, "tooltip.me_beam_former.binding.self_link");
            return InteractionResult.SUCCESS;
        }

        WirelessEnergyTowerBlockEntity sourceEntity = getTowerBlockEntity(level, source);
        if (sourceEntity == null) {
            return handleInvalidSource(tag, player);
        }

        if (!isWithinTowerRange(source, targetPos)) {
            sendMessage(player, "tooltip.me_beam_former.binding.tower_out_of_range");
            return InteractionResult.SUCCESS;
        }

        if (sourceEntity.getLinks().contains(targetPos)) {
            sourceEntity.removeLink(targetPos);
            sendMessage(player, "tooltip.me_beam_former.binding.tower_unlinked",
                    source.getX(), source.getY(), source.getZ(),
                    targetPos.getX(), targetPos.getY(), targetPos.getZ());
        } else {
            sourceEntity.addLink(targetPos);
            sendMessage(player, "tooltip.me_beam_former.binding.tower_linked",
                    source.getX(), source.getY(), source.getZ(),
                    targetPos.getX(), targetPos.getY(), targetPos.getZ());
        }
        return InteractionResult.SUCCESS;
    }

    private RangeBindingResult bindMachinesInRange(Level level, WirelessEnergyTowerBlockEntity sourceEntity, BlockPos source, BlockPos start, BlockPos end) {
        int minX = Math.max(Math.min(start.getX(), end.getX()), source.getX() - TOWER_RANGE_XZ);
        int maxX = Math.min(Math.max(start.getX(), end.getX()), source.getX() + TOWER_RANGE_XZ);
        int minY = Math.max(Math.min(start.getY(), end.getY()), source.getY() - TOWER_RANGE_Y);
        int maxY = Math.min(Math.max(start.getY(), end.getY()), source.getY() + TOWER_RANGE_Y);
        int minZ = Math.max(Math.min(start.getZ(), end.getZ()), source.getZ() - TOWER_RANGE_XZ);
        int maxZ = Math.min(Math.max(start.getZ(), end.getZ()), source.getZ() + TOWER_RANGE_XZ);

        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return new RangeBindingResult(0, 0, 0);
        }

        int found = 0;
        int linked = 0;
        int alreadyLinked = 0;

        for (BlockPos currentPos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockPos targetPos = currentPos.immutable();
            if (targetPos.equals(source) || !level.isLoaded(targetPos)) {
                continue;
            }

            BlockEntity targetBe = level.getBlockEntity(targetPos);
            if (!isBindableTowerMachine(targetBe)) {
                continue;
            }

            found++;
            if (sourceEntity.getLinks().contains(targetPos)) {
                alreadyLinked++;
                continue;
            }

            sourceEntity.addLink(targetPos);
            linked++;
        }

        return new RangeBindingResult(found, linked, alreadyLinked);
    }

    private InteractionResult handleTowerRangeSelection(Level level, CompoundTag tag, Player player, BlockPos clickedPos) {
        BlockPos source = readPos(tag, TAG_SOURCE);
        WirelessEnergyTowerBlockEntity sourceEntity = getTowerBlockEntity(level, source);
        if (sourceEntity == null) {
            return handleInvalidSource(tag, player);
        }

        if (!hasStoredPos(tag, TAG_RANGE_START)) {
            writePos(tag, TAG_RANGE_START, clickedPos);
            sendMessage(player, "tooltip.me_beam_former.binding.range_start_set", clickedPos.getX(), clickedPos.getY(), clickedPos.getZ());
            return InteractionResult.SUCCESS;
        }

        BlockPos start = readPos(tag, TAG_RANGE_START);
        tag.remove(TAG_RANGE_START);
        RangeBindingResult result = bindMachinesInRange(level, sourceEntity, source, start, clickedPos);

        if (result.found() <= 0) {
            sendMessage(player, "tooltip.me_beam_former.binding.range_no_targets");
        } else {
            sendMessage(player, "tooltip.me_beam_former.binding.range_completed", result.linked(), result.alreadyLinked(), result.found());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockPos clickedPos = ctx.getClickedPos();
        ItemStack stack = ctx.getItemInHand();
        Player player = ctx.getPlayer();

        BlockPos pos = getTowerBasePos(level, clickedPos);
        BlockEntity be = level.getBlockEntity(pos);

        BlockState clickedState = level.getBlockState(clickedPos);
        boolean isClickedTower = clickedState.getBlock() instanceof WirelessEnergyTowerBlock;

        CompoundTag tag = stack.getOrCreateTag();
        boolean hasSource = hasStoredPos(tag, TAG_SOURCE);
        boolean isLinkable = be instanceof ILinkable;
        boolean hasEnergy = hasEnergyCapability(be);

        if (player != null && player.isShiftKeyDown()) {
            if (hasSource && isTowerSource(tag) && isRangeMode(tag)) {
                return handleTowerRangeSelection(level, tag, player, clickedPos);
            }

            if (hasSource && isTowerSource(tag)) {
                if (isClickedTower) {
                    sendMessage(player, "tooltip.me_beam_former.binding.tower_to_tower_needs_normal_click");
                    return InteractionResult.SUCCESS;
                }

                if (isBindableTowerMachine(be)) {
                    return handleTowerMachineBinding(level, tag, player, pos);
                }

                if (!isLinkable && !hasEnergy) {
                    return InteractionResult.PASS;
                }
            }

            if (!isLinkable) {
                return InteractionResult.PASS;
            }

            setSource(tag, pos, isClickedTower, be, player);
            return InteractionResult.CONSUME;
        }

        if (!hasSource) {
            if (isLinkable) {
                sendMessage(player, "tooltip.me_beam_former.binding.no_source");
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }

        String sourceType = tag.getString(TAG_SOURCE_TYPE);
        if (TYPE_TOWER.equals(sourceType)) {
            if (!isClickedTower) {
                sendMessage(player, "tooltip.me_beam_former.binding.tower_needs_shift");
                return InteractionResult.SUCCESS;
            }

            BlockPos source = readPos(tag, TAG_SOURCE);
            if (source.equals(pos)) {
                sendMessage(player, "tooltip.me_beam_former.binding.self_link");
                return InteractionResult.SUCCESS;
            }

            if (!isWithinTowerRange(source, pos)) {
                sendMessage(player, "tooltip.me_beam_former.binding.tower_out_of_range");
                return InteractionResult.SUCCESS;
            }

            WirelessEnergyTowerBlockEntity sourceEntity = getTowerBlockEntity(level, source);
            WirelessEnergyTowerBlockEntity targetEntity = getTowerBlockEntity(level, pos);
            if (sourceEntity == null || targetEntity == null) {
                return handleInvalidSource(tag, player);
            }

            boolean isLinked = sourceEntity.getLinks().contains(pos);
            if (isLinked) {
                sourceEntity.removeLink(pos);
                targetEntity.removeLink(source);
                sendMessage(player, "tooltip.me_beam_former.binding.tower_to_tower_unlinked",
                        source.getX(), source.getY(), source.getZ(),
                        pos.getX(), pos.getY(), pos.getZ());
            } else {
                sourceEntity.addLink(pos);
                targetEntity.addLink(source);
                sendMessage(player, "tooltip.me_beam_former.binding.tower_to_tower_linked",
                        source.getX(), source.getY(), source.getZ(),
                        pos.getX(), pos.getY(), pos.getZ());
            }
            return InteractionResult.SUCCESS;
        }

        if (!(be instanceof OmniBeamFormerBlockEntity)) {
            sendMessage(player, "tooltip.me_beam_former.binding.omni_only");
            return InteractionResult.SUCCESS;
        }

        BlockPos source = readPos(tag, TAG_SOURCE);
        if (source.equals(pos)) {
            sendMessage(player, "tooltip.me_beam_former.binding.self_link");
            return InteractionResult.SUCCESS;
        }

        BlockEntity beSource = level.getBlockEntity(source);
        if (!(beSource instanceof OmniBeamFormerBlockEntity sourceEntity)) {
            return handleInvalidSource(tag, player);
        }

        int dx = Math.abs(pos.getX() - source.getX());
        int dy = Math.abs(pos.getY() - source.getY());
        int dz = Math.abs(pos.getZ() - source.getZ());
        if (dx > 16 || dz > 16 || dy > 32) {
            sendMessage(player, "tooltip.me_beam_former.binding.out_of_range");
            return InteractionResult.SUCCESS;
        }

        if (sourceEntity.getLinks().contains(pos)) {
            sourceEntity.removeLink(pos);
            sendMessage(player, "tooltip.me_beam_former.binding.omni_unlinked",
                    source.getX(), source.getY(), source.getZ(),
                    pos.getX(), pos.getY(), pos.getZ());
        } else {
            sourceEntity.addLink(pos);
            sendMessage(player, "tooltip.me_beam_former.binding.omni_linked",
                    source.getX(), source.getY(), source.getZ(),
                    pos.getX(), pos.getY(), pos.getZ());
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player.isShiftKeyDown()) {
            CompoundTag tag = stack.getTag();
            if (hasStoredPos(tag, TAG_SOURCE)) {
                clearBindingSelection(tag);
                sendMessage(player, "tooltip.me_beam_former.binding.cleared");
                return InteractionResultHolder.consume(stack);
            }
        }
        return super.use(level, player, hand);
    }

    private record RangeBindingResult(int found, int linked, int alreadyLinked) {
    }
}
