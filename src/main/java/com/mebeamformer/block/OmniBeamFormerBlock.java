package com.mebeamformer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import com.mebeamformer.blockentity.OmniBeamFormerBlockEntity;
import appeng.block.AEBaseEntityBlock;

public class OmniBeamFormerBlock extends AEBaseEntityBlock<OmniBeamFormerBlockEntity> {
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

    public OmniBeamFormerBlock(BlockBehaviour.Properties props) {
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
        return new OmniBeamFormerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (lvl, pos, st, be) -> {
            if (be instanceof OmniBeamFormerBlockEntity bf) {
                if (lvl.isClientSide) {
                    OmniBeamFormerBlockEntity.clientTick(lvl, pos, st, bf);
                } else {
                    OmniBeamFormerBlockEntity.serverTick(lvl, pos, st, bf);
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

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) { return false; }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) { return true; }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) { return Shapes.empty(); }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }

    private static final VoxelShape SELECTION = Block.box(2, 2, 8, 14, 14, 16);

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SELECTION; }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SELECTION; }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) { return 0; }
}
