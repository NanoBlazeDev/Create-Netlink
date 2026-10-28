package dev.exavolt.netlink.script;

import dev.exavolt.netlink.Netlink;
import dev.exavolt.netlink.network.NetlinkServer;
import dev.exavolt.netlink.network.NetworkSavedData;
import org.jspecify.annotations.Nullable;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns every running script on the server: starting, stopping, ticking and delivering events. */
public final class ScriptEngine {
	private ScriptEngine() {
	}

	public static final int MAX_SCRIPTS_PER_NETWORK = 32;
	public static final int MAX_CODE = 60_000;

	private static final Map<String, ScriptRuntime> running = new LinkedHashMap<>();
	/** Scripts whose last run hit an error: the editor shows them red after they stop. */
	private static final java.util.Set<String> failed = new java.util.HashSet<>();
	/** Console history survives restarts of a script (but not of the server). */
	private static final Map<String, Deque<ScriptRuntime.LogLine>> logs = new HashMap<>();

	static {
		LuaThread.thread_orphan_check_interval = 2000;
	}

	public static void onServerStarted(NetworkSavedData data) {
		stopAll();
		for (Map.Entry<UUID, List<ScriptRecord>> e : data.scripts.entrySet()) {
			for (ScriptRecord r : e.getValue()) {
				if (r.enabled) {
					start(e.getKey(), r);
				}
			}
		}
	}

	public static void stopAll() {
		for (ScriptRuntime rt : new ArrayList<>(running.values())) {
			stop(rt.id, false);
		}
		running.clear();
		logs.clear();
		failed.clear();
	}

	public static void start(UUID owner, ScriptRecord r) {
		stop(r.id, false);
		failed.remove(r.id);
		Deque<ScriptRuntime.LogLine> log = logs.computeIfAbsent(r.id, k -> ScriptRuntime.newLog());
		ScriptRuntime rt = new ScriptRuntime(r.id, owner, r.name, log);
		try {
			rt.author = r.author.isEmpty() ? null : UUID.fromString(r.author);
		} catch (IllegalArgumentException e) {
			rt.author = null;
		}
		rt.op = WorldApi.allowed(rt);
		rt.log(ScriptRuntime.Level.SYSTEM, "▶ started " + r.name + (rt.op ? "  (operator: world library on)"
			: WorldApi.isOp(owner) ? "  (world library off: the last editor is not an operator)" : ""));
		running.put(r.id, rt);
		if (!rt.load(r.code, new ScriptApi())) {
			rt.log(ScriptRuntime.Level.SYSTEM, "■ not started: fix the error and press Run again");
			stop(r.id, false);
		}
	}

	public static void stop(String id, boolean announce) {
		ScriptRuntime rt = running.remove(id);
		if (rt == null) {
			return;
		}
		rt.kill();
		if (rt.crashed) {
			failed.add(id);
		}
		NetlinkServer.clearSources("script:" + id);
		if (announce) {
			rt.log(ScriptRuntime.Level.SYSTEM, "■ stopped");
		}
	}

	public static boolean isRunning(String id) {
		return running.containsKey(id);
	}

	public static boolean hasErrors(String id) {
		ScriptRuntime rt = running.get(id);
		return rt != null ? rt.crashed : failed.contains(id);
	}

	public static void clearLog(String id) {
		Deque<ScriptRuntime.LogLine> log = logs.get(id);
		if (log != null) {
			synchronized (log) {
				log.clear();
			}
		}
	}

	/** The last lines of a script's console (also for stopped scripts). */
	public static List<ScriptRuntime.LogLine> log(String id) {
		Deque<ScriptRuntime.LogLine> log = logs.get(id);
		if (log == null) {
			return List.of();
		}
		synchronized (log) {
			return new ArrayList<>(log);
		}
	}

	public static void forget(String id) {
		stop(id, false);
		logs.remove(id);
		failed.remove(id);
	}

	public static void tick() {
		if (running.isEmpty()) {
			return;
		}
		for (ScriptRuntime rt : new ArrayList<>(running.values())) {
			try {
				rt.tick();
			} catch (Throwable t) {
				Netlink.LOGGER.warn("Script {} failed", rt.name, t);
				rt.log(ScriptRuntime.Level.ERROR, "internal error: " + t);
				stop(rt.id, true);
			}
			NetlinkServer.runDeferred();
		}
	}

	/** Fires an event in every script of the given network. */
	public static void fire(UUID owner, String event, Varargs args) {
		if (running.isEmpty()) {
			return;
		}
		for (ScriptRuntime rt : running.values()) {
			if (rt.owner.equals(owner)) {
				rt.fire(event, args);
			}
		}
	}

	/** World events go only to operator scripts. */
	public static void fireWorld(String event, Varargs args) {
		for (ScriptRuntime rt : running.values()) {
			if (rt.op) {
				rt.fire("world:" + event, args);
			}
		}
	}

	public static void channelChanged(UUID owner, String channel, int value, int old) {
		fire(owner, "signal:" + channel, LuaValue.varargsOf(LuaValue.valueOf(value), LuaValue.valueOf(old)));
	}

	public static @Nullable ScriptRuntime runtime(String id) {
		return running.get(id);
	}
}
