package dev.exavolt.netlink.client;

import com.zurrtum.create.client.AllSpecialTextures;
import com.zurrtum.create.client.catnip.outliner.Outliner;
import com.zurrtum.create.content.contraptions.glue.SuperGlueEntity;
import dev.exavolt.netlink.item.NetlinkGlueItem;
import dev.exavolt.netlink.net.NetlinkPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Client side of Netlink Glue: two corners anywhere (on blocks or in mid-air, any distance apart), sneak-click a block to
 * auto-glue everything connected to it, sneak-click a glue box in the air to remove it.
 */
public final class GlueClient {
	private GlueClient() {
	}

	private static final int PASSIVE = 0x2C8FB0;
	private static final int HIGHLIGHT = 0x6FE6FF;
	private static final int FAIL = 0xFF8060;
	/** How far in front of the eyes a mid-air corner is placed. */
	private static final double AIR_DISTANCE = 4.0;

	private static final Object SELECTION = new Object();
	private static final Object CURSOR = new Object();

	private static @Nullable BlockPos first;
	private static BlockPos target = BlockPos.ZERO;
	private static boolean targetInAir;
	private static @Nullable SuperGlueEntity hoveredGlue;

	public static void init() {
		NetlinkGlueItem.clientUse = GlueClient::onUse;
	}

	private static boolean holding(LocalPlayer player) {
		return player.getMainHandItem().getItem() instanceof NetlinkGlueItem;
	}

	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		if (!holding(player)) {
			first = null;
			hoveredGlue = null;
			return;
		}

		HitResult hit = mc.hitResult;
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
			target = ((BlockHitResult) hit).getBlockPos();
			targetInAir = false;
		} else {
			target = BlockPos.containing(eye.add(look.scale(AIR_DISTANCE)));
			targetInAir = true;
		}

		// glue boxes nearby, the one under the crosshair highlighted
		Vec3 far = eye.add(look.scale(64));
		hoveredGlue = null;
		double best = Double.MAX_VALUE;
		List<SuperGlueEntity> nearby = mc.level.getEntitiesOfClass(SuperGlueEntity.class, player.getBoundingBox().inflate(96));
		if (first == null) {
			for (SuperGlueEntity glue : nearby) {
				if (glue.getBoundingBox().contains(eye)) {
					// standing inside a glue box: it's the one we point at unless a closer box is in front
					if (best > 0.25) {
						best = 0.25;
						hoveredGlue = glue;
					}
					continue;
				}
				Optional<Vec3> clip = glue.getBoundingBox().clip(eye, far);
				if (clip.isPresent() && clip.get().distanceToSqr(eye) < best) {
					best = clip.get().distanceToSqr(eye);
					hoveredGlue = glue;
				}
			}
		}
		for (SuperGlueEntity glue : nearby) {
			boolean h = glue == hoveredGlue;
			Outliner.getInstance().showAABB(glue, glue.getBoundingBox())
				.colored(h ? FAIL : glue == hoveredGlue ? HIGHLIGHT : PASSIVE)
				.withFaceTextures(h ? AllSpecialTextures.GLUE : null, h ? AllSpecialTextures.GLUE : null)
				.disableLineNormals()
				.lineWidth(glue == hoveredGlue ? 1 / 16f : 1 / 48f);
		}

		Outliner.getInstance().showAABB(CURSOR, new AABB(target).inflate(0.002))
			.colored(targetInAir ? 0xB8F6FF : HIGHLIGHT)
			.disableLineNormals()
			.lineWidth(1 / 32f);

		if (first != null) {
			AABB box = new AABB(Vec3.atLowerCornerOf(first), Vec3.atLowerCornerOf(target)).expandTowards(1, 1, 1);
			boolean cancel = player.isShiftKeyDown();
			Outliner.getInstance().showAABB(SELECTION, box)
				.colored(cancel ? FAIL : HIGHLIGHT)
				.withFaceTextures(AllSpecialTextures.GLUE, AllSpecialTextures.GLUE)
				.disableLineNormals()
				.lineWidth(1 / 16f);
			int sx = (int) box.getXsize(), sy = (int) box.getYsize(), sz = (int) box.getZsize();
			player.sendOverlayMessage(Component.translatable(cancel ? "message.createnetlink.glue_cancel" : "message.createnetlink.glue_second",
				sx + "×" + sy + "×" + sz).withStyle(cancel ? ChatFormatting.GOLD : ChatFormatting.AQUA));
		}
	}

	/** Left-click with Netlink Glue removes the glue box under the crosshair (like Create's Super Glue) instead of mining. */
	public static void startTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.screen != null || !holding(player)) {
			return;
		}
		boolean clicked = false;
		while (mc.options.keyAttack.consumeClick()) {
			clicked = true;
		}
		mc.options.keyAttack.setDown(false);
		if (clicked) {
			if (first != null) {
				first = null;
				player.sendOverlayMessage(Component.translatable("message.createnetlink.glue_abort").withStyle(ChatFormatting.GOLD));
			} else if (hoveredGlue != null) {
				ClientPlayNetworking.send(new NetlinkPackets.GlueRemove(hoveredGlue.getId()));
				hoveredGlue = null;
			}
			player.swing(InteractionHand.MAIN_HAND);
		}
	}

	private static void onUse(InteractionHand hand, @Nullable BlockHitResult hit) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || !holding(player)) {
			return;
		}
		BlockPos pos = hit != null ? hit.getBlockPos() : target;
		boolean sneak = player.isShiftKeyDown();

		if (sneak) {
			if (first != null) {
				first = null;
				player.sendOverlayMessage(Component.translatable("message.createnetlink.glue_abort").withStyle(ChatFormatting.GOLD));
				return;
			}
			if (hit != null) {
				ClientPlayNetworking.send(new NetlinkPackets.GlueAuto(hit.getBlockPos()));
				return;
			}
			if (hoveredGlue != null) {
				ClientPlayNetworking.send(new NetlinkPackets.GlueRemove(hoveredGlue.getId()));
				hoveredGlue = null;
			}
			return;
		}

		if (first == null) {
			first = pos.immutable();
			mc.level.playLocalSound(pos, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.PLAYERS, 0.5F, 0.85F, false);
			player.sendOverlayMessage(Component.translatable("message.createnetlink.glue_first").withStyle(ChatFormatting.AQUA));
			return;
		}
		ClientPlayNetworking.send(new NetlinkPackets.GlueApply(first, pos.immutable()));
		mc.level.playLocalSound(pos, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.PLAYERS, 0.5F, 1.1F, false);
		first = null;
	}
}
