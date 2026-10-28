package dev.exavolt.netlink.script;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.CoroutineLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One running script: a sandboxed LuaJ environment, its scheduled threads (Roblox-style task scheduler) and its event
 * connections. Everything here is driven from the server thread; Lua code itself runs on LuaJ's coroutine threads
 * while the server thread waits, so API functions must never touch the world directly (see {@link ScriptApi}).
 */
public final class ScriptRuntime {
	/** Instructions one thread may run before it has to yield. */
	public static final int BUDGET_PER_TICK = 250_000;
	/** Instructions all threads of a script may run in one tick. */
	public static final int TICK_BUDGET = 1_000_000;
	private int tickRemaining;
	public static final int MAX_LOG = 300;
	public static final int MAX_TASKS = 512;

	public enum Level { INFO, WARN, ERROR, SYSTEM }

	public record LogLine(long serial, Level level, String text) {
	}

	/** Thrown inside Lua code to unwind a script that is being stopped. Not an Exception, so pcall can't catch it. */
	static final class Killed extends Error {
		Killed() {
			super("script stopped", null, false, false);
		}
	}

	/** Instruction budget enforced through LuaJ's debug hook. */
	final class Budget extends DebugLib {
		int remaining = BUDGET_PER_TICK;

		@Override
		public void onInstruction(int pc, Varargs v, int top) {
			super.onInstruction(pc, v, top);
			if (killed) {
				throw new Killed();
			}
			if (--remaining < 0) {
				throw new LuaError("script timed out: too much work in one tick (add task.wait() in long loops)");
			}
		}

		/** Errors carry just "script:line message", like Roblox; no Java-flavoured traceback. */
		@Override
		public String traceback(int level) {
			return "";
		}
	}

	static final class Task {
		final LuaThread thread;
		Varargs args;
		long resumeAt;
		String waitingFor;

		Task(LuaThread thread, Varargs args, long resumeAt) {
			this.thread = thread;
			this.args = args;
			this.resumeAt = resumeAt;
		}
	}

	record Connection(int id, String event, LuaFunction fn, boolean once) {
	}

	static final LuaValue WAIT = LuaValue.userdataOf(new Object());
	static final LuaValue WAIT_EVENT = LuaValue.userdataOf(new Object());
	static final LuaValue MAIN_CALL = LuaValue.userdataOf(new Object());
	static final LuaValue MAIN_ERROR = LuaValue.userdataOf(new Object());
	public static final int MAX_MAIN_CALLS = 4096;
	/** Whether the network owner was an operator when the script started: unlocks the world API. */
	public boolean op;
	/** Player who wrote or started the code (null for old scripts): world access needs them to be an operator too. */
	public @org.jetbrains.annotations.Nullable UUID author;

	public final String id;
	public final UUID owner;
	public String name;
	final Globals globals;
	final Budget budget;
	final List<Task> tasks = new ArrayList<>();
	final List<Task> pending = new ArrayList<>();
	final Map<Integer, Connection> connections = new HashMap<>();
	private int nextConnection = 1;
	final Deque<LogLine> log;
	private long serial;
	volatile boolean killed;
	long ticks;
	public boolean crashed;

	ScriptRuntime(String id, UUID owner, String name, Deque<LogLine> log) {
		this.id = id;
		this.owner = owner;
		this.name = name;
		this.log = log;
		this.serial = log.isEmpty() ? 0 : log.peekLast().serial();
		this.budget = new Budget();
		this.globals = sandbox(budget);
	}

	// ---------------------------------------------------------------- sandbox

	private static LuaTable sharedString;

	private static synchronized LuaTable sharedStringLib() {
		if (sharedString == null) {
			Globals tmp = new Globals();
			tmp.load(new BaseLib());
			tmp.load(new PackageLib());
			tmp.load(new StringLib());
			LuaTable str = (LuaTable) tmp.get("string");
			harden(str);
			sharedString = str;
			LuaTable mt = new LuaTable();
			mt.set(LuaValue.INDEX, str);
			mt.set("__metatable", LuaValue.valueOf("The metatable is locked"));
			LuaString.s_metatable = mt;
		}
		return sharedString;
	}

