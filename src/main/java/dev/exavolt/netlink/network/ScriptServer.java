package dev.exavolt.netlink.network;

import dev.exavolt.netlink.net.NetlinkPackets;
import dev.exavolt.netlink.script.ScriptEngine;
import dev.exavolt.netlink.script.ScriptRecord;
import dev.exavolt.netlink.script.ScriptRuntime;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server side of the script editor: listing, saving, running and stopping a network's scripts. */
public final class ScriptServer {
	private ScriptServer() {
	}

	static final String EXAMPLE = """
		--[[ Netlink script (Luau) - runs on the server while it is enabled.

		  signal.get(ch) -> 0..15            signal.set(ch, value|true|false)
		  signal.pulse(ch, ticks?, value?)   signal.clear(ch)    signal.list()
		  signal.changed(ch):Connect(function(value, old) end)   -- also :Once / :Wait()

		  task.wait(seconds)  task.spawn(fn, ...)  task.delay(seconds, fn, ...)
		  print(...)  warn(...)  tick()  time()  typeof(v)

		  local lift = elevator.get("Elevator1")    -- or elevator.wait(name, timeout)
		  lift:GoTo("2")  lift:GetFloors()  lift:IsMoving()
		  lift.Floor  lift.Target  lift.Moving  lift.Assembled
		  lift.Speed = 0.5         -- speed multiplier: 1 normal, 0 brakes and holds mid-shaft, 2 twice as fast
		  lift.Velocity            -- real speed right now, blocks per tick (+ up, - down)
		  lift.MaxSpeed = 1.5      -- blocks per tick (0 = Create default, max 3)
		  lift.Acceleration = 0.03 -- blocks per tick^2 (0 = default)
		  lift.Locked = true       -- cabin panels can't move it
		  lift.ArrivalPulse = 20   -- length of the <name>/Arrived pulse
		  lift.Departed:Connect(function(from, to) end)
		  lift.Arrived:Connect(function(floor) end)
		  lift.Requested:Connect(function(floor, player) end)

		  storage.get(key, default)  storage.set(key, value)  storage.increment(key, n)  storage.keys(prefix)
		    -- saved across restarts, shared by all scripts of this network

		  -- operators only (the network owner must be OP):
		  world.run("/time set day")   world.broadcast(msg)
		  blocks:   getBlock/getBlockId/setBlock/breakBlock/isAir  getProperty/setProperty  fill/replace  getSign/setSign
		  items:    getInventory/getSlot/setSlot  countItem/addItem/removeItem/moveItems/clearInventory
		  players:  players() getPlayer(name) playersNear  give/take/countPlayerItem  teleport  tell/actionbar/title
		            heal  effect/clearEffects  setGameMode
		  effects:  sound(x,y,z,"entity.player.levelup")  note(x,y,z,"bell",12)  particle  spawnItem  summon  lightning  explode
		  entities: entitiesNear/removeEntities     time/day/setTime/setWeather     getScore/setScore/addScore
		  world.PlayerJoined / world.PlayerLeft / world.Chatted:Connect(function(name, message) end)

		  Ctrl+Space shows suggestions; they also pop up after "." and ":".
		]]

		local lift = elevator.wait("Elevator1", 5)

		signal.changed("Home/Doorbell"):Connect(function(value)
			if value > 0 then
				print("Ding dong!")
				signal.pulse("Home/Chime", 20)
			end
		end)

		if lift then
			lift.MaxSpeed = 1.2
			lift.Arrived:Connect(function(floor)
				print(`Elevator arrived at floor {floor}`)
			end)
		end

		while true do
			signal.set("Home/Blink", signal.get("Home/Blink") == 0)
			task.wait(1)
		end
		""";

	private static boolean allowed(ServerPlayer player, UUID owner) {
		if (!NetlinkServer.hasImplant(player)) {
			return false;
		}
		if (!NetlinkServer.canModify(player.getUUID(), owner)) {
			player.sendOverlayMessage(Component.translatable("message.createnetlink.not_authorized", NetlinkServer.ownerName(owner))
				.withStyle(ChatFormatting.RED));
			return false;
		}
		return true;
	}

	private static List<ScriptRecord> scripts(UUID owner) {
		NetworkSavedData data = NetlinkServer.data();
		if (data == null) {
			return new ArrayList<>();
		}
		return data.scripts.computeIfAbsent(owner, k -> new ArrayList<>());
	}

