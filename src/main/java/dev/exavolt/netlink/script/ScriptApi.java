package dev.exavolt.netlink.script;

import dev.exavolt.netlink.network.Elevators;
import dev.exavolt.netlink.network.NetlinkServer;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.List;
import java.util.function.Function;

/**
 * Java side of the script API. Lua runs on coroutine threads while the server thread waits, so nothing here touches the
 * world: reads come from plain data, writes are recorded and applied on the server thread right after the script's turn.
 */
public final class ScriptApi {
	private ScriptRuntime rt;

	private static LuaFunction fn(Function<Varargs, Varargs> body) {
		return new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return body.apply(args);
			}
		};
	}

	private static int ticksOf(LuaValue seconds, int min) {
		if (seconds.isnil()) {
			return min;
		}
		double s = seconds.checkdouble();
		if (Double.isNaN(s) || s < 0) {
			s = 0;
		}
		return (int) Math.max(min, Math.min(20L * 60 * 60 * 24, Math.round(s * 20)));
	}

	private static String channel(LuaValue v) {
		String c = NetlinkServer.sanitizeChannel(v.checkjstring());
		if (c.isEmpty()) {
			throw new LuaError("empty channel name");
		}
		return c;
	}

	void install(ScriptRuntime rt) {
		this.rt = rt;
		LuaTable g = rt.globals;
		if (rt.op) {
			WorldApi.install(rt);
		}
		StorageApi.install(rt);

		LuaFunction print = fn(a -> {
			rt.log(ScriptRuntime.Level.INFO, ScriptRuntime.concat(a));
			return LuaValue.NONE;
		});
		g.set("print", print);
		g.set("warn", fn(a -> {
			rt.log(ScriptRuntime.Level.WARN, ScriptRuntime.concat(a));
			return LuaValue.NONE;
		}));

		// ---- task library (Roblox style)
		LuaTable task = new LuaTable();
		LuaFunction wait = fn(a -> rt.waitTicks(ticksOf(a.arg1(), 1)));
		task.set("wait", wait);
		task.set("spawn", fn(a -> {
			rt.spawn(a.checkvalue(1), a.subargs(2), 0);
			return LuaValue.NONE;
		}));
		task.set("defer", task.get("spawn"));
		task.set("delay", fn(a -> {
			rt.spawn(a.checkvalue(2), a.subargs(3), ticksOf(a.arg1(), 0));
			return LuaValue.NONE;
		}));
		g.set("task", task);
		g.set("wait", wait);
		g.set("spawn", task.get("spawn"));
		g.set("delay", task.get("delay"));
		g.set("tick", fn(a -> LuaValue.valueOf(System.currentTimeMillis() / 1000.0)));
		g.set("time", fn(a -> LuaValue.valueOf(rt.ticks / 20.0)));

		LuaTable os = new LuaTable();
		os.set("clock", fn(a -> LuaValue.valueOf(System.nanoTime() / 1.0e9)));
		os.set("time", fn(a -> LuaValue.valueOf(System.currentTimeMillis() / 1000)));
		g.set("os", os);

		// ---- events
		g.set("__connect", fn(a -> LuaValue.valueOf(rt.connect(a.checkjstring(1), a.checkfunction(2), a.optboolean(3, false)))));
		g.set("__disconnect", fn(a -> {
			rt.disconnect(a.checkint(1));
			return LuaValue.NONE;
		}));
		g.set("__wait", fn(a -> rt.waitEvent(a.checkjstring(1))));

		// ---- signals on the script owner's network
		LuaTable signal = new LuaTable();
		signal.set("get", fn(a -> LuaValue.valueOf(NetlinkServer.peekChannel(rt.owner, channel(a.arg1())))));
		signal.set("set", fn(a -> {
			int v = a.isnil(2) ? 15 : a.arg(2).isboolean() ? (a.arg(2).toboolean() ? 15 : 0) : a.checkint(2);
			NetlinkServer.setScriptSource(rt.owner, channel(a.arg1()), "script:" + rt.id, Math.max(0, Math.min(15, v)));
			return LuaValue.NONE;
		}));
		signal.set("clear", fn(a -> {
			NetlinkServer.setScriptSource(rt.owner, channel(a.arg1()), "script:" + rt.id, 0);
			return LuaValue.NONE;
		}));
		signal.set("pulse", fn(a -> {
			NetlinkServer.scriptPulse(rt.owner, channel(a.arg1()), Math.max(1, Math.min(1200, a.optint(2, 10))),
				Math.max(1, Math.min(15, a.optint(3, 15))));
			return LuaValue.NONE;
		}));
		signal.set("list", fn(a -> {
			LuaTable t = new LuaTable();
			int i = 1;
			for (String c : NetlinkServer.channelsOf(rt.owner)) {
				t.set(i++, LuaValue.valueOf(c));
			}
			return t;
		}));
		g.set("signal", signal);

		// ---- elevators (Advanced Elevator Pulleys on this network)
		g.set("__elev_list", fn(a -> {
			LuaTable t = new LuaTable();
			int i = 1;
			for (String n : Elevators.names(rt.owner)) {
				t.set(i++, LuaValue.valueOf(n));
			}
			return t;
		}));
		g.set("__elev_exists", fn(a -> LuaValue.valueOf(Elevators.snapshot(rt.owner, a.checkjstring(1)) != null)));
		g.set("__elev_get", fn(a -> {
			Elevators.Snapshot s = Elevators.snapshot(rt.owner, a.checkjstring(1));
			if (s == null) {
				return LuaValue.NIL;
			}
			String key = a.checkjstring(2);
			return switch (key) {
				case "Floor" -> s.floor() == null ? LuaValue.NIL : LuaValue.valueOf(s.floor());
				case "Target" -> s.target() == null ? LuaValue.NIL : LuaValue.valueOf(s.target());
				case "Moving" -> LuaValue.valueOf(s.moving());
				case "Assembled" -> LuaValue.valueOf(s.assembled());
				case "Height" -> LuaValue.valueOf(s.height());
				case "Velocity" -> LuaValue.valueOf(s.velocity());
				case "Speed" -> LuaValue.valueOf(s.params().speed);
				case "MaxSpeed" -> LuaValue.valueOf(s.params().maxSpeed);
				case "Acceleration" -> LuaValue.valueOf(s.params().acceleration);
				case "Locked" -> LuaValue.valueOf(s.params().locked);
				case "ArrivalPulse" -> LuaValue.valueOf(s.params().arrivalPulse);
				default -> LuaValue.NIL;
			};
		}));
		g.set("__elev_set", fn(a -> {
			String err = Elevators.setParam(rt.owner, a.checkjstring(1), a.checkjstring(2), a.arg(3).isboolean()
				? (a.arg(3).toboolean() ? 1 : 0) : a.checkdouble(3));
			if (err != null) {
				throw new LuaError(err);
			}
			return LuaValue.NONE;
		}));
		g.set("__elev_floors", fn(a -> {
			Elevators.Snapshot s = Elevators.snapshot(rt.owner, a.checkjstring(1));
			LuaTable t = new LuaTable();
			if (s == null) {
				return t;
			}
			List<Elevators.Floor> floors = s.floors();
			for (int i = 0; i < floors.size(); i++) {
				Elevators.Floor f = floors.get(i);
				LuaTable e = new LuaTable();
				e.set("Name", f.name());
				e.set("Label", f.label());
				e.set("Y", f.y());
				e.set("Index", i + 1);
				t.set(i + 1, e);
			}
			return t;
		}));
		g.set("__elev_goto", fn(a -> {
			String err = Elevators.goTo(rt.owner, a.checkjstring(1), a.arg(2).tojstring());
			if (err != null) {
				throw new LuaError(err);
			}
			return LuaValue.NONE;
		}));
	}
}