	private static void harden(LuaTable str) {
		str.set("dump", LuaValue.NIL);
		LuaValue rep = str.get("rep");
		str.set("rep", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				long len = (long) args.checkstring(1).length() * Math.max(0, args.checkint(2));
				if (len > 1_000_000) {
					throw new LuaError("string.rep: result too long");
				}
				return rep.invoke(args);
			}
		});
		str.set("split", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				String s = args.checkjstring(1);
				String sep = args.optjstring(2, ",");
				LuaTable out = new LuaTable();
				int i = 1;
				if (sep.isEmpty()) {
					for (int k = 0; k < s.length(); k++) {
						out.set(i++, LuaValue.valueOf(s.substring(k, k + 1)));
					}
					return out;
				}
				int from = 0;
				while (true) {
					int at = s.indexOf(sep, from);
					if (at < 0) {
						out.set(i, LuaValue.valueOf(s.substring(from)));
						return out;
					}
					out.set(i++, LuaValue.valueOf(s.substring(from, at)));
					from = at + sep.length();
				}
			}
		});
	}

	private static Globals sandbox(Budget budget) {
		LuaTable shared = sharedStringLib();
		Globals g = new Globals();
		g.load(new BaseLib());
		g.load(new PackageLib());
		g.load(new Bit32Lib());
		g.load(new TableLib());
		g.load(new StringLib());
		g.load(new CoroutineLib());
		g.load(new JseMathLib());
		g.load(budget);
		LoadState.install(g);
		LuaC.install(g);
		// method calls on strings use the shared, hardened table; scripts get their own copy for string.x
		LuaTable copy = new LuaTable();
		for (Varargs n = shared.next(LuaValue.NIL); !n.arg1().isnil(); n = shared.next(n.arg1())) {
			copy.set(n.arg1(), n.arg(2));
		}
		g.set("string", copy);
		for (String k : new String[]{"dofile", "loadfile", "require", "module", "collectgarbage", "debug"}) {
			g.set(k, LuaValue.NIL);
		}
		g.set("package", LuaValue.NIL);
		g.STDOUT = null;
		return g;
	}

	// ---------------------------------------------------------------- logging

	public void log(Level level, String text) {
		synchronized (log) {
			for (String line : text.strip().split("\n", -1)) {
				if (line.length() > 400) {
					line = line.substring(0, 400) + "…";
				}
				log.addLast(new LogLine(++serial, level, line));
				while (log.size() > MAX_LOG) {
					log.removeFirst();
				}
			}
		}
	}

	static String concat(Varargs args) {
		StringBuilder sb = new StringBuilder();
		for (int i = 1; i <= args.narg(); i++) {
			if (i > 1) {
				sb.append('\t');
			}
			LuaValue v = args.arg(i);
			LuaValue ts = v.metatag(LuaValue.TOSTRING);
			sb.append(!ts.isnil() ? ts.call(v).tojstring() : v.tojstring());
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------- scheduling

	void spawn(LuaValue fn, Varargs args, long delay) {
		if (tasks.size() + pending.size() >= MAX_TASKS) {
			throw new LuaError("too many running threads (" + MAX_TASKS + ")");
		}
		LuaThread thread = fn instanceof LuaThread t ? t : new LuaThread(globals, fn);
		pending.add(new Task(thread, args, ticks + delay));
	}

	int connect(String event, LuaFunction fn, boolean once) {
		int id = nextConnection++;
		connections.put(id, new Connection(id, event, fn, once));
		return id;
	}

	void disconnect(int id) {
		connections.remove(id);
	}

	boolean listens(String event) {
		for (Connection c : connections.values()) {
			if (c.event.equals(event)) {
				return true;
			}
		}
		for (Task t : tasks) {
			if (event.equals(t.waitingFor)) {
				return true;
			}
		}
		return false;
	}

	/** An event happened: run connected handlers (each in a new thread) and wake threads waiting on it. */
	void fire(String event, Varargs args) {
		if (killed) {
			return;
		}
		List<Connection> hit = new ArrayList<>();
		for (Connection c : connections.values()) {
			if (c.event.equals(event)) {
				hit.add(c);
			}
		}
		for (Connection c : hit) {
			if (c.once) {
				connections.remove(c.id);
			}
			if (tasks.size() + pending.size() < MAX_TASKS) {
				pending.add(new Task(new LuaThread(globals, c.fn), args, ticks));
			}
		}
		for (Task t : tasks) {
			if (event.equals(t.waitingFor)) {
				t.waitingFor = null;
				t.args = args;
				t.resumeAt = ticks;
			}
		}
	}

	/** Runs everything that is due this tick. */
	void tick() {
		ticks++;
		tickRemaining = TICK_BUDGET;
		tasks.addAll(pending);
		pending.clear();
		// run due tasks; handlers spawned during this pass run in the same tick
		int guard = 0;
		boolean ran = true;
		while (ran && guard++ < 8 && !killed && tickRemaining >= BUDGET_PER_TICK) {
			ran = false;
			tasks.addAll(pending);
			pending.clear();
			for (Iterator<Task> it = new ArrayList<>(tasks).iterator(); it.hasNext() && !killed; ) {
				Task t = it.next();
				if (t.waitingFor != null || t.resumeAt > ticks) {
					continue;
				}
				// not enough of this tick's budget left for a full thread slice: let it run next tick instead of
				// starting it with a crumb of budget and killing it with "timed out"
				if (tickRemaining < BUDGET_PER_TICK) {
					break;
				}
				ran = true;
				resume(t);
			}
		}
	}

	private void resume(Task t) {
		Varargs args = t.args == null ? LuaValue.NONE : t.args;
		t.args = null;
		Varargs r;
		int given = BUDGET_PER_TICK;
		budget.remaining = given;
		try {
			r = t.thread.resume(args);
			// world calls: the thread yields a job, we run it here on the server thread and hand back the result
			int calls = 0;
			while (r.arg1().toboolean() && r.arg(2) == MAIN_CALL && !killed && !t.thread.getStatus().equals("dead")) {
				Varargs result;
				if (++calls > MAX_MAIN_CALLS) {
					result = LuaValue.varargsOf(MAIN_ERROR, LuaValue.valueOf("too many world calls in one tick (" + MAX_MAIN_CALLS + ")"));
				} else {
					try {
						@SuppressWarnings("unchecked")
						java.util.function.Supplier<Varargs> job = (java.util.function.Supplier<Varargs>) r.arg(3).touserdata();
						result = job.get();
					} catch (LuaError e) {
						result = LuaValue.varargsOf(MAIN_ERROR, LuaValue.valueOf(e.getMessage()));
					} catch (RuntimeException e) {
						result = LuaValue.varargsOf(MAIN_ERROR, LuaValue.valueOf(String.valueOf(e.getMessage())));
					}
				}
				r = t.thread.resume(result);
			}
			tickRemaining -= given - Math.max(0, budget.remaining);
		} catch (Throwable e) {
			tasks.remove(t);
			log(Level.ERROR, String.valueOf(e.getMessage()));
			return;
		}
		if (!r.arg1().toboolean()) {
			tasks.remove(t);
			String msg = tidy(r.arg(2).tojstring());
			if (!killed) {
				log(Level.ERROR, msg);
				crashed = true;
			}
			return;
		}
		if (t.thread.getStatus().equals("dead")) {
			tasks.remove(t);
			return;
		}
		LuaValue marker = r.arg(2);
		if (marker == WAIT) {
			t.resumeAt = ticks + Math.max(1, r.arg(3).toint());
			t.args = null;
		} else if (marker == WAIT_EVENT) {
			t.waitingFor = r.arg(3).tojstring();
		} else {
			// a plain coroutine.yield() at the top of a thread: resume next tick
			t.resumeAt = ticks + 1;
		}
	}

	/** Error text without LuaJ's Java frames and with at most a few traceback lines. */
	static String tidy(String msg) {
		StringBuilder sb = new StringBuilder();
		int frames = 0;
		for (String line : msg.split("\n")) {
			String t = line.strip();
			if (t.isEmpty() || t.startsWith("[Java]") || t.contains("prelude:") && sb.length() > 0) {
				continue;
			}
			if (t.equals("stack traceback:")) {
				continue;
			}
			if (sb.length() > 0) {
				if (++frames > 3) {
					break;
				}
				sb.append("\n  ↳ ");
			}
			sb.append(t);
		}
		return sb.toString();
	}

	/** Stops the script: every suspended thread is resumed once so it unwinds and its Java thread ends. */
	void kill() {
		killed = true;
		connections.clear();
		List<Task> all = new ArrayList<>(tasks);
		all.addAll(pending);
		tasks.clear();
		pending.clear();
		for (Task t : all) {
			if (t.thread.getStatus().equals("suspended")) {
				try {
					t.thread.resume(LuaValue.NONE);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	/** Loads the user's code (after the prelude) as the script's main thread. */
	boolean load(String code, ScriptApi api) {
		api.install(this);
		try {
			globals.load(Prelude.LUA, "prelude").call();
		} catch (LuaError e) {
			log(Level.ERROR, "prelude: " + e.getMessage());
			return false;
		}
		String lua;
		try {
			lua = LuauTranspiler.transpile(code);
		} catch (LuauTranspiler.TranspileError e) {
			log(Level.ERROR, name + ":" + e.line + ": " + e.getMessage());
			crashed = true;
			return false;
		}
		LuaValue chunk;
		try {
			chunk = globals.load(lua, name);
		} catch (LuaError e) {
			log(Level.ERROR, e.getMessage());
			crashed = true;
			return false;
		}
		pending.add(new Task(new LuaThread(globals, chunk), LuaValue.NONE, 0));
		return true;
	}

	// ---------------------------------------------------------------- small helpers for the API

	static abstract class Fn0 extends ZeroArgFunction {
	}

	static abstract class Fn1 extends OneArgFunction {
	}

	static abstract class Fn2 extends TwoArgFunction {
	}

	/** Yields the current task for some ticks; returns the seconds actually waited. */
	Varargs waitTicks(int t) {
		long start = ticks;
		globals.yield(LuaValue.varargsOf(WAIT, LuaValue.valueOf(t)));
		return LuaValue.valueOf((ticks - start) / 20.0);
	}

	/** Runs a job on the server thread (the only place the world may be touched) and returns its result. */
	Varargs callMain(java.util.function.Supplier<Varargs> job) {
		if (globals.running == null || globals.running.isMainThread()) {
			throw new LuaError("world functions can only be used inside the script, not while it loads");
		}
		Varargs r = globals.yield(LuaValue.varargsOf(MAIN_CALL, LuaValue.userdataOf(job)));
		if (r.arg1() == MAIN_ERROR) {
			throw new LuaError(r.arg(2).tojstring());
		}
		return r;
	}

	Varargs waitEvent(String event) {
		return globals.yield(LuaValue.varargsOf(WAIT_EVENT, LuaValue.valueOf(event)));
	}



	static Deque<LogLine> newLog() {
		return new ArrayDeque<>();
	}
}
