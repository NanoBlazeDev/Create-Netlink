package dev.exavolt.netlinkdecor.client.render;

import com.zurrtum.create.client.infrastructure.model.WrapperBlockStateModel;
import dev.exavolt.netlinkdecor.voxel.VoxelBlock;
import dev.exavolt.netlinkdecor.voxel.VoxelBlockEntity;
import dev.exavolt.netlinkdecor.voxel.VoxelData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Chunk model of a sculpted block: the voxel mesh of its block entity, rebuilt only when the voxels or the facing change. */
public class VoxelBlockModel extends WrapperBlockStateModel {
	public VoxelBlockModel(BlockState state, UnbakedRoot unbaked) {
		super(state, unbaked);
	}

	private record Key(VoxelData data, int steps, boolean mirrored) {
		@Override
		public boolean equals(Object o) {
			return o instanceof Key k && k.data == data && k.steps == steps && k.mirrored == mirrored;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(data) * 31 + steps * 2 + (mirrored ? 1 : 0);
		}
	}

	public static VoxelMesh meshOf(VoxelBlockEntity be, BlockState state) {
		VoxelData data = be.data();
		Key key = new Key(data, VoxelBlock.steps(state), state.getValue(VoxelBlock.MIRRORED));
		if (key.equals(be.meshKey) && be.meshCache instanceof VoxelMesh mesh) {
			return mesh;
		}
		VoxelMesh mesh = VoxelMesh.build(data, key.steps(), key.mirrored());
		be.meshCache = mesh;
		be.meshKey = key;
		return mesh;
	}

	@Override
	public void addPartsWithInfo(BlockAndTintGetter world, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
		if (world.getBlockEntity(pos) instanceof VoxelBlockEntity be && state.getBlock() instanceof VoxelBlock) {
			parts.add(meshOf(be, state));
		}
	}

	@Override
	public boolean needUpdateTerrainParticle() {
		return true;
	}

	@Override
	public Material.Baked particleMaterialWithInfo(BlockAndTintGetter world, BlockPos pos, BlockState state) {
		if (world.getBlockEntity(pos) instanceof VoxelBlockEntity be && state.getBlock() instanceof VoxelBlock) {
			return meshOf(be, state).particleMaterial();
		}
		return model.particleMaterial();
	}
}
