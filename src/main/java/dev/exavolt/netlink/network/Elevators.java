package dev.exavolt.netlink.network;

import com.zurrtum.create.content.contraptions.AbstractContraptionEntity;
import com.zurrtum.create.content.contraptions.elevator.ElevatorColumn;
import com.zurrtum.create.content.contraptions.elevator.ElevatorContactBlock;
import com.zurrtum.create.content.contraptions.elevator.ElevatorContactBlockEntity;
import com.zurrtum.create.content.contraptions.elevator.ElevatorContraption;
import com.zurrtum.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import dev.exavolt.netlink.NetlinkRegistry;
import dev.exavolt.netlink.block.ElevatorTuning;
import dev.exavolt.netlink.script.ScriptEngine;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Advanced Elevator Pulleys: watches every one of them, publishes where the cabin is and where it's heading as network
 * channels, feeds scripts their state and events, and applies script-set tuning (speed, acceleration, lock).
 */
public final class Elevators {
	private Elevators() {
	}

	public record Floor(String name, String label, int y) {
	}

	public record Snapshot(String name, String key, List<Floor> floors, @Nullable String floor, @Nullable String target, boolean moving,
	                       boolean assembled, double height, double velocity, ElevatorParams params) {
	}

	private static final class Watch {
		boolean moving;
		@Nullable String lastFloor;
		double lastHeight = Double.NaN;
		Map<String, Integer> signals = new HashMap<>();
	}

	private static volatile Map<UUID, Map<String, Snapshot>> snapshots = Map.of();
	private static final Map<String, Watch> watches = new HashMap<>();

	public static void reset() {
		snapshots = Map.of();
		watches.clear();
	}

	public static String blockId() {
		return BuiltInRegistries.BLOCK.getKey(NetlinkRegistry.ADVANCED_ELEVATOR_PULLEY).toString();
	}

	// ---------------------------------------------------------------- registration

	/** A new Advanced Elevator Pulley joins its placer's network with a fresh name. */
	public static void register(ServerLevel level, BlockPos pos, @Nullable LivingEntity placer) {
		NetworkSavedData data = NetlinkServer.data();
		if (data == null || !(placer instanceof ServerPlayer player)) {
			return;
		}
		UUID owner = player.getUUID();
		NetlinkServer.touchNetwork(player);
		int n = 1;
		while (find(owner, "Elevator" + n) != null) {
			n++;
		}
		NetlinkServer.putDevice(new DeviceRecord(DeviceRecord.Kind.ELEVATOR, level.dimension(), pos, owner, "Elevator" + n, false, 0,
			net.minecraft.core.Direction.UP, blockId()));
		player.sendOverlayMessage(Component.translatable("message.createnetlink.elevator_registered", "Elevator" + n,
			Component.keybind("key.createnetlink.hud").withStyle(ChatFormatting.AQUA)).withStyle(ChatFormatting.GRAY));
	}

	static void rename(DeviceRecord d, UUID owner, String name) {
		NetworkSavedData data = NetlinkServer.data();
		if (data == null) {
			return;
		}
		String source = "elevator:" + d.key();
		NetlinkServer.clearSources(source);
		watches.remove(d.key());
		NetlinkServer.putDevice(new DeviceRecord(d.kind, d.dimension, d.pos, owner, name.isBlank() ? "Elevator" : name, false, 0, d.face,
			blockId()));
	}

