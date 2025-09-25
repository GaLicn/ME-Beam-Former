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
    private static final String TAG_FIRST = "FirstPos";

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
        if (!tag.contains(TAG_FIRST)) {
            // 首次绑定点
            CompoundTag t = new CompoundTag();
            t.putInt("x", pos.getX());
            t.putInt("y", pos.getY());
            t.putInt("z", pos.getZ());
            tag.put(TAG_FIRST, t);
            if (player != null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.set", pos.getX(), pos.getY(), pos.getZ()), true);
            }
            return InteractionResult.CONSUME;
        } else {
            CompoundTag t = tag.getCompound(TAG_FIRST);
            BlockPos first = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
            if (first.equals(pos)) {
                // 点到相同方块：清空
                tag.remove(TAG_FIRST);
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.cleared"), true);
                }
                return InteractionResult.CONSUME;
            }
            BlockEntity beFirst = level.getBlockEntity(first);
            if (beFirst instanceof OmniBeamFormerBlockEntity other) {
                // 双向建立/追加链接
                current.addLink(first);
                other.addLink(pos);
                tag.remove(TAG_FIRST);
                if (player != null) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.linked", first.getX(), first.getY(), first.getZ(), pos.getX(), pos.getY(), pos.getZ()), true);
                }
                return InteractionResult.CONSUME;
            } else {
                // 首点不再存在
                tag.remove(TAG_FIRST);
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
            CompoundTag tag = stack.getTag();
            if (tag != null && tag.contains(TAG_FIRST)) {
                tag.remove(TAG_FIRST);
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("tooltip.me_beam_former.binding.cleared"), true);
                return InteractionResultHolder.consume(stack);
            }
        }
        return super.use(level, player, hand);
    }
}
