package dev.exavolt.netlinkdecor.voxel;

import dev.exavolt.netlinkdecor.DecorRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Holds the voxels of a sculpted block. The data object is replaced, never edited in place, so readers stay safe. */
public class VoxelBlockEntity extends BlockEntity {
	private volatile VoxelData data = VoxelData.empty(16);
	private VoxelShape canonicalShape;
	private VoxelShape shape;
	private int shapeKey = -1;
	/** Client mesh cache, filled by the block model. */
	public volatile Object meshCache;
	public volatile Object meshKey;

	public VoxelBlockEntity(BlockPos pos, BlockState state) {
		super(DecorRegistry.VOXEL_BE, pos, state);
	}

	public VoxelData data() {
		return data;
	}

	public void setData(VoxelData data) {
		this.data = data;
		this.canonicalShape = null;
		this.shapeKey = -1;
		setChanged();
		if (level != null && !level.isClientSide()) {
			BlockState state = getBlockState();
			int light = data.lightEmission();
			if (state.hasProperty(VoxelBlock.LIGHT) && state.getValue(VoxelBlock.LIGHT) != light) {
				level.setBlock(worldPosition, state.setValue(VoxelBlock.LIGHT, light), Block.UPDATE_ALL);
			}
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	/** World shape for the current facing and mirror. */
	public VoxelShape shape(BlockState state) {
		int key = VoxelBlock.steps(state) * 2 + (state.getValue(VoxelBlock.MIRRORED) ? 1 : 0);
		if (shape == null || key != shapeKey || canonicalShape == null) {
			if (canonicalShape == null) {
				canonicalShape = data.canonicalShape();
			}
			VoxelShape s = canonicalShape;
			if (state.getValue(VoxelBlock.MIRRORED)) {
				VoxelShape[] out = {Shapes.empty()};
				s.forAllBoxes((x0, y0, z0, x1, y1, z1) -> out[0] = Shapes.or(out[0], Shapes.box(1 - x1, y0, z0, 1 - x0, y1, z1)));
				s = out[0];
			}
			Direction facing = state.getValue(VoxelBlock.FACING);
			shape = dev.exavolt.netlinkdecor.block.PropBlock.rotate(s, facing);
			if (shape.isEmpty()) {
				shape = Shapes.block();
			}
			shapeKey = key;
		}
		return shape;
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		data = input.read("Voxels", VoxelData.CODEC).orElseGet(() -> VoxelData.empty(16));
		canonicalShape = null;
		shapeKey = -1;
		if (level != null && level.isClientSide()) {
			// the voxels are part of the chunk mesh: rebuild it
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 8);
		}
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.store("Voxels", VoxelData.CODEC, data);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}
}