	private static @Nullable DeviceRecord find(UUID owner, String name) {
		NetworkSavedData data = NetlinkServer.data();
		if (data == null) {
			return null;
		}
		for (DeviceRecord d : data.devices.values()) {
			if (d.kind == DeviceRecord.Kind.ELEVATOR && owner.equals(d.owner) && d.channel.equals(name)) {
				return d;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- script access (any thread while the server waits)

	public static List<String> names(UUID owner) {
		Map<String, Snapshot> m = snapshots.get(owner);
		return m == null ? List.of() : new ArrayList<>(m.keySet());
	}

	public static @Nullable Snapshot snapshot(UUID owner, String name) {
		Map<String, Snapshot> m = snapshots.get(owner);
		return m == null ? null : m.get(name);
	}

	public static ElevatorParams params(String deviceKey) {
		NetworkSavedData data = NetlinkServer.data();
		if (data == null) {
			return new ElevatorParams();
		}
		return data.elevators.computeIfAbsent(deviceKey, k -> new ElevatorParams());
	}

	/** Returns an error message, or null. */
	public static @Nullable String setParam(UUID owner, String name, String key, double value) {
		DeviceRecord d = find(owner, name);
		NetworkSavedData data = NetlinkServer.data();
		if (d == null || data == null) {
			return "no elevator named '" + name + "'";
		}
		ElevatorParams p = params(d.key());
		switch (key) {
			case "MaxSpeed" -> p.maxSpeed = Math.max(0, Math.min(3, value));
			case "Speed" -> p.speed = Math.max(0, Math.min(10, value));
			case "Acceleration" -> p.acceleration = Math.max(0, Math.min(0.25, value));
			case "Locked" -> p.locked = value != 0;
			case "ArrivalPulse" -> p.arrivalPulse = (int) Math.max(1, Math.min(200, value));
			default -> {
				return "elevator property '" + key + "' can't be set (Speed, MaxSpeed, Acceleration, Locked, ArrivalPulse)";
			}
		}
		data.setDirty();
		return null;
	}

	/** Returns an error message, or null. The call itself happens on the server thread. */
	public static @Nullable String goTo(UUID owner, String name, String floorRef) {
		Snapshot s = snapshot(owner, name);
		if (s == null) {
			return "no elevator named '" + name + "'";
		}
		if (!s.assembled()) {
			return null; // not loaded right now: nothing to move
		}
		Floor f = resolve(s.floors(), floorRef);
		if (f == null) {
			return "elevator '" + name + "' has no floor '" + floorRef + "'";
		}
		String key = s.key();
		NetlinkServer.later(() -> call(key, f.y()));
		return null;
	}

	static @Nullable Floor resolve(List<Floor> floors, String ref) {
		String r = ref.trim();
		for (Floor f : floors) {
			if (f.name().equalsIgnoreCase(r)) {
				return f;
			}
		}
		for (Floor f : floors) {
			if (!f.label().isEmpty() && f.label().equalsIgnoreCase(r)) {
				return f;
			}
		}
		try {
			int i = Integer.parseInt(r);
			if (i >= 1 && i <= floors.size()) {
				return floors.get(i - 1);
			}
		} catch (NumberFormatException ignored) {
		}
		return null;
	}

	// ---------------------------------------------------------------- world side

	private static @Nullable ElevatorPulleyBlockEntity pulley(DeviceRecord d) {
		MinecraftServer server = NetlinkServer.server();
		if (server == null) {
			return null;
		}
		ServerLevel level = server.getLevel(d.dimension);
		if (level == null || !level.isLoaded(d.pos)) {
			return null;
		}
		return level.getBlockEntity(d.pos) instanceof ElevatorPulleyBlockEntity be ? be : null;
	}

	private static void call(String deviceKey, int y) {
		NetworkSavedData data = NetlinkServer.data();
		DeviceRecord d = data == null ? null : data.devices.get(deviceKey);
		ElevatorPulleyBlockEntity be = d == null ? null : pulley(d);
		if (be == null || !(be.getLevel() instanceof ServerLevel level) || be.movedContraption == null
			|| !(be.movedContraption.getContraption() instanceof ElevatorContraption ec)) {
			return;
		}
		callColumn(level, ec, y);
	}

	static boolean callColumn(ServerLevel level, ElevatorContraption ec, int y) {
		ElevatorColumn column = ElevatorColumn.get(level, ec.getGlobalColumn());
		if (column == null || !column.contacts.contains(y) || ec.isTargetUnreachable(y)) {
			return false;
		}
		BlockPos pos = column.contactAt(y);
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ElevatorContactBlock ecb)) {
			return false;
		}
		ecb.callToContactAndUpdate(column, state, level, pos, false);
		return true;
	}

	private static List<Floor> floors(ServerLevel level, ElevatorColumn column) {
		List<Floor> out = new ArrayList<>();
		int i = 0;
		for (int y : column.contacts) {
			String name = String.valueOf(i);
			String label = "";
			if (level.isLoaded(column.contactAt(y)) && level.getBlockEntity(column.contactAt(y)) instanceof ElevatorContactBlockEntity c) {
				if (c.shortName != null && !c.shortName.isBlank()) {
					name = c.shortName;
				}
				label = c.longName == null ? "" : c.longName;
			}
			out.add(new Floor(name, label, y));
			i++;
		}
		return out;
	}

	private static @Nullable Floor at(List<Floor> floors, @Nullable Integer y) {
		if (y == null) {
			return null;
		}
		for (Floor f : floors) {
			if (f.y() == y) {
				return f;
			}
		}
		return null;
	}

	public static void tick(@Nullable MinecraftServer server) {
		NetworkSavedData data = NetlinkServer.data();
		if (server == null || data == null) {
			return;
		}
		Map<UUID, Map<String, Snapshot>> next = new HashMap<>();
		for (DeviceRecord d : new ArrayList<>(data.devices.values())) {
			if (d.kind != DeviceRecord.Kind.ELEVATOR || d.owner == null) {
				continue;
			}
			ElevatorPulleyBlockEntity be = pulley(d);
			if (be == null) {
				// chunk not loaded (e.g. nobody nearby after a restart): scripts still get the elevator, it just isn't
				// assembled until the chunk loads; settings set meanwhile are saved and applied then
				Snapshot old = snapshot(d.owner, d.channel);
				next.computeIfAbsent(d.owner, k -> new LinkedHashMap<>()).putIfAbsent(d.channel, new Snapshot(d.channel, d.key(),
					old == null ? List.of() : old.floors(), null, null, false, false, old == null ? 0 : old.height(), 0, params(d.key())));
				continue;
			}
			ElevatorParams params = params(d.key());
			// keep the client-synced tuning in step with the saved parameters
			ElevatorTuning want = new ElevatorTuning((float) params.maxSpeed, (float) params.acceleration, (float) params.speed);
			ElevatorTuning have = be.getAttached(NetlinkRegistry.ELEVATOR_TUNING);
			if (!want.equals(have == null ? ElevatorTuning.NONE : have)) {
				be.setAttached(NetlinkRegistry.ELEVATOR_TUNING, want);
				be.setChanged();
				be.sendData(); // clients line their cabin up with the server's when the tuning changes
			}
			Snapshot snap = observe(d, be, params);
			next.computeIfAbsent(d.owner, k -> new LinkedHashMap<>()).putIfAbsent(d.channel, snap);
		}
		snapshots = next;
	}

	private static Snapshot observe(DeviceRecord d, ElevatorPulleyBlockEntity be, ElevatorParams params) {
		ServerLevel level = (ServerLevel) be.getLevel();
		AbstractContraptionEntity entity = be.movedContraption;
		Watch w = watches.computeIfAbsent(d.key(), k -> new Watch());
		String name = d.channel;
		Map<String, Integer> signals = new HashMap<>();
		Snapshot snap;
		if (entity == null || !entity.isAlive() || !(entity.getContraption() instanceof ElevatorContraption ec)) {
			snap = new Snapshot(name, d.key(), List.of(), null, null, false, false, 0, 0, params);
			w.moving = false;
			w.lastHeight = Double.NaN;
		} else {
			ElevatorColumn column = ElevatorColumn.get(level, ec.getGlobalColumn());
			List<Floor> floors = column == null ? List.of() : floors(level, column);
			double cabin = entity.getY() + ec.getContactYOffset();
			double velocity = Double.isNaN(w.lastHeight) ? 0 : cabin - w.lastHeight;
			if (Math.abs(velocity) < 1e-4) {
				velocity = 0;
			}
			w.lastHeight = cabin;
			Integer targetY = ec.getCurrentTargetY(level);
			// the pulley's own "arrived" flag: once it is set Create docks the cabin exactly onto the floor, so Moving only
			// turns false when the cabin is really going to stand at the floor (a cabin held mid-way by Speed = 0 still moves)
			boolean moving = targetY != null && !ec.arrived;
			Floor here = moving ? null : at(floors, (int) Math.floor(cabin + 0.5));
			Floor target = at(floors, targetY);
			snap = new Snapshot(name, d.key(), floors, here == null ? null : here.name(), target == null ? null : target.name(), moving, true,
				cabin, velocity, params);

			String p = name + "/";
			if (moving) {
				signals.put(p + "Moving", 15);
				signals.put(p + (targetY > cabin ? "Up" : "Down"), 15);
				if (target != null) {
					signals.put(p + "Target", Math.min(15, floors.indexOf(target) + 1));
					signals.put(p + "To/" + target.name(), 15);
				}
			} else if (here != null) {
				signals.put(p + "Floor", Math.min(15, floors.indexOf(here) + 1));
				signals.put(p + "At/" + here.name(), 15);
			}

			if (moving && !w.moving) {
				NetlinkServer.scriptPulse(d.owner, p + "Departed", params.arrivalPulse, 15);
				ScriptEngine.fire(d.owner, "elevator:" + name + ":Departed", LuaValue.varargsOf(
					w.lastFloor == null ? LuaValue.NIL : LuaValue.valueOf(w.lastFloor),
					target == null ? LuaValue.NIL : LuaValue.valueOf(target.name())));
			} else if (!moving && w.moving) {
				NetlinkServer.scriptPulse(d.owner, p + "Arrived", params.arrivalPulse, 15);
				ScriptEngine.fire(d.owner, "elevator:" + name + ":Arrived", LuaValue.varargsOf(
					here == null ? LuaValue.NIL : LuaValue.valueOf(here.name()), LuaValue.NONE));
			}
			w.moving = moving;
			if (here != null) {
				w.lastFloor = here.name();
			}
		}
		// publish only what changed
		String source = "elevator:" + d.key();
		for (Map.Entry<String, Integer> e : w.signals.entrySet()) {
			if (!signals.containsKey(e.getKey())) {
				NetlinkServer.setExtraSource(d.owner, e.getKey(), source, 0);
			}
		}
		for (Map.Entry<String, Integer> e : signals.entrySet()) {
			if (!e.getValue().equals(w.signals.get(e.getKey()))) {
				NetlinkServer.setExtraSource(d.owner, e.getKey(), source, e.getValue());
			}
		}
		w.signals = signals;
		return snap;
	}

	// ---------------------------------------------------------------- cabin panel

	/** A player typed a floor on an Elevator Panel inside a cabin. */
	public static void panelRequest(ServerPlayer player, int entityId, String floorRef) {
		ServerLevel level = player.level();
		Entity e = level.getEntity(entityId);
		if (!(e instanceof AbstractContraptionEntity ace) || !(ace.getContraption() instanceof ElevatorContraption ec)
			|| ace.distanceToSqr(player) > 32 * 32) {
			return;
		}
		ElevatorColumn column = ElevatorColumn.get(level, ec.getGlobalColumn());
		if (column == null) {
			return;
		}
		List<Floor> floors = floors(level, column);
		Floor f = resolve(floors, floorRef);
		if (f == null) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.panel_no_floor", floorRef).withStyle(ChatFormatting.RED));
			return;
		}
		// an advanced pulley may lock its panels and tells scripts about the request
		NetworkSavedData data = NetlinkServer.data();
		if (data != null) {
			for (DeviceRecord d : data.devices.values()) {
				if (d.kind != DeviceRecord.Kind.ELEVATOR) {
					continue;
				}
				ElevatorPulleyBlockEntity be = pulley(d);
				if (be == null || be.movedContraption != ace) {
					continue;
				}
				ScriptEngine.fire(d.owner, "elevator:" + d.channel + ":Requested", LuaValue.varargsOf(LuaValue.valueOf(f.name()),
					LuaValue.valueOf(player.getName().getString())));
				if (params(d.key()).locked) {
					player.sendOverlayMessage(Component.translatable("message.createnetlink.panel_locked").withStyle(ChatFormatting.GOLD));
					return;
				}
			}
		}
		if (callColumn(level, ec, f.y())) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.panel_going", f.label().isEmpty() ? f.name()
				: f.name() + " · " + f.label()).withStyle(ChatFormatting.AQUA));
		} else {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.panel_unreachable", f.name()).withStyle(ChatFormatting.RED));
		}
	}
}
