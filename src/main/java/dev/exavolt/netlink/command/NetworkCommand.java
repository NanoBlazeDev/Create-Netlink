package dev.exavolt.netlink.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.exavolt.netlink.network.NetlinkServer;
import dev.exavolt.netlink.network.NetworkSavedData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.Collection;
import java.util.UUID;

/** /network — open the network screen, and manage who may use your network. Available to every player. */
public final class NetworkCommand {
	private NetworkCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> build(dispatcher));
	}

	private static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("network")
			.executes(NetworkCommand::open)
			.then(Commands.literal("authorize")
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
					.executes(ctx -> authorize(ctx, true))))
			.then(Commands.literal("revoke")
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
					.executes(ctx -> authorize(ctx, false))))
			.then(Commands.literal("list")
				.executes(NetworkCommand::list)));
	}

	private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!NetlinkServer.hasImplant(player)) {
			ctx.getSource().sendFailure(Component.translatable("message.createnetlink.no_terminal"));
			return 0;
		}
		NetlinkServer.sendMap(player, true);
		return 1;
	}

	private static int authorize(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Collection<NameAndId> targets = GameProfileArgument.getGameProfiles(ctx, "player");
		int changed = 0;
		for (NameAndId target : targets) {
			if (target.id().equals(player.getUUID())) {
				ctx.getSource().sendFailure(Component.translatable("command.createnetlink.self"));
				continue;
			}
			boolean ok = add ? NetlinkServer.authorize(player, target.id(), target.name()) : NetlinkServer.revoke(player, target.id());
			String key = add ? (ok ? "command.createnetlink.authorized" : "command.createnetlink.already_authorized")
				: (ok ? "command.createnetlink.revoked" : "command.createnetlink.not_listed");
			Component name = Component.literal(target.name()).withStyle(ChatFormatting.AQUA);
			ctx.getSource().sendSuccess(() -> Component.translatable(key, name).withStyle(ChatFormatting.GRAY), false);
			if (ok) {
				changed++;
				ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(target.id());
				if (online != null) {
					online.sendSystemMessage(Component.translatable(add ? "command.createnetlink.you_were_authorized" : "command.createnetlink.you_were_revoked",
						Component.literal(player.getName().getString()).withStyle(ChatFormatting.AQUA)).withStyle(ChatFormatting.GRAY));
				}
			}
		}
		return changed;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		NetworkSavedData data = NetlinkServer.data();
		if (data == null) {
			return 0;
		}
		NetworkSavedData.NetworkInfo own = data.network(player.getUUID(), player.getName().getString());
		MutableComponent mine = Component.translatable("command.createnetlink.list.authorized").withStyle(ChatFormatting.GRAY);
		if (own.authorized.isEmpty()) {
			mine.append(Component.translatable("command.createnetlink.list.none").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			mine.append(Component.literal(String.join(", ", own.authorized.values())).withStyle(ChatFormatting.AQUA));
		}
		ctx.getSource().sendSuccess(() -> mine, false);

		MutableComponent access = Component.translatable("command.createnetlink.list.access").withStyle(ChatFormatting.GRAY);
		boolean any = false;
		for (UUID owner : NetlinkServer.accessibleOwners(player)) {
			if (owner.equals(player.getUUID())) {
				continue;
			}
			access.append(Component.literal((any ? ", " : "") + NetlinkServer.ownerName(owner)).withStyle(ChatFormatting.AQUA));
			any = true;
		}
		if (!any) {
			access.append(Component.translatable("command.createnetlink.list.none").withStyle(ChatFormatting.DARK_GRAY));
		}
		ctx.getSource().sendSuccess(() -> access, false);
		return 1;
	}
}
