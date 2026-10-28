package dev.exavolt.netlink.client.render;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import com.zurrtum.create.client.catnip.animation.AnimationTickHolder;
import com.zurrtum.create.client.foundation.model.BakedModelHelper;
import dev.exavolt.netlink.Netlink;
import dev.exavolt.netlink.client.KeyboardClient;
import dev.exavolt.netlink.client.render.KeyboardModel.RenderData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState.FoilType;
import net.minecraft.client.renderer.item.ItemStackRenderState.LayerRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Full-3D Netlink Keyboard: a case plus one baked part per key, so keys physically sink while you hold them.
 * Built the same way as Create's LinkedControllerModel (special item model with animated sub-parts).
 */
public class KeyboardModel implements ItemModel, SpecialModelRenderer<RenderData> {
	public static final Identifier ID = Netlink.id("keyboard");
	public static final Identifier CASE_ID = Netlink.id("item/keyboard/case");
	public static final Identifier CASE_ACTIVE_ID = Netlink.id("item/keyboard/case_active");
	/** How far a fully pressed key travels, in model units (1/16 block). */
	private static final float TRAVEL = 0.45f / 16f;

	private final int[] tints = new int[0];
	private final ModelRenderProperties settings;
	private final Matrix4fc transformation;
	private final Supplier<Vector3fc[]> extents;
	private final List<BakedQuad> caseQuads;
	private final List<BakedQuad> caseActive;
	private final List<List<BakedQuad>> keys;
	private final List<List<BakedQuad>> keysActive;

	public KeyboardModel(ModelRenderProperties settings, Matrix4fc transformation, List<BakedQuad> caseQuads, List<BakedQuad> caseActive,
	                     List<List<BakedQuad>> keys, List<List<BakedQuad>> keysActive) {
		this.settings = settings;
		this.transformation = transformation;
		this.caseQuads = caseQuads;
		this.caseActive = caseActive;
		this.keys = keys;
		this.keysActive = keysActive;
		this.extents = Suppliers.memoize(() -> CuboidItemModelWrapper.computeExtents(caseQuads));
	}

	@Override
	public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext displayContext,
	                   @Nullable ClientLevel world, @Nullable ItemOwner user, int seed) {
		state.appendModelIdentityElement(this);
		state.setAnimated();
		LayerRenderState layer = state.newLayer();
		layer.setExtents(extents);
		layer.setLocalTransform(transformation);
		settings.applyToLayer(layer, displayContext);

		RenderData data = new RenderData();
		data.displayContext = displayContext;
		data.active = KeyboardClient.isActiveFor(stack);
		data.held = KeyboardClient.isHeld(stack);
		layer.setupSpecialModel(this, data);
		state.appendModelIdentityElement(data.active);
	}

	@Override
	public void submit(@Nullable RenderData data, PoseStack matrices, SubmitNodeCollector queue, int light, int overlay, boolean glint, int i) {
		if (data == null) {
			return;
		}
		boolean active = data.active;
		float pt = AnimationTickHolder.getPartialTicks();
		matrices.pushPose();
		boolean firstPerson = data.displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
			|| data.displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
		if (firstPerson && data.held) {
			// lift the keyboard up and toward the middle of the screen, tilted so the keys face you
			float p = KeyboardClient.equipAmount(pt);
			float hand = data.displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ? -1 : 1;
			matrices.translate(-0.30f * p * hand, 0.42f * p, 0.10f * p);
			matrices.translate(0.5f, 0.5f, 0.5f);
			matrices.mulPose(com.mojang.math.Axis.XP.rotationDegrees(22 * p));
			matrices.mulPose(com.mojang.math.Axis.YP.rotationDegrees(10 * p * hand));
			matrices.translate(-0.5f, -0.5f, -0.5f);
		}
		queue.submitItem(matrices, data.displayContext, light, overlay, 0, tints, active ? caseActive : caseQuads, FoilType.NONE);
		List<List<BakedQuad>> parts = active ? keysActive : keys;
		for (int k = 0; k < parts.size(); k++) {
			float press = active ? KeyboardClient.pressAmount(KeyboardLayout.KEYS[k], pt) : 0;
			if (press > 0.001f) {
				matrices.pushPose();
				matrices.translate(0, -TRAVEL * press, 0);
				queue.submitItem(matrices, data.displayContext, light, overlay, 0, tints, parts.get(k), FoilType.NONE);
				matrices.popPose();
			} else {
				queue.submitItem(matrices, data.displayContext, light, overlay, 0, tints, parts.get(k), FoilType.NONE);
			}
		}
		matrices.popPose();
	}

	public static class RenderData {
		ItemDisplayContext displayContext = ItemDisplayContext.NONE;
		boolean active;
		boolean held;
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		throw new UnsupportedOperationException();
	}

	@Override
	public RenderData extractArgument(ItemStack stack) {
		throw new UnsupportedOperationException();
	}

	public static class Unbaked implements ItemModel.Unbaked {
		public static final MapCodec<Unbaked> CODEC = MapCodec.unit(Unbaked::new);

		private static Identifier keyId(String name, boolean active) {
			return Netlink.id("item/keyboard/keys" + (active ? "_active/" : "/") + name);
		}

		@Override
		public MapCodec<Unbaked> type() {
			return CODEC;
		}

		@Override
		public void resolveDependencies(Resolver resolver) {
			resolver.markDependency(CASE_ID);
			resolver.markDependency(CASE_ACTIVE_ID);
			for (String name : KeyboardLayout.NAMES) {
				resolver.markDependency(keyId(name, false));
				resolver.markDependency(keyId(name, true));
			}
		}

		@Override
		public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
			ModelBaker baker = context.blockModelBaker();
			ResolvedModel model = baker.getModel(CASE_ID);
			TextureSlots textures = model.getTopTextureSlots();
			List<BakedQuad> caseQuads = model.bakeTopGeometry(textures, baker, BlockModelRotation.IDENTITY).getAll();
			ModelRenderProperties settings = ModelRenderProperties.fromResolvedModel(baker, model, textures);
			List<List<BakedQuad>> keys = new ArrayList<>();
			List<List<BakedQuad>> keysActive = new ArrayList<>();
			for (String name : KeyboardLayout.NAMES) {
				keys.add(BakedModelHelper.bakeQuads(baker, keyId(name, false)));
				keysActive.add(BakedModelHelper.bakeQuads(baker, keyId(name, true)));
			}
			return new KeyboardModel(settings, transformation, caseQuads, BakedModelHelper.bakeQuads(baker, CASE_ACTIVE_ID), keys, keysActive);
		}
	}
}
