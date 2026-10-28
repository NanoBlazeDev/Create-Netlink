package dev.exavolt.netlinkdecor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A square cable duct; place it along any axis like a log. */
public class CableDuctBlock extends RotatedPillarBlock {
	private static final VoxelShape X = Block.box(0, 3, 3, 16, 13, 13);
	private static final VoxelShape Y = Block.box(3, 0, 3, 13, 16, 13);
	private static final VoxelShape Z = Block.box(3, 3, 0, 13, 13, 16);

	public CableDuctBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (state.getValue(AXIS)) {
			case X -> X;
			case Z -> Z;
			default -> Y;
		};
	}
}
