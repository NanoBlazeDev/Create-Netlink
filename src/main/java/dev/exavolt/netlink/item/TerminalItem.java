package dev.exavolt.netlink.item;

import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.network.NetlinkServer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/** Right-click to implant. The item is used up but the modification stays on the player for good. */
public class TerminalItem extends Item {
	public TerminalItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayer sp)) {
			return InteractionResult.PASS;
		}
		if (NetlinkServer.hasImplant(sp)) {
			sp.sendOverlayMessage(Component.translatable("message.createnetlink.already_implanted").withStyle(ChatFormatting.GOLD));
			return InteractionResult.FAIL;
		}
		sp.setAttached(NetlinkRegistry.IMPLANT, true);
		NetlinkServer.touchNetwork(sp);
		stack.consume(1, sp);
		level.playSound(null, sp.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.6F);
		level.playSound(null, sp.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 0.7F);
		sp.sendSystemMessage(Component.translatable("message.createnetlink.implanted",
			Component.keybind("key.createnetlink.hud").withStyle(ChatFormatting.AQUA)).withStyle(ChatFormatting.GRAY));
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("tooltip.createnetlink.terminal.1").withStyle(ChatFormatting.GRAY));
		builder.accept(Component.translatable("tooltip.createnetlink.terminal.2",
			Component.keybind("key.createnetlink.hud")).withStyle(ChatFormatting.DARK_AQUA));
	}
}