	private static @Nullable ScriptRecord find(UUID owner, String id) {
		for (ScriptRecord r : scripts(owner)) {
			if (r.id.equals(id)) {
				return r;
			}
		}
		return null;
	}

	public static void request(ServerPlayer player, NetlinkPackets.ScriptRequest p) {
		if (!allowed(player, p.owner())) {
			return;
		}
		send(player, p.owner(), p.selected(), p.withCode());
	}

	private static void send(ServerPlayer player, UUID owner, String selected, boolean withCode) {
		List<NetlinkPackets.ScriptInfo> infos = new ArrayList<>();
		for (ScriptRecord r : scripts(owner)) {
			infos.add(new NetlinkPackets.ScriptInfo(r.id, r.name, r.enabled, ScriptEngine.isRunning(r.id), ScriptEngine.hasErrors(r.id)));
		}
		ScriptRecord sel = find(owner, selected);
		List<String> log = new ArrayList<>();
		if (sel != null) {
			for (ScriptRuntime.LogLine l : ScriptEngine.log(sel.id)) {
				log.add(l.level().name().charAt(0) + l.text());
			}
			if (log.size() > 200) {
				log = new ArrayList<>(log.subList(log.size() - 200, log.size()));
			}
		}
		ServerPlayNetworking.send(player, new NetlinkPackets.ScriptState(owner, NetlinkServer.ownerName(owner), infos,
			sel == null ? "" : sel.id, withCode && sel != null ? Optional.of(sel.code) : Optional.empty(), log));
	}

	private static String cleanName(String raw) {
		StringBuilder sb = new StringBuilder();
		for (char c : raw.trim().toCharArray()) {
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ' ' || c == '.') {
				sb.append(c);
			}
		}
		String s = sb.toString().trim();
		return s.isEmpty() ? "Script" : s.substring(0, Math.min(32, s.length()));
	}

	public static void action(ServerPlayer player, NetlinkPackets.ScriptAction p) {
		UUID owner = p.owner();
		if (!allowed(player, owner)) {
			return;
		}
		NetworkSavedData data = NetlinkServer.data();
		if (data == null) {
			return;
		}
		data.network(owner, null);
		ScriptRecord r = find(owner, p.id());
		String selected = p.id();
		boolean withCode = false;
		switch (p.action()) {
			case NetlinkPackets.SCRIPT_SAVE, NetlinkPackets.SCRIPT_RUN -> {
				String code = p.code().length() > NetlinkPackets.MAX_CODE ? p.code().substring(0, NetlinkPackets.MAX_CODE) : p.code();
				if (r == null) {
					if (scripts(owner).size() >= ScriptEngine.MAX_SCRIPTS_PER_NETWORK) {
						player.sendOverlayMessage(Component.translatable("message.createnetlink.script_limit").withStyle(ChatFormatting.RED));
						return;
					}
					r = new ScriptRecord(UUID.randomUUID().toString().substring(0, 8), cleanName(p.name()), code.isEmpty() ? EXAMPLE : code, false);
					scripts(owner).add(r);
					selected = r.id;
					withCode = true;
				} else {
					r.name = cleanName(p.name());
					r.code = code;
				}
				if (p.action() == NetlinkPackets.SCRIPT_RUN) {
					r.enabled = true;
					ScriptEngine.start(owner, r);
				} else if (ScriptEngine.isRunning(r.id) && ScriptEngine.runtime(r.id) != null) {
					ScriptEngine.runtime(r.id).name = r.name;
				}
				data.setDirty();
			}
			case NetlinkPackets.SCRIPT_STOP -> {
				if (r != null) {
					r.enabled = false;
					ScriptEngine.stop(r.id, true);
					data.setDirty();
				}
			}
			case NetlinkPackets.SCRIPT_DELETE -> {
				if (r != null) {
					ScriptEngine.forget(r.id);
					scripts(owner).remove(r);
					data.setDirty();
					selected = scripts(owner).isEmpty() ? "" : scripts(owner).getFirst().id;
					withCode = true;
				}
			}
			case NetlinkPackets.SCRIPT_CLEAR_LOG -> {
				if (r != null) {
					ScriptEngine.clearLog(r.id);
				}
			}
			default -> {
			}
		}
		send(player, owner, selected, withCode);
	}
}
