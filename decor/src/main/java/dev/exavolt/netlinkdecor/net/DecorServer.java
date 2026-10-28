package dev.exavolt.netlinkdecor.net;

import dev.exavolt.netlink.network.NetlinkServer;
import dev.exavolt.netlinkdecor.block.LiveBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Optional;

/** Linking live props to Netlink channels. */
public final class DecorServer {
	private DecorServer() {
	}

	public static void openConfig(ServerPlayer player, LiveBlockEntity be) {
		ServerPlayNetworking.send(player, new DecorPackets.OpenPropConfig(be.getBlockPos(), Optional.ofNullable(be.owner()), be.channel(),
			NetlinkServer.networkEntries(player)));
	}

	static void applyConfig(ServerPlayer player, DecorPackets.ApplyPropConfig p) {
		if (!NetlinkServer.requireImplant(player) || player.blockPosition().distSqr(p.pos()) > 64 * 64) {
			return;
		}
		if (!(player.level().getBlockEntity(p.pos()) instanceof LiveBlockEntity be)) {
			return;
		}
		if (!NetlinkServer.canModify(player.getUUID(), be.owner()) || p.owner().isPresent() && !NetlinkServer.canModify(player.getUUID(), p.owner().get())) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.not_authorized", NetlinkServer.ownerName(be.owner()))
				.withStyle(ChatFormatting.RED));
			return;
		}
		String channel = NetlinkServer.sanitizeChannel(p.channel());
		be.configure(channel.isEmpty() ? null : p.owner().orElse(player.getUUID()), channel);
		player.level().playSound(null, p.pos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 1.8F);
	}
}
