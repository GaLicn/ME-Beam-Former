package com.mebeamformer.item;

import com.mebeamformer.blockentity.OmniBeamFormerBlockEntity;
import net.minecraft.core.BlockPos;
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

public class LaserBindingTool extends Item {
    private static final String TAG_SOURCE = "SourcePos";

    public LaserBindingTool(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockPos pos = ctx.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        ItemStack stack = ctx.getItemInHand();
        Player player = ctx.getPlayer();

        if (!(be instanceof OmniBeamFormerBlockEntity current)) {
            return InteractionResult.PASS;
        }

        CompoundTag tag = stack.getOrCreateTag();
        
        if (player != null && player.isShiftKeyDown()) {
            // Shift+右键：选定被连接的成型器（源）
            CompoundTag t = new CompoundTag();
            t.putInt("x", pos.getX());
            t.putInt("y", pos.getY());
            t.putInt("z", pos.getZ());
            tag.put(TAG_SOURCE, t);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.set", pos.getX(), pos.getY(), pos.getZ()), true);
            return InteractionResult.CONSUME;
        } else {
            // 普通右键：连接到已选定的源
            if (!tag.contains(TAG_SOURCE)) {
                // 没有选定源，提示先用Shift+右键选定
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.no_source"), true);
                }
                return InteractionResult.CONSUME;
            }
            
            CompoundTag t = tag.getCompound(TAG_SOURCE);
            BlockPos source = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
            
            if (source.equals(pos)) {
                // 点击相同方块：提示不能连接自己
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.self_link"), true);
                }
                return InteractionResult.CONSUME;
            }
            
            BlockEntity beSource = level.getBlockEntity(source);
            if (beSource instanceof OmniBeamFormerBlockEntity sourceEntity) {
                // 检查距离限制：水平范围16x16，垂直范围32
                int dx = Math.abs(pos.getX() - source.getX());
                int dy = Math.abs(pos.getY() - source.getY());
                int dz = Math.abs(pos.getZ() - source.getZ());
                
                if (dx > 16 || dz > 16 || dy > 32) {
                    // 超出连接范围
                    if (player != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.out_of_range"), true);
                    }
                    return InteractionResult.CONSUME;
                }
                
                // 检查是否已经连接
                if (sourceEntity.getLinks().contains(pos)) {
                    // 已连接，断开连接
                    sourceEntity.removeLink(pos);
                    if (player != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.unlinked", source.getX(), source.getY(), source.getZ(), pos.getX(), pos.getY(), pos.getZ()), true);
                    }
                } else {
                    // 未连接，建立链接：从源到目标的单向连接
                    sourceEntity.addLink(pos);
                    if (player != null) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.linked", source.getX(), source.getY(), source.getZ(), pos.getX(), pos.getY(), pos.getZ()), true);
                    }
                }
                // 注意：不清空TAG_SOURCE，保持源信息，可以继续连接/断开其他目标
                return InteractionResult.CONSUME;
            } else {
                // 源位置不再存在有效的成型器
                tag.remove(TAG_SOURCE);
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.invalid"), true);
                }
                return InteractionResult.CONSUME;
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
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.cleared"), true);
                return InteractionResultHolder.consume(stack);
            }
        }
        return super.use(level, player, hand);
    }
}
