package dev.exavolt.netlink.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.exavolt.netlink.block.ExtenderData;
import dev.exavolt.netlink.block.WallExtenderBlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a Wall Extender's wall while it slides in or out of the plates. At rest the wall is made of real blocks and
 * this renders nothing; while moving, the world holds invisible ghost blocks and every part is drawn here at its
 * interpolated position, the way Create draws contraptions.
 */
public class WallExtenderRenderer implements BlockEntityRenderer<WallExtenderBlockEntity, WallExtenderRenderer.State> {
	public static class State extends BlockEntityRenderState {
		final List<MovingBlockRenderState> blocks = new ArrayList<>();
		final List<double[]> offsets = new ArrayList<>();
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(WallExtenderBlockEntity be, State state, float partialTicks, Vec3 cameraPosition,
	                               ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, cameraPosition, breakProgress);
		state.blocks.clear();
		state.offsets.clear();
		ExtenderData d = be.data();
		if (d == null || !(be.getLevel() instanceof ClientLevel level) || (d.moveDir == 0 && be.linger() <= 0)) {
			return;
		}
		BlockPos master = be.getBlockPos();
		float f = d.moveDir == 0 ? d.extension : d.frac(partialTicks);
		int restSink = d.hmax - d.extension;
		Holder<Biome> biome = level.getBiome(master);
		for (ExtenderData.Part p : d.structure) {
			if (d.height(p, f) <= 0) {
				continue;
			}
			if (d.moveDir == 0) {
				// lingering after the stop: only fill in cells whose real block hasn't arrived yet
				BlockPos at = master.offset(p.rel()).relative(d.up, -restSink);
				if (level.getBlockState(at) == p.state()) {
					continue;
				}
			}
			double[] o = d.offset(p, f);
			BlockPos cell = BlockPos.containing(master.getX() + o[0] + 0.5, master.getY() + o[1] + 0.5, master.getZ() + o[2] + 0.5);
			MovingBlockRenderState m = new MovingBlockRenderState();
			m.randomSeedPos = master.offset(p.rel());
			m.blockPos = cell;
			m.blockState = p.state();
			m.biome = biome;
			m.cardinalLighting = level.cardinalLighting();
			m.lightEngine = level.getLightEngine();
			state.blocks.add(m);
			state.offsets.add(o);
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		for (int i = 0; i < state.blocks.size(); i++) {
			double[] o = state.offsets.get(i);
			poseStack.pushPose();
			poseStack.translate(o[0], o[1], o[2]);
			collector.submitMovingBlock(poseStack, state.blocks.get(i));
			poseStack.popPose();
		}
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 256;
	}

	@Override
	public boolean shouldRender(WallExtenderBlockEntity be, Vec3 cameraPosition) {
		return be.isMaster() && BlockEntityRenderer.super.shouldRender(be, cameraPosition);
	}
}
