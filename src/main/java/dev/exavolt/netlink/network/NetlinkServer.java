package dev.exavolt.netlink.network;

import dev.exavolt.netlink.Netlink;
import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.block.AdvancedRedstoneLinkBlock;
import dev.exavolt.netlink.block.AdvancedRedstoneLinkBlockEntity;
import dev.exavolt.netlink.item.KeyboardItem;
import dev.exavolt.netlink.item.KeyboardConfig;
import dev.exavolt.netlink.net.NetlinkPackets;
import dev.exavolt.netlink.net.NetlinkPackets.HudDevice;
import dev.exavolt.netlink.net.NetlinkPackets.MapChannel;
import dev.exavolt.netlink.net.NetlinkPackets.MapDevice;
import dev.exavolt.netlink.net.NetlinkPackets.MapNetwork;
import dev.exavolt.netlink.net.NetlinkPackets.NetEntry;
import dev.exavolt.netlink.network.NetworkSavedData.NetworkInfo;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Server side of every network: channel values, device registry, permissions, bugs, HUD sync and controllers.
 * A channel's value is the strongest of: POST devices on it, controller keys held on it, a pulse, and a value set
 * by hand from the network screen.
 */
public final class NetlinkServer {
	private NetlinkServer() {
	}

	public static final int HUD_RADIUS = 64;

	private static @Nullable MinecraftServer server;
	private static @Nullable NetworkSavedData data;

	private static final Map<String, Set<String>> postIndex = new HashMap<>();
	private static final Map<String, Set<String>> listenIndex = new HashMap<>();
	private static final Set<String> bugKeys = new LinkedHashSet<>();
	private static final Map<String, Integer> channelCache = new HashMap<>();
	private static final Map<String, Map<UUID, Integer>> controllerSources = new HashMap<>();
	private static final Map<String, int[]> pulses = new HashMap<>();
	/** Pulse trains started by TOGGLE_PULSE binds: channel key -> [period, phase]. */
	private static final Map<String, int[]> clocks = new HashMap<>();
	/** Non-hold keyboard keys currently held, for press-edge detection: player -> key -> [timer]. */
	private static final Map<UUID, Map<Integer, int[]>> heldKeys = new HashMap<>();
	private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> bugPower = new HashMap<>();
	private static volatile boolean anyBugPower;
	private static final Set<UUID> hudPlayers = new HashSet<>();
	/** Clone of Create's ManualFrequencyEntry list: player -> channel key -> [timer, owner hash]. */
	private static final Map<UUID, Map<String, int[]>> controllers = new HashMap<>();
	private static final int CONTROLLER_TIMEOUT = 30;
	private static int ticks;

	/** Set by the client entrypoint so the shared use-block hook can see client-only state. */
	public static Predicate<BlockPos> clientBugAt = p -> false;
	public static BooleanSupplier clientHudOn = () -> false;

	// =================================================================== lifecycle

