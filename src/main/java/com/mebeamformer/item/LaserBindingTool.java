package com.mebeamformer.item;

import com.mebeamformer.blockentity.OmniBeamFormerBlockEntity;
import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import com.mebeamformer.blockentity.ILinkable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;

public class LaserBindingTool extends Item {
    private static final String TAG_SOURCE = "SourcePos";
    private static final String TAG_SOURCE_TYPE = "SourceType";
    private static final String TYPE_TOWER = "tower";
    private static final String TYPE_OMNI = "omni";

    public LaserBindingTool(Properties props) {
        super(props);
    }

    /**
     * 检查方块实体是否有能量存储能力
     */
    private boolean hasEnergyCapability(BlockEntity be) {
        if (be == null) return false;
        for (Direction dir : Direction.values()) {
            if (be.getCapability(ForgeCapabilities.ENERGY, dir).isPresent()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockPos pos = ctx.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        ItemStack stack = ctx.getItemInHand();
        Player player = ctx.getPlayer();

        CompoundTag tag = stack.getOrCreateTag();
        boolean hasSource = tag.contains(TAG_SOURCE);
        boolean isLinkable = be instanceof ILinkable;
        boolean hasEnergy = hasEnergyCapability(be);
        
        if (player != null && player.isShiftKeyDown()) {
            // Shift+右键：有两种情况
            // 1. 选择ILinkable作为源
            // 2. 如果已有源且源是无线能源感应塔，则绑定目标
            
            if (hasSource) {
                // 已有源，检查源类型
                String sourceType = tag.getString(TAG_SOURCE_TYPE);
                if (TYPE_TOWER.equals(sourceType)) {
                    // 源是无线能源感应塔
                    // 如果目标也是ILinkable（如全向光束成型器），优先重新选择它作为源
                    if (isLinkable) {
                        // 跳过绑定逻辑，直接到下面的"选择新源"逻辑
                        // 不做任何处理，继续执行
                    } else if (hasEnergy) {
                        // 目标不是ILinkable但有能量能力，执行绑定逻辑
                        CompoundTag t = tag.getCompound(TAG_SOURCE);
                        BlockPos source = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
                        
                        if (source.equals(pos)) {
                            // 点击相同方块：提示不能连接自己
                            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.self_link"), true);
                            return InteractionResult.SUCCESS;
                        }
                        
                        BlockEntity beSource = level.getBlockEntity(source);
                        if (beSource instanceof WirelessEnergyTowerBlockEntity sourceEntity) {
                            // 无线能源感应塔没有距离限制
                            
                            // 检查是否已经连接
                            if (sourceEntity.getLinks().contains(pos)) {
                                // 已连接，断开连接
                                sourceEntity.removeLink(pos);
                                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.tower_unlinked", source.getX(), source.getY(), source.getZ(), pos.getX(), pos.getY(), pos.getZ()), true);
                            } else {
                                // 未连接，建立链接：从源到目标的单向连接
                                sourceEntity.addLink(pos);
                                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.tower_linked", source.getX(), source.getY(), source.getZ(), pos.getX(), pos.getY(), pos.getZ()), true);
                            }
                            return InteractionResult.SUCCESS;
                        } else {
                            // 源位置不再存在有效的无线能源感应塔
                            tag.remove(TAG_SOURCE);
                            tag.remove(TAG_SOURCE_TYPE);
                            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.invalid"), true);
                            return InteractionResult.SUCCESS;
                        }
                    } else {
                        // 目标既不是ILinkable也没有能量能力，返回PASS
                        return InteractionResult.PASS;
                    }
                }
                // 源是全向光束成型器，或源是能源塔但目标是ILinkable，继续选择新源的逻辑
            }
            
            // 没有源，或源是全向光束成型器时，Shift+右键选择新源
            if (!isLinkable) {
                return InteractionResult.PASS;
            }
            
            // Shift+右键：选定被连接的成型器（源）
            CompoundTag t = new CompoundTag();
            t.putInt("x", pos.getX());
            t.putInt("y", pos.getY());
            t.putInt("z", pos.getZ());
            tag.put(TAG_SOURCE, t);
            
            // 记录源类型并显示对应提示
            if (be instanceof WirelessEnergyTowerBlockEntity) {
                tag.putString(TAG_SOURCE_TYPE, TYPE_TOWER);
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.set_tower", pos.getX(), pos.getY(), pos.getZ()), true);
            } else if (be instanceof OmniBeamFormerBlockEntity) {
                tag.putString(TAG_SOURCE_TYPE, TYPE_OMNI);
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.set_omni", pos.getX(), pos.getY(), pos.getZ()), true);
            } else {
                tag.putString(TAG_SOURCE_TYPE, TYPE_OMNI);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.set", pos.getX(), pos.getY(), pos.getZ()), true);
            }
            return InteractionResult.CONSUME;
        } else {
            // 普通右键：仅对全向光束成型器有效，无线能源感应塔需要Shift+右键
            if (!hasSource) {
                // 没有选定源
                if (isLinkable) {
                    // 如果是ILinkable，提示先选定源，并阻止UI打开
                    if (player != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.no_source"), true);
                    }
                    return InteractionResult.SUCCESS;
                } else {
                    // 不是ILinkable，让其他交互继续（例如打开箱子等）
                    return InteractionResult.PASS;
                }
            }
            
            // 已选定源，检查源类型
            String sourceType = tag.getString(TAG_SOURCE_TYPE);
            if (TYPE_TOWER.equals(sourceType)) {
                // 源是无线能源感应塔，提示需要Shift+右键绑定
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.tower_needs_shift"), true);
                }
                return InteractionResult.SUCCESS;
            }
            
            // 源是全向光束成型器，执行普通右键绑定逻辑
            // 全向光束成型器只能连接其他全向光束成型器
            if (!(be instanceof OmniBeamFormerBlockEntity)) {
                // 目标不是全向光束成型器
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.omni_only"), true);
                }
                return InteractionResult.SUCCESS;
            }
            
            CompoundTag t = tag.getCompound(TAG_SOURCE);
            BlockPos source = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
            
            if (source.equals(pos)) {
                // 点击相同方块：提示不能连接自己
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.self_link"), true);
                }
                return InteractionResult.SUCCESS;
            }
            
            BlockEntity beSource = level.getBlockEntity(source);
            if (beSource instanceof OmniBeamFormerBlockEntity sourceEntity) {
                // 对于OmniBeamFormerBlockEntity，检查距离限制：水平范围16x16，垂直范围32
                int dx = Math.abs(pos.getX() - source.getX());
                int dy = Math.abs(pos.getY() - source.getY());
                int dz = Math.abs(pos.getZ() - source.getZ());
                
                if (dx > 16 || dz > 16 || dy > 32) {
                    // 超出连接范围
                    if (player != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.out_of_range"), true);
                    }
                    return InteractionResult.SUCCESS;
                }
                
                // 检查是否已经连接
                if (sourceEntity.getLinks().contains(pos)) {
                    // 已连接，断开连接
                    sourceEntity.removeLink(pos);
                    if (player != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.omni_unlinked", source.getX(), source.getY(), source.getZ(), pos.getX(), pos.getY(), pos.getZ()), true);
                    }
                } else {
                    // 未连接，建立链接：从源到目标的单向连接
                    sourceEntity.addLink(pos);
                    if (player != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.omni_linked", source.getX(), source.getY(), source.getZ(), pos.getX(), pos.getY(), pos.getZ()), true);
                    }
                }
                // 注意：不清空TAG_SOURCE，保持源信息，可以继续连接/断开其他目标
                return InteractionResult.SUCCESS;
            } else {
                // 源位置不再存在有效的成型器
                tag.remove(TAG_SOURCE);
                tag.remove(TAG_SOURCE_TYPE);
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.invalid"), true);
                }
                return InteractionResult.SUCCESS;
            }
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player.isShiftKeyDown()) {
            // Shift+左键：清空已选定的源
            CompoundTag tag = stack.getTag();
            if (tag != null && tag.contains(TAG_SOURCE)) {
                tag.remove(TAG_SOURCE);
                tag.remove(TAG_SOURCE_TYPE);
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.cleared"), true);
                return InteractionResultHolder.consume(stack);
            }
        }
        return super.use(level, player, hand);
    }
}
