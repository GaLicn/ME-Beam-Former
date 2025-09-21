package com.mebeamformer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

import com.mebeamformer.blockentity.BeamFormerBlockEntity;
import appeng.block.AEBaseEntityBlock;

public class BeamFormerBlock extends AEBaseEntityBlock<BeamFormerBlockEntity> {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public enum Status implements StringRepresentable {
        OFF("off"), ON("on"), BEAMING("beaming");

        private final String name;
        Status(String name) { this.name = name; }
        @Override
        public String getSerializedName() { return name; }
        @Override
        public String toString() { return name; }
    }
    public static final EnumProperty<Status> STATUS = EnumProperty.create("status", Status.class);

    public BeamFormerBlock(BlockBehaviour.Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.SOUTH)
                .setValue(STATUS, Status.OFF));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STATUS);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BeamFormerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> {
            if (be instanceof BeamFormerBlockEntity bf) {
                if (lvl.isClientSide) {
                    BeamFormerBlockEntity.clientTick(lvl, pos, st, bf);
                } else {
                    BeamFormerBlockEntity.serverTick(lvl, pos, st, bf);
                }
            }
        };
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction face = ctx.getNearestLookingDirection().getOpposite();
        return this.defaultBlockState().setValue(FACING, face).setValue(STATUS, Status.OFF);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    // 禁用模拟器输出信号，避免 AEBaseEntityBlock 在 blockEntityClass 未绑定前触发 NPE
    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return false;
    }
}
