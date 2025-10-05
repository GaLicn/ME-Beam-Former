package com.mebeamformer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;

import com.mebeamformer.blockentity.BeamFormerBlockEntity;
import appeng.block.AEBaseEntityBlock;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.entity.LivingEntity;

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

    // 非完整方块的可视/遮挡与采光修正，避免看穿或相邻面错误被裁剪
    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        // 返回空遮挡形状，配合 noOcclusion 防止错误遮挡与邻面裁剪
        return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        // 允许天空光穿过非完整几何，避免出现阴影块的视觉瑕疵
        return true;
    }

    // 为每个朝向缓存VoxelShape
    private static final Map<Direction, VoxelShape> SHAPES;
    
    static {
        SHAPES = new EnumMap<>(Direction.class);
        
        // 为每个朝向创建对应的形状
        for (Direction facing : Direction.values()) {
            VoxelShape shape;
            switch (facing) {
                case NORTH:
                    shape = Block.box(2, 2, 0, 14, 14, 8);
                    break;
                case SOUTH:
                    shape = Block.box(2, 2, 8, 14, 14, 16);
                    break;
                case WEST:
                    shape = Block.box(0, 2, 2, 8, 14, 14);
                    break;
                case EAST:
                    shape = Block.box(8, 2, 2, 16, 14, 14);
                    break;
                case UP:
                    shape = Block.box(2, 8, 2, 14, 16, 14);
                    break;
                case DOWN:
                    shape = Block.box(2, 0, 2, 14, 8, 14);
                    break;
                default:
                    shape = Block.box(2, 2, 8, 14, 14, 16); // 默认南朝向
                    break;
            }
            SHAPES.put(facing, shape);
        }
    }

    // 可选：提供更贴近模型的大致选择框，避免完全方块造成的误选
    // private static final VoxelShape SELECTION = Block.box(2, 2, 8, 14, 14, 16); // 以南朝向为基准的主体

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // 根据朝向返回对应的形状
        Direction facing = state.getValue(FACING);
        return SHAPES.get(facing);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // 碰撞体积与选择框一致，避免"看得见/撞不到"或"撞到隐形盒"的违和
        Direction facing = state.getValue(FACING);
        return SHAPES.get(facing);
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        // 不阻挡环境光，避免在下方形成不合理的阴影
        return 0;
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        // 方块发光亮度设置为15（最大亮度）
        return 15;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack heldItem, BlockState state, Level level, BlockPos pos, Player player,
                                InteractionHand hand, BlockHitResult hit) {
        // Shift 右键切换光束可见性
        if (player.isShiftKeyDown()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof BeamFormerBlockEntity bf) {
                if (!level.isClientSide) {
                    bf.toggleBeamVisibility();
                }
                return ItemInteractionResult.SUCCESS;
            }
        }
        
        // 调用父类的默认处理（如内存卡等）
        return super.useItemOn(heldItem, state, level, pos, player, hand, hit);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // 通知相邻方块，让线缆可以连接
        if (!level.isClientSide()) {
            level.blockUpdated(pos, this);
            for (Direction dir : Direction.values()) {
                level.updateNeighborsAt(pos.relative(dir), this);
            }
        }
    }
}
