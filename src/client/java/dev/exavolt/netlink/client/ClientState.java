package dev.exavolt.netlink.client;

import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.net.NetlinkPackets.HudDevice;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import dev.exavolt.netlink.block.WallExtenderBlock;
import dev.exavolt.netlink.block.WallExtenderBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/** Client-side view of the HUD: whether vision is on and the devices the server reported around the player. */
public final class ClientState {
	private ClientState() {
	}

	public static boolean hudOn;
	public static List<HudDevice> devices = List.of();
	private static Map<Long, HudDevice> bugs = Map.of();
	private static Map<Long, HudDevice> links = Map.of();
	public static long hudSince;

	public static boolean implanted() {
		Player player = Minecraft.getInstance().player;
		return player != null && player.getAttachedOrElse(NetlinkRegistry.IMPLANT, false);
	}

	public static void setDevices(List<HudDevice> list) {
		devices = list;
		plateCache.clear();
		Map<Long, HudDevice> b = new HashMap<>();
		Map<Long, HudDevice> l = new HashMap<>();
		for (HudDevice d : list) {
			(d.kind() == 1 ? b : l).put(d.pos().asLong(), d);
		}
		bugs = b;
		links = l;
	}

	public static boolean bugAt(BlockPos pos) {
		return bugs.containsKey(pos.asLong());
	}

	public static @Nullable HudDevice deviceAt(BlockPos pos) {
		HudDevice d = bugs.get(pos.asLong());
		if (d == null) {
			d = links.get(pos.asLong());
		}
		if (d == null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null && mc.level.getBlockEntity(pos) instanceof WallExtenderBlockEntity be) {
				d = links.get(be.masterPos().asLong());
			}
		}
		return d;
	}

	private static final Map<Long, List<BlockPos>> plateCache = new HashMap<>();

	/** Plates of a Wall Extender as seen in the client world: every connected plate with the same facing. */
	public static List<BlockPos> platesOf(HudDevice d) {
		return plateCache.computeIfAbsent(d.pos().asLong(), k -> {
			Minecraft mc = Minecraft.getInstance();
			List<BlockPos> out = new ArrayList<>();
			if (mc.level == null) {
				return out;
			}
			BlockState start = mc.level.getBlockState(d.pos());
			if (!(start.getBlock() instanceof WallExtenderBlock)) {
				out.add(d.pos());
				return out;
			}
			Set<BlockPos> seen = new HashSet<>();
			ArrayDeque<BlockPos> q = new ArrayDeque<>();
			seen.add(d.pos());
			q.add(d.pos());
			while (!q.isEmpty() && out.size() < 4096) {
				BlockPos cur = q.poll();
				out.add(cur);
				for (Direction dir : Direction.values()) {
					BlockPos n = cur.relative(dir);
					if (seen.add(n) && mc.level.getBlockState(n).getBlock() instanceof WallExtenderBlock
						&& mc.level.getBlockEntity(n) instanceof WallExtenderBlockEntity be && be.masterPos().equals(d.pos())) {
						q.add(n);
					}
				}
			}
			return out;
		});
	}

	public static void reset() {
		hudOn = false;
		setDevices(List.of());
	}
}
