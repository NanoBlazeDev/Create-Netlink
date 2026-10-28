package dev.exavolt.netlink.client.render;

import com.zurrtum.create.client.infrastructure.model.WrapperBlockStateModel;
import dev.exavolt.netlink.block.WallExtenderBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A Wall Extender plate looks like the block it was built into with the extender's slot drawn on top of it, the way
 * Create's copycats borrow another block's look. The plate's own model is only the overlay.
 */
public class WallExtenderPlateModel extends WrapperBlockStateModel {
	public WallExtenderPlateModel(BlockState state, UnbakedRoot unbaked) {
		super(state, unbaked);
	}

	private static BlockStateModel modelOf(BlockState state) {
		return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
	}

	@Override
	public void addPartsWithInfo(BlockAndTintGetter world, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
		if (world.getBlockEntity(pos) instanceof WallExtenderBlockEntity be) {
			BlockState disguise = be.disguise();
			BlockStateModel base = modelOf(disguise);
			if (base instanceof WrapperBlockStateModel wrapper) {
				wrapper.addPartsWithInfo(world, pos, disguise, random, parts);
			} else {
				base.collectParts(random, parts);
			}
		}
		model.collectParts(random, parts);
	}

	@Override
	public boolean needUpdateTerrainParticle() {
		return true;
	}

	@Override
	public Material.Baked particleMaterialWithInfo(BlockAndTintGetter world, BlockPos pos, BlockState state) {
		if (world.getBlockEntity(pos) instanceof WallExtenderBlockEntity be) {
			return modelOf(be.disguise()).particleMaterial();
		}
		return model.particleMaterial();
	}
}
