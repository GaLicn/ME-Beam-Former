package com.mebeamformer.block;

import com.mebeamformer.blockentity.WirelessEnergyTowerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.BlockGetter;
import org.jetbrains.annotations.Nullable;

public class WirelessEnergyTowerBlock extends Block implements EntityBlock {
    
    // 使用IntegerProperty表示塔的部分：0=底部，1=中部，2=顶部
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 2);
    
    public WirelessEnergyTowerBlock(BlockBehaviour.Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any().setValue(PART, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
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

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        // 不阻挡环境光，避免在下方形成不合理的阴影
        return 0;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        
        // 检查上方两格是否可以放置
        if (pos.getY() < level.getMaxBuildHeight() - 2 && 
            level.getBlockState(pos.above()).canBeReplaced(context) &&
            level.getBlockState(pos.above(2)).canBeReplaced(context)) {
            return this.defaultBlockState().setValue(PART, 0);
        }
        
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        // 放置中部和顶部
        level.setBlock(pos.above(), state.setValue(PART, 1), 3);
        level.setBlock(pos.above(2), state.setValue(PART, 2), 3);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            // 移除其他部分
            int part = state.getValue(PART);
            if (part == 0) {
                // 底部被破坏，移除中部和顶部
                if (level.getBlockState(pos.above()).is(this)) {
                    level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 35);
                }
                if (level.getBlockState(pos.above(2)).is(this)) {
                    level.setBlock(pos.above(2), Blocks.AIR.defaultBlockState(), 35);
                }
            } else if (part == 1) {
                // 中部被破坏，移除底部和顶部
                if (level.getBlockState(pos.below()).is(this)) {
                    level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 35);
                }
                if (level.getBlockState(pos.above()).is(this)) {
                    level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 35);
                }
            } else if (part == 2) {
                // 顶部被破坏，移除底部和中部
                if (level.getBlockState(pos.below()).is(this)) {
                    level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 35);
                }
                if (level.getBlockState(pos.below(2)).is(this)) {
                    level.setBlock(pos.below(2), Blocks.AIR.defaultBlockState(), 35);
                }
            }
            
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos currentPos, BlockPos neighborPos) {
        int part = state.getValue(PART);
        
        // 检查结构完整性
        if (direction.getAxis() == Direction.Axis.Y) {
            if (part == 0 && direction == Direction.UP) {
                // 底部检查上方
                if (!neighborState.is(this) || neighborState.getValue(PART) != 1) {
                    return Blocks.AIR.defaultBlockState();
                }
            } else if (part == 1) {
                // 中部检查上下
                if (direction == Direction.DOWN && (!neighborState.is(this) || neighborState.getValue(PART) != 0)) {
                    return Blocks.AIR.defaultBlockState();
                }
                if (direction == Direction.UP && (!neighborState.is(this) || neighborState.getValue(PART) != 2)) {
                    return Blocks.AIR.defaultBlockState();
                }
            } else if (part == 2 && direction == Direction.DOWN) {
                // 顶部检查下方
                if (!neighborState.is(this) || neighborState.getValue(PART) != 1) {
                    return Blocks.AIR.defaultBlockState();
                }
            }
        }
        
        return super.updateShape(state, direction, neighborState, level, currentPos, neighborPos);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // 只在底部创建BlockEntity
        if (state.getValue(PART) == 0) {
        return new WirelessEnergyTowerBlockEntity(pos, state);
        }
        return null;
    }

    // ticker 由 WirelessEnergyNetwork 统一处理
    
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // 不再需要 ticker - 全局管理器会处理所有能量传输
        return null;
    }
} 