	public static void registerEvents() {
		ServerLifecycleEvents.SERVER_STARTED.register(NetlinkServer::onStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> onStopped());
		ServerTickEvents.END_SERVER_TICK.register(s -> tick());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> onChunkLoad(level, chunk));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> onLeave(handler.player));
		UseBlockCallback.EVENT.register(NetlinkServer::onUseBlock);
		WallExtenders.registerEvents();
		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> dev.exavolt.netlink.script.ScriptEngine.fireWorld("PlayerJoined",
			org.luaj.vm2.LuaValue.valueOf(handler.player.getName().getString())));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> dev.exavolt.netlink.script.ScriptEngine.fireWorld("PlayerLeft",
			org.luaj.vm2.LuaValue.valueOf(handler.player.getName().getString())));
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
			dev.exavolt.netlink.script.ScriptEngine.fireWorld("Chatted", org.luaj.vm2.LuaValue.varargsOf(
				org.luaj.vm2.LuaValue.valueOf(sender.getName().getString()), org.luaj.vm2.LuaValue.valueOf(message.signedContent()))));
	}

	/** Ticks since the server started; devices wait {@link #STARTUP_TICKS} before syncing so scripts can set their signals first. */
	private static int uptime;
	private static final int STARTUP_TICKS = 40;
	private static final Set<String> bugRefresh = new HashSet<>();

	/** Whether devices may sync with their channels (the server runs and scripts had time to restore their signals). */
	public static boolean ready() {
		return running() && uptime >= STARTUP_TICKS;
	}

	private static void onChunkLoad(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
		if (data == null || bugKeys.isEmpty()) {
			return;
		}
		for (String key : bugKeys) {
			DeviceRecord d = data.devices.get(key);
			if (d != null && d.listen && d.dimension.equals(level.dimension()) && (d.pos.getX() >> 4) == chunk.getPos().x()
				&& (d.pos.getZ() >> 4) == chunk.getPos().z()) {
				bugRefresh.add(key);
			}
		}
	}

	/** Listening bugs in freshly loaded chunks: let the block see the channel's current value. */
	private static void refreshBugs() {
		if (bugRefresh.isEmpty() || !ready()) {
			return;
		}
		for (String key : List.copyOf(bugRefresh)) {
			DeviceRecord d = data.devices.get(key);
			ServerLevel level = d == null ? null : server.getLevel(d.dimension);
			if (d != null && d.listen && level != null && level.isLoaded(d.pos)) {
				setBugPower(d.dimension, d.pos, channelValue(d.owner, d.channel));
				level.neighborChanged(d.pos, Blocks.AIR, null);
			}
		}
		bugRefresh.clear();
	}

	private static void onStarted(MinecraftServer s) {
		server = s;
		data = NetworkSavedData.get(s);
		uptime = 0;
		bugRefresh.clear();
		rebuild();
		dev.exavolt.netlink.script.ScriptEngine.onServerStarted(data);
	}

	private static void onStopped() {
		dev.exavolt.netlink.script.ScriptEngine.stopAll();
		Elevators.reset();
		extraSources.clear();
		deferred.clear();
		server = null;
		data = null;
		postIndex.clear();
		listenIndex.clear();
		bugKeys.clear();
		channelCache.clear();
		controllerSources.clear();
		pulses.clear();
		clocks.clear();
		heldKeys.clear();
		bugPower.clear();
		anyBugPower = false;
		hudPlayers.clear();
		controllers.clear();
		bugRefresh.clear();
	}

	private static void onLeave(ServerPlayer player) {
		hudPlayers.remove(player.getUUID());
		heldKeys.remove(player.getUUID());
		releaseController(player.getUUID());
	}

	private static void rebuild() {
		postIndex.clear();
		listenIndex.clear();
		bugKeys.clear();
		channelCache.clear();
		bugPower.clear();
		anyBugPower = false;
		if (data == null) {
			return;
		}
		for (DeviceRecord d : data.devices.values()) {
			index(d);
			if (d.kind == DeviceRecord.Kind.BUG && d.listen) {
				bugRefresh.add(d.key());
			}
		}
		clocks.clear();
		data.clocks.forEach((key, period) -> clocks.put(key, new int[]{Math.max(1, period), 0}));
		Set<String> keys = new HashSet<>(postIndex.keySet());
		keys.addAll(listenIndex.keySet());
		keys.addAll(data.manual.keySet());
		keys.addAll(clocks.keySet());
		for (String key : keys) {
			int v = computeChannel(key);
			channelCache.put(key, v);
			for (String id : listenIndex.getOrDefault(key, Set.of())) {
				DeviceRecord d = data.devices.get(id);
				if (d != null && d.kind == DeviceRecord.Kind.BUG) {
					setBugPower(d.dimension, d.pos, v);
				}
			}
		}
	}

	public static boolean running() {
		return server != null && data != null;
	}

	public static @Nullable NetworkSavedData data() {
		return data;
	}

	// =================================================================== channel logic

	private static void index(DeviceRecord d) {
		if (d.kind == DeviceRecord.Kind.BUG) {
			bugKeys.add(d.key());
		}
		if (!d.active() || d.kind == DeviceRecord.Kind.ELEVATOR) {
			return;
		}
		(d.listen ? listenIndex : postIndex).computeIfAbsent(d.channelKey(), k -> new LinkedHashSet<>()).add(d.key());
	}

	private static void unindex(DeviceRecord d) {
		bugKeys.remove(d.key());
		if (!d.active() || d.kind == DeviceRecord.Kind.ELEVATOR) {
			return;
		}
		Map<String, Set<String>> idx = d.listen ? listenIndex : postIndex;
		Set<String> set = idx.get(d.channelKey());
		if (set != null) {
			set.remove(d.key());
			if (set.isEmpty()) {
				idx.remove(d.channelKey());
			}
		}
	}

	private static int computeChannel(String key) {
		if (data == null) {
			return 0;
		}
		int v = Math.max(0, data.manual.getOrDefault(key, 0));
		for (String id : postIndex.getOrDefault(key, Set.of())) {
			DeviceRecord d = data.devices.get(id);
			if (d != null) {
				v = Math.max(v, d.value);
			}
		}
		Map<UUID, Integer> ctl = controllerSources.get(key);
		if (ctl != null) {
			for (int x : ctl.values()) {
				v = Math.max(v, x);
			}
		}
		int[] pulse = pulses.get(key);
		if (pulse != null) {
			v = Math.max(v, pulse[1]);
		}
		Map<String, Integer> extra = extraSources.get(key);
		if (extra != null) {
			for (int x : extra.values()) {
				v = Math.max(v, x);
			}
		}
		int[] clock = clocks.get(key);
		if (clock != null && (clock[1] / Math.max(1, clock[0])) % 2 == 0) {
			v = 15;
		}
		return Math.min(15, v);
	}

	// =================================================================== scripts & elevators as signal sources

	/** Extra sources by channel key, then by source id ("script:&lt;id&gt;", "elevator:&lt;key&gt;"). */
	private static final Map<String, Map<String, Integer>> extraSources = new HashMap<>();
	private static final List<Runnable> deferred = new ArrayList<>();

	/** Runs work queued by scripts (which can't touch the world themselves). */
	public static void runDeferred() {
		while (!deferred.isEmpty()) {
			List<Runnable> jobs = new ArrayList<>(deferred);
			deferred.clear();
			for (Runnable r : jobs) {
				try {
					r.run();
				} catch (RuntimeException e) {
					Netlink.LOGGER.warn("Deferred netlink job failed", e);
				}
			}
		}
	}

	public static void later(Runnable r) {
		deferred.add(r);
	}

	/** Current value of a channel computed from data only (safe off the server thread while it waits). */
	public static int peekChannel(UUID owner, String channel) {
		if (data == null || channel.isEmpty()) {
			return 0;
		}
		return computeChannel(NetworkSavedData.channelKey(owner, channel));
	}

	public static void setExtraSource(UUID owner, String channel, String source, int value) {
		String key = NetworkSavedData.channelKey(owner, channel);
		Map<String, Integer> m = extraSources.computeIfAbsent(key, k -> new HashMap<>());
		Integer old = value > 0 ? m.put(source, value) : m.remove(source);
		if (m.isEmpty()) {
			extraSources.remove(key);
		}
		if (old == null ? value > 0 : old != value) {
			later(() -> refreshChannel(key));
		}
	}

	public static void setScriptSource(UUID owner, String channel, String source, int value) {
		if (data != null) {
			data.network(owner, null);
		}
		setExtraSource(owner, channel, source, value);
	}

	public static void scriptPulse(UUID owner, String channel, int ticks, int value) {
		String key = NetworkSavedData.channelKey(owner, channel);
		pulses.put(key, new int[]{ticks, value});
		later(() -> refreshChannel(key));
	}

	/** Drops every value a source put on any channel. */
	public static void clearSources(String source) {
		List<String> touched = new ArrayList<>();
		for (Iterator<Map.Entry<String, Map<String, Integer>>> it = extraSources.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, Map<String, Integer>> e = it.next();
			if (e.getValue().remove(source) != null) {
				touched.add(e.getKey());
			}
			if (e.getValue().isEmpty()) {
				it.remove();
			}
		}
		touched.forEach(NetlinkServer::refreshChannel);
	}

	public static @Nullable MinecraftServer server() {
		return server;
	}

	public static int channelValue(@Nullable UUID owner, String channel) {
		if (owner == null || channel.isEmpty()) {
			return 0;
		}
		String key = NetworkSavedData.channelKey(owner, channel);
		Integer cached = channelCache.get(key);
		return cached != null ? cached : computeChannel(key);
	}

	private static void refreshChannel(String key) {
		int v = computeChannel(key);
		Integer old = channelCache.put(key, v);
		if (old == null || old != v) {
			int bar = key.indexOf('|');
			if (bar > 0) {
				try {
					dev.exavolt.netlink.script.ScriptEngine.channelChanged(UUID.fromString(key.substring(0, bar)), key.substring(bar + 1), v,
						old == null ? 0 : old);
				} catch (IllegalArgumentException ignored) {
				}
			}
			for (String id : List.copyOf(listenIndex.getOrDefault(key, Set.of()))) {
				DeviceRecord d = data == null ? null : data.devices.get(id);
				if (d != null) {
					push(d, v);
				}
			}
		}
	}

	private static void push(DeviceRecord d, int v) {
		if (server == null) {
			return;
		}
		ServerLevel level = server.getLevel(d.dimension);
		if (d.kind == DeviceRecord.Kind.BUG) {
			setBugPower(d.dimension, d.pos, v);
			if (level != null && level.isLoaded(d.pos)) {
				level.neighborChanged(d.pos, Blocks.AIR, null);
			}
		} else if (level != null && level.isLoaded(d.pos)) {
			BlockEntity be = level.getBlockEntity(d.pos);
			if (be instanceof AdvancedRedstoneLinkBlockEntity link) {
				link.setReceived(v);
			} else if (be instanceof dev.exavolt.netlink.block.WallExtenderBlockEntity ext && ext.isMaster()) {
				ext.setReceived(v);
			}
		}
	}

	private static void setBugPower(ResourceKey<Level> dim, BlockPos pos, int v) {
		Long2IntOpenHashMap map = bugPower.computeIfAbsent(dim, k -> new Long2IntOpenHashMap());
		if (v > 0) {
			map.put(pos.asLong(), v);
		} else {
			map.remove(pos.asLong());
		}
		boolean any = false;
		for (Long2IntOpenHashMap m : bugPower.values()) {
			if (!m.isEmpty()) {
				any = true;
				break;
			}
		}
		anyBugPower = any;
	}

	/** Called from the Level#getSignal mixin for every redstone query, so it has to be cheap. */
	public static int bugPowerAt(Level level, BlockPos consumer) {
		if (!anyBugPower) {
			return 0;
		}
		Long2IntOpenHashMap map = bugPower.get(level.dimension());
		return map == null ? 0 : map.get(consumer.asLong());
	}

	// =================================================================== devices

	public static @Nullable DeviceRecord device(String key) {
		return data == null ? null : data.devices.get(key);
	}

	/** Inserts or replaces a device and updates every channel it touched. */
	public static void putDevice(DeviceRecord d) {
		if (data == null) {
			return;
		}
		DeviceRecord old = data.devices.get(d.key());
		String oldChannel = null;
		if (old != null) {
			unindex(old);
			oldChannel = old.active() ? old.channelKey() : null;
			if (old.kind == DeviceRecord.Kind.BUG && old.listen) {
				setBugPower(old.dimension, old.pos, 0);
			}
		}
		data.devices.put(d.key(), d);
		index(d);
		data.setDirty();
		if (oldChannel != null) {
			refreshChannel(oldChannel);
		}
		if (d.active()) {
			refreshChannel(d.channelKey());
			if (d.listen) {
				push(d, channelValue(d.owner, d.channel));
			}
		} else if (d.kind == DeviceRecord.Kind.BUG && old != null && old.listen) {
			push(d, 0);
		}
	}

	public static void removeDevice(String key) {
		if (data == null) {
			return;
		}
		DeviceRecord d = data.devices.remove(key);
		if (d == null) {
			return;
		}
		unindex(d);
		data.setDirty();
		if (d.kind == DeviceRecord.Kind.BUG) {
			setBugPower(d.dimension, d.pos, 0);
			if (d.listen && server != null) {
				ServerLevel level = server.getLevel(d.dimension);
				if (level != null && level.isLoaded(d.pos)) {
					level.neighborChanged(d.pos, Blocks.AIR, null);
				}
			}
		}
		if (d.active()) {
			refreshChannel(d.channelKey());
		}
	}

	/** A POST device measured a new signal strength. */
	public static void updatePostValue(String key, int value) {
		DeviceRecord d = device(key);
		if (d == null || d.value == value) {
			return;
		}
		d.value = value;
		if (data != null) {
			data.setDirty();
		}
		if (d.active() && !d.listen) {
			refreshChannel(d.channelKey());
		}
	}

	// =================================================================== permissions

	public static boolean hasImplant(Player player) {
		return player.getAttachedOrElse(NetlinkRegistry.IMPLANT, false);
	}

	public static boolean canModify(UUID player, @Nullable UUID owner) {
		if (owner == null || owner.equals(player)) {
			return true;
		}
		NetworkInfo info = data == null ? null : data.networks.get(owner);
		return info != null && info.authorized.containsKey(player);
	}

	public static String ownerName(@Nullable UUID owner) {
		if (owner == null) {
			return "nobody";
		}
		if (server != null) {
			ServerPlayer online = server.getPlayerList().getPlayer(owner);
			if (online != null) {
				return online.getName().getString();
			}
		}
		NetworkInfo info = data == null ? null : data.networks.get(owner);
		return info != null ? info.ownerName : owner.toString().substring(0, 8);
	}

	public static void touchNetwork(ServerPlayer player) {
		if (data != null) {
			data.network(player.getUUID(), player.getName().getString());
		}
	}

	public static List<UUID> accessibleOwners(ServerPlayer player) {
		touchNetwork(player);
		List<UUID> list = new ArrayList<>();
		list.add(player.getUUID());
		if (data != null) {
			for (NetworkInfo info : data.networks.values()) {
				if (!info.owner.equals(player.getUUID()) && info.authorized.containsKey(player.getUUID())) {
					list.add(info.owner);
				}
			}
		}
		return list;
	}

	public static Set<String> channelsOf(UUID owner) {
		Set<String> out = new TreeSet<>();
		if (data == null) {
			return out;
		}
		for (DeviceRecord d : data.devices.values()) {
			if (owner.equals(d.owner) && !d.channel.isEmpty()) {
				out.add(d.channel);
			}
		}
		String prefix = owner + "|";
		for (String key : data.manual.keySet()) {
			if (key.startsWith(prefix)) {
				out.add(key.substring(prefix.length()));
			}
		}
		for (String key : extraSources.keySet()) {
			if (key.startsWith(prefix)) {
				out.add(key.substring(prefix.length()));
			}
		}
		return out;
	}

	public static List<NetEntry> networkEntries(ServerPlayer player) {
		List<NetEntry> out = new ArrayList<>();
		for (UUID owner : accessibleOwners(player)) {
			out.add(new NetEntry(owner, ownerName(owner), owner.equals(player.getUUID()), List.copyOf(channelsOf(owner))));
		}
		return out;
	}

	public static String sanitizeChannel(String raw) {
		StringBuilder sb = new StringBuilder();
		for (char c : raw.trim().toCharArray()) {
			if (Character.isLetterOrDigit(c) || c == '/' || c == '.' || c == '_' || c == '-' || c == ' ' || c == ':' || c == '#') {
				sb.append(c);
			}
			if (sb.length() >= NetlinkPackets.MAX_CHANNEL) {
				break;
			}
		}
		return sb.toString().trim();
	}

	private static void deny(ServerPlayer player, String key, Object... args) {
		player.sendOverlayMessage(Component.translatable(key, args).withStyle(ChatFormatting.RED));
	}

	public static boolean requireImplant(ServerPlayer player) {
		if (hasImplant(player)) {
			return true;
		}
		deny(player, "message.createnetlink.no_terminal");
		return false;
	}

	// =================================================================== HUD vision

	public static void setHud(ServerPlayer player, boolean on) {
		if (on && hasImplant(player)) {
			hudPlayers.add(player.getUUID());
			syncHud(player);
		} else {
			hudPlayers.remove(player.getUUID());
			ServerPlayNetworking.send(player, new NetlinkPackets.HudSync(List.of()));
		}
	}

	public static boolean hudOn(Player player) {
		return hudPlayers.contains(player.getUUID());
	}

	private static void syncHud(ServerPlayer player) {
		if (data == null) {
			return;
		}
		List<HudDevice> list = new ArrayList<>();
		ResourceKey<Level> dim = player.level().dimension();
		BlockPos center = player.blockPosition();
		long r2 = (long) HUD_RADIUS * HUD_RADIUS;
		for (DeviceRecord d : data.devices.values()) {
			if (d.dimension != dim && !d.dimension.equals(dim)) {
				continue;
			}
			if (d.pos.distSqr(center) > r2) {
				continue;
			}
			int value = d.listen ? channelValue(d.owner, d.channel) : d.value;
			list.add(new HudDevice(d.kind.ordinal(), d.pos, d.face, d.channel, d.listen, value,
				!canModify(player.getUUID(), d.owner), ownerName(d.owner)));
		}
		ServerPlayNetworking.send(player, new NetlinkPackets.HudSync(list));
	}

	// =================================================================== bugs

	public static @Nullable DeviceRecord bugAt(Level level, BlockPos pos) {
		return device(DeviceRecord.key(DeviceRecord.Kind.BUG, level.dimension(), pos));
	}

	/** Sources that only ever call (buttons, levers, plates...) can't be fed a signal; everything else can. */
	/**
	 * Whether a bug on this block may LISTEN. Decided by the block type, not its current state: Create's contacts are
	 * signal sources only while they output (cabin at the floor), yet they take a signal to call the elevator.
	 * Pure sources (levers, buttons, torches, redstone blocks, observers...) stay POST only.
	 */
	public static boolean canListen(BlockState state) {
		Block b = state.getBlock();
		return !b.defaultBlockState().isSignalSource() || b instanceof RedStoneWireBlock || b instanceof DiodeBlock;
	}

	/** What a POST bug reads from the block it sits on. */
	public static int readPower(ServerLevel level, BlockPos pos, BlockState state) {
		Block b = state.getBlock();
		if (b instanceof RedStoneWireBlock) {
			return state.getValue(RedStoneWireBlock.POWER);
		}
		int v = 0;
		if (state.isSignalSource()) {
			for (Direction d : Direction.values()) {
				v = Math.max(v, state.getSignal(level, pos, d));
			}
		}
		if (v == 0 && state.hasProperty(BlockStateProperties.POWER)) {
			v = state.getValue(BlockStateProperties.POWER);
		}
		if (v == 0 && state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED)) {
			v = 15;
		}
		if (v == 0 && !state.isSignalSource()) {
			v = level.getBestNeighborSignal(pos);
		}
		return Math.min(15, v);
	}

	public static void attachBug(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face, ItemStack stack) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return;
		}
		if (bugAt(level, pos) != null) {
			deny(player, "message.createnetlink.already_bugged");
			return;
		}
		touchNetwork(player);
		DeviceRecord d = new DeviceRecord(DeviceRecord.Kind.BUG, level.dimension(), pos, player.getUUID(), "", false,
			readPower(level, pos, state), face, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		putDevice(d);
		stack.consume(1, player);
		level.playSound(null, pos, SoundEvents.COPPER_BULB_TURN_ON, SoundSource.BLOCKS, 0.6F, 1.6F);
		player.sendOverlayMessage(Component.translatable("message.createnetlink.bug_attached").withStyle(ChatFormatting.AQUA));
		if (hasImplant(player)) {
			openDeviceConfig(player, d);
		}
		if (hudOn(player)) {
			syncHud(player);
		}
	}

	public static void detachBug(ServerPlayer player, BlockPos pos) {
		DeviceRecord d = bugAt(player.level(), pos);
		if (d == null || !hasImplant(player) || player.blockPosition().distSqr(pos) > 64 * 64) {
			return;
		}
		boolean own = canModify(player.getUUID(), d.owner);
		removeDevice(d.key());
		if (own) {
			if (!player.getInventory().add(new ItemStack(NetlinkRegistry.BUG))) {
				player.drop(new ItemStack(NetlinkRegistry.BUG), false);
			}
			player.sendOverlayMessage(Component.translatable("message.createnetlink.bug_detached").withStyle(ChatFormatting.AQUA));
		} else {
			player.level().playSound(null, pos, SoundEvents.ITEM_BREAK.value(), SoundSource.BLOCKS, 0.8F, 1.4F);
			player.sendOverlayMessage(Component.translatable("message.createnetlink.bug_destroyed", ownerName(d.owner))
				.withStyle(ChatFormatting.GOLD));
		}
		if (hudOn(player)) {
			syncHud(player);
		}
	}

	/** Same-tick reaction for POST bugs; called from the Level#setBlock mixin. */
	public static void onBlockChanged(Level level, BlockPos pos) {
		if (bugKeys.isEmpty() || level.isClientSide() || !(level instanceof ServerLevel sl) || data == null) {
			return;
		}
		DeviceRecord d = data.devices.get(DeviceRecord.key(DeviceRecord.Kind.BUG, level.dimension(), pos));
		if (d == null || d.listen) {
			return;
		}
		BlockState state = sl.getBlockState(pos);
		if (state.isAir()) {
			return;   // handled (with item drop) by the poll
		}
		updatePostValue(d.key(), readPower(sl, pos, state));
	}

	private static void pollBugs() {
		if (server == null || data == null) {
			return;
		}
		boolean validate = ticks % 20 == 0;
		for (String key : List.copyOf(bugKeys)) {
			DeviceRecord d = data.devices.get(key);
			if (d == null) {
				continue;
			}
			ServerLevel level = server.getLevel(d.dimension);
			if (level == null || !level.isLoaded(d.pos)) {
				continue;
			}
			BlockState state = level.getBlockState(d.pos);
			if (validate || !d.listen) {
				String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
				if (state.isAir() || (!d.block.isEmpty() && !d.block.equals(id))) {
					removeDevice(key);
					Block.popResource(level, d.pos, new ItemStack(NetlinkRegistry.BUG));
					continue;
				}
			}
			if (!d.listen) {
				updatePostValue(key, readPower(level, d.pos, state));
			} else if (!canListen(state)) {
				d.listen = false;
				putDevice(d);
			}
		}
	}

	private static void validateLinks() {
		if (server == null || data == null) {
			return;
		}
		List<String> dead = new ArrayList<>();
		for (DeviceRecord d : data.devices.values()) {
			if (d.kind == DeviceRecord.Kind.BUG) {
				continue;
			}
			ServerLevel level = server.getLevel(d.dimension);
			if (level == null) {
				dead.add(d.key());
				continue;
			}
			if (!level.isLoaded(d.pos)) {
				continue;
			}
			if (d.kind == DeviceRecord.Kind.LINK && !(level.getBlockState(d.pos).getBlock() instanceof AdvancedRedstoneLinkBlock)) {
				dead.add(d.key());
			}
			if (d.kind == DeviceRecord.Kind.ELEVATOR && !(level.getBlockState(d.pos).getBlock() instanceof dev.exavolt.netlink.block.AdvancedElevatorPulleyBlock)) {
				dead.add(d.key());
			}
			if (d.kind == DeviceRecord.Kind.EXTENDER) {
				// only drop the record when the master plate is really gone; a block entity that isn't there yet is not proof
				BlockEntity be = level.getBlockEntity(d.pos);
				boolean plate = level.getBlockState(d.pos).getBlock() instanceof dev.exavolt.netlink.block.WallExtenderBlock;
				if (!plate || be instanceof dev.exavolt.netlink.block.WallExtenderBlockEntity ext && !ext.isMaster()) {
					dead.add(d.key());
				}
			}
		}
		for (String key : dead) {
			removeDevice(key);
		}
	}

	// =================================================================== configuration

	public static void openDeviceConfig(ServerPlayer player, DeviceRecord d) {
		boolean foreign = !canModify(player.getUUID(), d.owner);
		boolean allowListen = true;
		if (d.kind == DeviceRecord.Kind.BUG) {
			allowListen = canListen(player.level().getBlockState(d.pos));
		}
		int speed = 0;
		boolean invert = false;
		if (d.kind == DeviceRecord.Kind.EXTENDER) {
			int[] st = WallExtenders.settings(player.level(), d.pos);
			if (st == null) {
				return;
			}
			speed = st[0];
			invert = st[1] != 0;
		}
		ServerPlayNetworking.send(player, new NetlinkPackets.OpenDeviceConfig(
			d.kind.ordinal(), d.pos, Optional.ofNullable(d.owner), ownerName(d.owner), d.channel, d.listen, allowListen, foreign,
			foreign ? List.of() : networkEntries(player), speed, invert
		));
	}

	public static void openLinkConfig(ServerPlayer player, AdvancedRedstoneLinkBlockEntity be) {
		DeviceRecord d = be.ensureRecord();
		if (d != null) {
			openDeviceConfig(player, d);
		}
	}

	public static void applyDeviceConfig(ServerPlayer player, NetlinkPackets.ApplyDeviceConfig p) {
		if (!requireImplant(player) || player.blockPosition().distSqr(p.pos()) > 64 * 64) {
			return;
		}
		ServerLevel level = player.level();
		DeviceRecord.Kind kind = DeviceRecord.Kind.byId(p.kind());
		DeviceRecord d = device(DeviceRecord.key(kind, level.dimension(), p.pos()));
		if (d == null) {
			return;
		}
		if (!canModify(player.getUUID(), d.owner)) {
			deny(player, "message.createnetlink.not_authorized", ownerName(d.owner));
			return;
		}
		if (!canModify(player.getUUID(), p.owner())) {
			deny(player, "message.createnetlink.not_authorized", ownerName(p.owner()));
			return;
		}
		String channel = sanitizeChannel(p.channel());
		boolean listen = p.listen();
		if (kind == DeviceRecord.Kind.BUG && listen && !canListen(level.getBlockState(p.pos()))) {
			listen = false;
		}
		if (kind == DeviceRecord.Kind.LINK) {
			BlockEntity be = level.getBlockEntity(p.pos());
			if (be instanceof AdvancedRedstoneLinkBlockEntity link) {
				link.configure(p.owner(), channel, listen);
			}
		} else if (kind == DeviceRecord.Kind.EXTENDER) {
			WallExtenders.configure(level, p.pos(), p.owner(), channel, p.speed(), p.invert());
		} else if (kind == DeviceRecord.Kind.ELEVATOR) {
			Elevators.rename(d, p.owner(), channel.replace("/", "_"));
		} else {
			DeviceRecord nd = new DeviceRecord(d.kind, d.dimension, d.pos, p.owner(), channel, listen,
				listen ? 0 : readPower(level, d.pos, level.getBlockState(d.pos)), d.face, d.block);
			putDevice(nd);
		}
		level.playSound(null, p.pos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3F, 1.8F);
		if (hudOn(player)) {
			syncHud(player);
		}
	}

	// =================================================================== controllers

	public static void openKeyboardConfig(ServerPlayer player, InteractionHand hand) {
		if (!requireImplant(player)) {
			return;
		}
		ItemStack stack = player.getItemInHand(hand);
		KeyboardConfig cfg = stack.getOrDefault(NetlinkRegistry.KEYBOARD_CONFIG, KeyboardConfig.EMPTY);
		ServerPlayNetworking.send(player, new NetlinkPackets.OpenKeyboardConfig(hand.ordinal(), cfg, networkEntries(player)));
	}

	public static void applyKeyboardConfig(ServerPlayer player, NetlinkPackets.ApplyKeyboardConfig p) {
		if (!requireImplant(player)) {
			return;
		}
		InteractionHand hand = p.hand() == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		ItemStack stack = player.getItemInHand(hand);
		if (!(stack.getItem() instanceof KeyboardItem)) {
			return;
		}
		Optional<UUID> owner = p.config().owner();
		if (owner.isPresent() && !canModify(player.getUUID(), owner.get())) {
			deny(player, "message.createnetlink.not_authorized", ownerName(owner.get()));
			return;
		}
		List<KeyboardConfig.Bind> binds = new ArrayList<>();
		for (KeyboardConfig.Bind b : p.config().binds()) {
			String channel = sanitizeChannel(b.channel());
			if (b.key() < 32 || b.key() > 348 || channel.isEmpty() || binds.size() >= KeyboardConfig.MAX_BINDS) {
				continue;
			}
			int action = Math.max(0, Math.min(KeyboardConfig.ACTIONS - 1, b.action()));
			int ticks = Math.max(1, Math.min(KeyboardConfig.MAX_TICKS, b.ticks()));
			binds.add(new KeyboardConfig.Bind(b.key(), channel, action, ticks));
		}
		KeyboardConfig before = stack.getOrDefault(NetlinkRegistry.KEYBOARD_CONFIG, KeyboardConfig.EMPTY);
		KeyboardConfig after = new KeyboardConfig(owner.isPresent() ? owner : Optional.of(player.getUUID()), binds);
		stack.set(NetlinkRegistry.KEYBOARD_CONFIG, after);
		releaseController(player.getUUID());
		stopRemovedBinds(player.getUUID(), before, after);
	}

	private static @Nullable ItemStack heldKeyboard(ServerPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (stack.getItem() instanceof KeyboardItem) {
			return stack;
		}
		stack = player.getOffhandItem();
		return stack.getItem() instanceof KeyboardItem ? stack : null;
	}

	/** Mirrors Create's LinkedControllerServerHandler.receivePressed, with key binds instead of frequencies. */
	public static void controllerInput(ServerPlayer player, List<Integer> keys, boolean press) {
		ItemStack stack = heldKeyboard(player);
		if (stack == null || (player.isSpectator() && press)) {
			return;
		}
		KeyboardConfig cfg = stack.getOrDefault(NetlinkRegistry.KEYBOARD_CONFIG, KeyboardConfig.EMPTY);
		UUID owner = cfg.owner().orElse(null);
		if (owner == null || !canModify(player.getUUID(), owner)) {
			return;
		}
		Map<String, int[]> entries = controllers.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
		Map<Integer, int[]> held = heldKeys.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
		for (int key : keys) {
			List<KeyboardConfig.Bind> binds = cfg.bindsFor(key);
			// hold binds: Create-style entries that time out unless kept alive
			for (KeyboardConfig.Bind b : binds) {
				if (b.action() != KeyboardConfig.POWER) {
					continue;
				}
				String chKey = NetworkSavedData.channelKey(owner, b.channel());
				int[] entry = entries.get(chKey);
				if (entry != null) {
					entry[0] = press ? CONTROLLER_TIMEOUT : 0;
					continue;
				}
				if (!press) {
					continue;
				}
				entries.put(chKey, new int[]{CONTROLLER_TIMEOUT});
				controllerSources.computeIfAbsent(chKey, k -> new HashMap<>()).put(player.getUUID(), 15);
				refreshChannel(chKey);
			}
			// press binds: fire once per physical press
			if (!press) {
				held.remove(key);
				continue;
			}
			int[] timer = held.get(key);
			if (timer != null) {
				timer[0] = CONTROLLER_TIMEOUT;
				continue;
			}
			held.put(key, new int[]{CONTROLLER_TIMEOUT});
			for (KeyboardConfig.Bind b : binds) {
				if (b.action() != KeyboardConfig.POWER) {
					fireBind(owner, b);
				}
			}
		}
	}

	private static void fireBind(UUID owner, KeyboardConfig.Bind b) {
		if (data == null) {
			return;
		}
		String key = NetworkSavedData.channelKey(owner, b.channel());
		int ticks = Math.max(1, Math.min(KeyboardConfig.MAX_TICKS, b.ticks()));
		switch (b.action()) {
			case KeyboardConfig.TOGGLE -> {
				if (data.manual.containsKey(key)) {
					data.manual.remove(key);
				} else {
					data.manual.put(key, 15);
				}
				data.setDirty();
			}
			case KeyboardConfig.PULSE -> pulses.put(key, new int[]{ticks, 15});
			case KeyboardConfig.TOGGLE_PULSE -> {
				if (clocks.remove(key) == null) {
					clocks.put(key, new int[]{ticks, 0});
					data.clocks.put(key, ticks);
				} else {
					data.clocks.remove(key);
				}
				data.setDirty();
			}
			default -> {
			}
		}
		refreshChannel(key);
	}

	private static void tickClocksAndKeys() {
		if (!clocks.isEmpty()) {
			for (Map.Entry<String, int[]> e : List.copyOf(clocks.entrySet())) {
				int[] c = e.getValue();
				c[1]++;
				if (c[1] % Math.max(1, c[0]) == 0) {
					refreshChannel(e.getKey());
				}
			}
		}
		if (!heldKeys.isEmpty()) {
			for (Iterator<Map.Entry<UUID, Map<Integer, int[]>>> it = heldKeys.entrySet().iterator(); it.hasNext(); ) {
				Map<Integer, int[]> m = it.next().getValue();
				m.values().removeIf(t -> --t[0] <= 0);
				if (m.isEmpty()) {
					it.remove();
				}
			}
		}
	}

	private static void tickControllers() {
		if (controllers.isEmpty()) {
			return;
		}
		for (Iterator<Map.Entry<UUID, Map<String, int[]>>> it = controllers.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Map<String, int[]>> e = it.next();
			List<String> dead = new ArrayList<>();
			for (Map.Entry<String, int[]> entry : e.getValue().entrySet()) {
				if (--entry.getValue()[0] <= 0) {
					dead.add(entry.getKey());
				}
			}
			for (String key : dead) {
				e.getValue().remove(key);
				dropControllerSource(e.getKey(), key);
			}
			if (e.getValue().isEmpty()) {
				it.remove();
			}
		}
	}

	private static void dropControllerSource(UUID player, String key) {
		Map<UUID, Integer> m = controllerSources.get(key);
		if (m != null) {
			m.remove(player);
			if (m.isEmpty()) {
				controllerSources.remove(key);
			}
		}
		refreshChannel(key);
	}

	private static void releaseController(UUID id) {
		Map<String, int[]> entries = controllers.remove(id);
		if (entries != null) {
			for (String key : entries.keySet()) {
				dropControllerSource(id, key);
			}
		}
	}

	/** Like Create's bind packet: the pressed key takes over the clicked link's channel (and network). */
	public static void bindController(ServerPlayer player, int key, BlockPos linkPos) {
		if (player.isSpectator() || key < 32 || key > 348) {
			return;
		}
		ItemStack stack = heldKeyboard(player);
		if (stack == null || !requireImplant(player)) {
			return;
		}
		if (!(player.level().getBlockEntity(linkPos) instanceof AdvancedRedstoneLinkBlockEntity link) || link.getOwner() == null
			|| link.getChannel().isEmpty()) {
			return;
		}
		if (!canModify(player.getUUID(), link.getOwner())) {
			deny(player, "message.createnetlink.not_authorized", ownerName(link.getOwner()));
			return;
		}
		KeyboardConfig cfg = stack.getOrDefault(NetlinkRegistry.KEYBOARD_CONFIG, KeyboardConfig.EMPTY);
		KeyboardConfig after = cfg.withBind(key, link.getChannel(), link.getOwner());
		stack.set(NetlinkRegistry.KEYBOARD_CONFIG, after);
		stopRemovedBinds(player.getUUID(), cfg, after);
	}

	/**
	 * A bind that was deleted or changed must take its effect with it: a running toggle-pulse clock, a pulse, a toggled-on
	 * channel or a held key. Otherwise old effects keep running alongside the new ones and stack up.
	 */
	private static void stopRemovedBinds(UUID player, KeyboardConfig before, KeyboardConfig after) {
		UUID owner = before.owner().orElse(null);
		if (owner == null || data == null) {
			return;
		}
		UUID newOwner = after.owner().orElse(null);
		Set<String> touched = new LinkedHashSet<>();
		for (KeyboardConfig.Bind b : before.binds()) {
			boolean kept = owner.equals(newOwner) && after.binds().stream()
				.anyMatch(n -> n.channel().equals(b.channel()) && n.action() == b.action());
			if (kept) {
				continue;
			}
			String key = NetworkSavedData.channelKey(owner, b.channel());
			switch (b.action()) {
				case KeyboardConfig.TOGGLE -> {
					if (data.manual.remove(key) != null) {
						data.setDirty();
					}
				}
				case KeyboardConfig.PULSE -> pulses.remove(key);
				case KeyboardConfig.TOGGLE_PULSE -> {
					clocks.remove(key);
					if (data.clocks.remove(key) != null) {
						data.setDirty();
					}
				}
				default -> {
					Map<String, int[]> entries = controllers.get(player);
					if (entries != null) {
						entries.remove(key);
					}
					Map<UUID, Integer> m = controllerSources.get(key);
					if (m != null) {
						m.remove(player);
						if (m.isEmpty()) {
							controllerSources.remove(key);
						}
					}
				}
			}
			touched.add(key);
		}
		touched.forEach(NetlinkServer::refreshChannel);
	}

	// =================================================================== network screen

	public static void sendMap(ServerPlayer player, boolean open) {
		if (!requireImplant(player) || data == null) {
			return;
		}
		List<MapNetwork> nets = new ArrayList<>();
		for (UUID owner : accessibleOwners(player)) {
			NetworkInfo info = data.networks.get(owner);
			List<String> auth = info == null ? List.of() : List.copyOf(info.authorized.values());
			List<MapDevice> devices = new ArrayList<>();
			Map<String, int[]> counts = new HashMap<>();
			for (DeviceRecord d : data.devices.values()) {
				if (!owner.equals(d.owner)) {
					continue;
				}
				int value = d.listen ? channelValue(d.owner, d.channel) : d.value;
				devices.add(new MapDevice(d.kind.ordinal(), d.dimension.identifier().toString(), d.pos, d.channel, d.listen, value, hostBlock(d)));
				if (!d.channel.isEmpty()) {
					int[] c = counts.computeIfAbsent(d.channel, k -> new int[2]);
					c[d.listen ? 1 : 0]++;
				}
			}
			List<MapChannel> channels = new ArrayList<>();
			for (String ch : channelsOf(owner)) {
				String key = NetworkSavedData.channelKey(owner, ch);
				int[] c = counts.getOrDefault(ch, new int[2]);
				channels.add(new MapChannel(ch, channelValue(owner, ch), data.manual.containsKey(key) ? data.manual.get(key) : -1, c[0], c[1]));
			}
			channels.sort(Comparator.comparing(MapChannel::channel));
			nets.add(new MapNetwork(owner, ownerName(owner), owner.equals(player.getUUID()), auth, devices, channels));
		}
		ServerPlayNetworking.send(player, new NetlinkPackets.NetworkMap(open, nets));
	}

	/** The mechanism behind a device: the bugged block, or the block an Advanced Link is mounted on. */
	private static String hostBlock(DeviceRecord d) {
		if (server != null) {
			ServerLevel level = server.getLevel(d.dimension);
			if (level != null && level.isLoaded(d.pos)) {
				if (d.kind == DeviceRecord.Kind.EXTENDER || d.kind == DeviceRecord.Kind.ELEVATOR) {
					return d.block;
				}
				BlockPos host = d.kind == DeviceRecord.Kind.BUG ? d.pos : d.pos.relative(d.face.getOpposite());
				String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(host).getBlock()).toString();
				if (d.kind == DeviceRecord.Kind.LINK && !id.equals(d.block)) {
					d.block = id;
					if (data != null) {
						data.setDirty();
					}
				}
				return id;
			}
		}
		return d.block;
	}

	public static void setChannel(ServerPlayer player, NetlinkPackets.SetChannel p) {
		if (!requireImplant(player) || data == null) {
			return;
		}
		if (!canModify(player.getUUID(), p.owner())) {
			deny(player, "message.createnetlink.not_authorized", ownerName(p.owner()));
			return;
		}
		String channel = sanitizeChannel(p.channel());
		if (channel.isEmpty()) {
			return;
		}
		String key = NetworkSavedData.channelKey(p.owner(), channel);
		int value = Math.max(0, Math.min(15, p.value()));
		switch (p.action()) {
			case NetlinkPackets.ACTION_PULSE -> pulses.put(key, new int[]{10, value == 0 ? 15 : value});
			case NetlinkPackets.ACTION_CLEAR -> data.manual.remove(key);
			default -> data.manual.put(key, value);
		}
		data.setDirty();
		refreshChannel(key);
		sendMap(player, false);
	}

	// =================================================================== authorization (used by /network)

	public static boolean authorize(ServerPlayer owner, UUID target, String targetName) {
		if (data == null || owner.getUUID().equals(target)) {
			return false;
		}
		NetworkInfo info = data.network(owner.getUUID(), owner.getName().getString());
		boolean added = info.authorized.put(target, targetName) == null;
		data.setDirty();
		return added;
	}

	public static boolean revoke(ServerPlayer owner, UUID target) {
		if (data == null) {
			return false;
		}
		NetworkInfo info = data.network(owner.getUUID(), owner.getName().getString());
		boolean removed = info.authorized.remove(target) != null;
		data.setDirty();
		if (removed) {
			// drop any controller keys the revoked player still holds on this network
			Map<String, int[]> entries = controllers.get(target);
			if (entries != null) {
				String prefix = owner.getUUID() + "|";
				for (String key : List.copyOf(entries.keySet())) {
					if (key.startsWith(prefix)) {
						entries.remove(key);
						dropControllerSource(target, key);
					}
				}
			}
		}
		return removed;
	}

	// =================================================================== ticking & interaction

	private static void tick() {
		if (server == null || data == null) {
			return;
		}
		ticks++;
		uptime++;
		refreshBugs();
		if (!pulses.isEmpty()) {
			Iterator<Map.Entry<String, int[]>> it = pulses.entrySet().iterator();
			List<String> ended = new ArrayList<>();
			while (it.hasNext()) {
				Map.Entry<String, int[]> e = it.next();
				if (--e.getValue()[0] <= 0) {
					it.remove();
					ended.add(e.getKey());
				}
			}
			for (String key : ended) {
				refreshChannel(key);
			}
			// pulses that just started are refreshed when they are set
		}
		pollBugs();
		WallExtenders.tick();
		Elevators.tick(server);
		dev.exavolt.netlink.script.ScriptEngine.tick();
		runDeferred();
		if (ticks % 100 == 0) {
			validateLinks();
		}
		tickControllers();
		tickClocksAndKeys();
		if (ticks % 10 == 0 && !hudPlayers.isEmpty()) {
			for (UUID id : List.copyOf(hudPlayers)) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p == null || !hasImplant(p)) {
					hudPlayers.remove(id);
				} else {
					syncHud(p);
				}
			}
		}
	}

	public static void startPulse(String key) {
		refreshChannel(key);
	}

	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
		if (player.isSpectator()) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hit.getBlockPos();
		ItemStack stack = player.getItemInHand(hand);
		InteractionResult controller = KeyboardItem.onItemUseFirst(level, player, stack, hand, hit, pos);
		if (controller != null) {
			return controller;
		}
		boolean holdingBug = stack.is(NetlinkRegistry.BUG);
		if (stack.getItem() instanceof dev.exavolt.netlink.item.NetlinkGlueItem) {
			if (level.isClientSide() && hand == InteractionHand.MAIN_HAND) {
				dev.exavolt.netlink.item.NetlinkGlueItem.clientUse.accept(hand, hit);
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.getItem() instanceof dev.exavolt.netlink.item.WallExtenderItem && !(level.getBlockState(pos).getBlock()
			instanceof dev.exavolt.netlink.block.WallExtenderBlock)) {
			if (!level.isClientSide() && player instanceof ServerPlayer sp && level instanceof ServerLevel sl && hand == InteractionHand.MAIN_HAND) {
				WallExtenders.beginAssembly(sp, sl, pos);
			}
			return InteractionResult.SUCCESS;
		}
		if (level.isClientSide()) {
			if (holdingBug) {
				return InteractionResult.SUCCESS;
			}
			if (clientHudOn.getAsBoolean() && clientBugAt.test(pos)) {
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		}
		if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) {
			return InteractionResult.PASS;
		}
		if (holdingBug) {
			if (level.getBlockState(pos).getBlock() instanceof AdvancedRedstoneLinkBlock) {
				// a bug on our own link would be pointless; treat it like any other block anyway
			}
			attachBug(sp, sl, pos, hit.getDirection(), stack);
			return InteractionResult.SUCCESS;
		}
		if (hudOn(sp)) {
			DeviceRecord d = bugAt(level, pos);
			if (d != null) {
				if (hand == InteractionHand.MAIN_HAND) {
					openDeviceConfig(sp, d);
				}
				return InteractionResult.SUCCESS;
			}
		}
		return InteractionResult.PASS;
	}

	static void log(String msg) {
		Netlink.LOGGER.debug(msg);
	}
}
