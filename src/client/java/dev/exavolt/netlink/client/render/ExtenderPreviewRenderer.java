package dev.exavolt.netlink.client.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zurrtum.create.client.catnip.gui.render.BlockBakedQuadOutput;
import com.zurrtum.create.client.flywheel.lib.model.baked.ModelConsumer;
import com.zurrtum.create.client.flywheel.lib.model.baked.ModelRenderHelper;
import com.zurrtum.create.client.flywheel.lib.model.baked.SinglePosVirtualBlockGetter;
import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.block.WallExtenderBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/** Renders {@link ExtenderPreviewState}: an orbiting little scene of the plates and the wall sliding out of them. */
public class ExtenderPreviewRenderer extends PictureInPictureRenderer<ExtenderPreviewState> {
	private final SinglePosVirtualBlockGetter world = SinglePosVirtualBlockGetter.createFullBright();

	public ExtenderPreviewRenderer(MultiBufferSource.BufferSource bufferSource) {
		super(bufferSource);
	}

	@Override
	public Class<ExtenderPreviewState> getRenderStateClass() {
		return ExtenderPreviewState.class;
	}

	@Override
	protected float getTranslateY(int height, int guiScale) {
		return height / 2.0F;
	}

	@Override
	protected void renderToTexture(ExtenderPreviewState state, PoseStack poseStack) {
		Minecraft mc = Minecraft.getInstance();
		mc.gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
		poseStack.mulPose(Axis.ZP.rotationDegrees(180));
		poseStack.mulPose(Axis.XP.rotationDegrees(state.pitch()));
		poseStack.mulPose(Axis.YP.rotationDegrees(state.yaw()));

		BlockBakedQuadOutput output = new BlockBakedQuadOutput(bufferSource, poseStack);
		ModelConsumer helper = ModelRenderHelper.getHelper(output);

		Direction up = state.up();
		BlockState plate = NetlinkRegistry.WALL_EXTENDER.defaultBlockState().setValue(WallExtenderBlock.FACING, up);
		for (Map.Entry<BlockPos, BlockState> e : state.plates().entrySet()) {
			BlockPos p = e.getKey();
			float x = (float) (p.getX() - state.cx()), y = (float) (p.getY() - state.cy()), z = (float) (p.getZ() - state.cz());
			if (e.getValue() != null && !e.getValue().isAir()) {
				draw(mc, helper, output, e.getValue(), x, y, z);
			}
			draw(mc, helper, output, plate, x, y, z);
		}
		float sink = state.sink();
		for (Map.Entry<BlockPos, BlockState> e : state.structure().entrySet()) {
			BlockPos p = e.getKey();
			int depth = p.getX() * up.getStepX() + p.getY() * up.getStepY() + p.getZ() * up.getStepZ() - state.plane();
			if (depth - sink <= 0.001F) {
				continue;
			}
			draw(mc, helper, output, e.getValue(),
				(float) (p.getX() - state.cx()) - up.getStepX() * sink,
				(float) (p.getY() - state.cy()) - up.getStepY() * sink,
				(float) (p.getZ() - state.cz()) - up.getStepZ() * sink);
		}
		output.clearBuffer();
	}

	private void draw(Minecraft mc, ModelConsumer helper, BlockBakedQuadOutput output, BlockState state, float x, float y, float z) {
		BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(state);
		world.blockState(state);
		output.updateBuffer(model);
		helper.tesselateBlock(x, y, z, world, BlockPos.ZERO, state, model, 42L);
	}

	@Override
	protected String getTextureLabel() {
		return "netlink wall extender preview";
	}
}
