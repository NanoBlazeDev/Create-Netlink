package dev.exavolt.netlink.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.exavolt.netlink.network.NetlinkServer;
import dev.exavolt.netlink.network.NetworkSavedData;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * {@code storage}: values that survive restarts and are shared by every script of a network (balances, counters,
 * settings). Numbers, strings, booleans and tables of those are kept; setting nil removes a key.
 */
final class StorageApi {
	private StorageApi() {
	}

	private static final int MAX_KEYS = 2000;
	private static final int MAX_VALUE = 65536;
	private static final int MAX_DEPTH = 16;

	private static Map<String, String> store(UUID owner) {
		NetworkSavedData data = NetlinkServer.data();
		if (data == null) {
			throw new LuaError("storage is not available right now");
		}
		return data.storage.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
	}

	private static void dirty() {
		NetworkSavedData data = NetlinkServer.data();
		if (data != null) {
			data.setDirty();
		}
	}

	private static VarArgFunction fn(Function<Varargs, Varargs> body) {
		return new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return body.apply(args);
			}
		};
	}

	// ---------------------------------------------------------------- encoding

	static String encode(LuaValue v) {
		if (v.isboolean()) {
			return "b:" + v.toboolean();
		}
		if (v.isnumber() && !v.isstring()) {
			return "n:" + number(v);
		}
		if (v.isstring()) {
			return "s:" + v.tojstring();
		}
		if (v.istable()) {
			return "t:" + json(v, 0);
		}
		throw new LuaError("storage can keep numbers, strings, booleans and tables, not " + v.typename());
	}

	private static String number(LuaValue v) {
		double d = v.todouble();
		if (!Double.isFinite(d)) {
			throw new LuaError("storage can't keep NaN or infinite numbers");
		}
		if (d == Math.rint(d) && Math.abs(d) < 1e15) {
			return String.valueOf((long) d);
		}
		return String.valueOf(d);
	}

	private static JsonElement json(LuaValue v, int depth) {
		if (depth > MAX_DEPTH) {
			throw new LuaError("table is nested too deep for storage");
		}
		if (v.isnil()) {
			return JsonNull.INSTANCE;
		}
		if (v.isboolean()) {
			return new JsonPrimitive(v.toboolean());
		}
		if (v.isnumber() && !v.isstring()) {
			double d = v.todouble();
			if (!Double.isFinite(d)) {
				throw new LuaError("storage can't keep NaN or infinite numbers");
			}
			return d == Math.rint(d) && Math.abs(d) < 1e15 ? new JsonPrimitive((long) d) : new JsonPrimitive(d);
		}
		if (v.isstring()) {
			return new JsonPrimitive(v.tojstring());
		}
		if (v.istable()) {
			LuaTable t = (LuaTable) v;
			int n = t.length();
			int keys = 0;
			LuaValue k = LuaValue.NIL;
			while (true) {
				Varargs e = t.next(k);
				if ((k = e.arg1()).isnil()) {
					break;
				}
				keys++;
			}
			if (n > 0 && keys == n) {
				JsonArray arr = new JsonArray();
				for (int i = 1; i <= n; i++) {
					arr.add(json(t.get(i), depth + 1));
				}
				return arr;
			}
			JsonObject obj = new JsonObject();
			k = LuaValue.NIL;
			while (true) {
				Varargs e = t.next(k);
				if ((k = e.arg1()).isnil()) {
					break;
				}
				String key = k.isnumber() && !k.isstring() ? "#" + number(k) : k.tojstring();
				obj.add(key, json(e.arg(2), depth + 1));
			}
			return obj;
		}
		throw new LuaError("storage can't keep a " + v.typename());
	}

	static LuaValue decode(String s) {
		if (s.length() < 2) {
			return LuaValue.NIL;
		}
		String body = s.substring(2);
		return switch (s.charAt(0)) {
			case 'b' -> LuaValue.valueOf(Boolean.parseBoolean(body));
			case 'n' -> decodeNumber(body);
			case 's' -> LuaValue.valueOf(body);
			case 't' -> lua(JsonParser.parseString(body));
			default -> LuaValue.NIL;
		};
	}

	/** Whole numbers come back as integers; anything else (including values saved by older versions) as a double. */
	private static LuaValue decodeNumber(String body) {
		try {
			return LuaValue.valueOf(Long.parseLong(body));
		} catch (NumberFormatException e) {
			try {
				return LuaValue.valueOf(Double.parseDouble(body));
			} catch (NumberFormatException e2) {
				return LuaValue.NIL;
			}
		}
	}

	private static LuaValue lua(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return LuaValue.NIL;
		}
		if (e.isJsonPrimitive()) {
			JsonPrimitive p = e.getAsJsonPrimitive();
			if (p.isBoolean()) {
				return LuaValue.valueOf(p.getAsBoolean());
			}
			if (p.isNumber()) {
				double d = p.getAsDouble();
				return d == Math.rint(d) && Math.abs(d) < 1e15 ? LuaValue.valueOf((long) d) : LuaValue.valueOf(d);
			}
			return LuaValue.valueOf(p.getAsString());
		}
		LuaTable t = new LuaTable();
		if (e.isJsonArray()) {
			int i = 1;
			for (JsonElement x : e.getAsJsonArray()) {
				t.set(i++, lua(x));
			}
			return t;
		}
		for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
			String key = x.getKey();
			if (key.startsWith("#")) {
				try {
					double d = Double.parseDouble(key.substring(1));
					t.set(d == Math.rint(d) ? LuaValue.valueOf((long) d) : LuaValue.valueOf(d), lua(x.getValue()));
					continue;
				} catch (NumberFormatException ignored) {
				}
			}
			t.set(key, lua(x.getValue()));
		}
		return t;
	}

	// ---------------------------------------------------------------- the library

	static void install(ScriptRuntime rt) {
		LuaTable storage = new LuaTable();
		UUID owner = rt.owner;

		storage.set("get", fn(a -> {
			String v = store(owner).get(a.checkjstring(1));
			return v == null ? a.arg(2) : decode(v);
		}));

		storage.set("set", fn(a -> {
			String key = a.checkjstring(1);
			Map<String, String> m = store(owner);
			if (a.arg(2).isnil()) {
				if (m.remove(key) != null) {
					dirty();
				}
				return LuaValue.NONE;
			}
			String v = encode(a.arg(2));
			if (v.length() > MAX_VALUE) {
				throw new LuaError("value for '" + key + "' is too big (" + v.length() + " chars, max " + MAX_VALUE + ")");
			}
			if (!m.containsKey(key) && m.size() >= MAX_KEYS) {
				throw new LuaError("storage is full (" + MAX_KEYS + " keys)");
			}
			m.put(key, v);
			dirty();
			return LuaValue.NONE;
		}));

		storage.set("remove", fn(a -> {
			if (store(owner).remove(a.checkjstring(1)) != null) {
				dirty();
			}
			return LuaValue.NONE;
		}));

		storage.set("increment", fn(a -> {
			String key = a.checkjstring(1);
			double delta = a.optdouble(2, 1);
			Map<String, String> m = store(owner);
			synchronized (m) {
				String old = m.get(key);
				LuaValue cur = old == null ? LuaValue.valueOf(0) : decode(old);
				if (!cur.isnumber()) {
					throw new LuaError("'" + key + "' is not a number");
				}
				LuaValue next = LuaValue.valueOf(cur.todouble() + delta);
				if (!m.containsKey(key) && m.size() >= MAX_KEYS) {
					throw new LuaError("storage is full (" + MAX_KEYS + " keys)");
				}
				m.put(key, "n:" + number(next));
				dirty();
				return decode("n:" + number(next));
			}
		}));

		storage.set("has", fn(a -> LuaValue.valueOf(store(owner).containsKey(a.checkjstring(1)))));

		storage.set("keys", fn(a -> {
			String prefix = a.optjstring(1, "");
			List<String> keys = new ArrayList<>();
			for (String k : store(owner).keySet()) {
				if (k.startsWith(prefix)) {
					keys.add(k);
				}
			}
			Collections.sort(keys);
			LuaTable t = new LuaTable();
			for (int i = 0; i < keys.size(); i++) {
				t.set(i + 1, keys.get(i));
			}
			return t;
		}));

		storage.set("clear", fn(a -> {
			String prefix = a.optjstring(1, "");
			int n = 0;
			Map<String, String> m = store(owner);
			for (String k : new ArrayList<>(m.keySet())) {
				if (k.startsWith(prefix)) {
					m.remove(k);
					n++;
				}
			}
			if (n > 0) {
				dirty();
			}
			return LuaValue.valueOf(n);
		}));

		rt.globals.set("storage", storage);
	}
}
