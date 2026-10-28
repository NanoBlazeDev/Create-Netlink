package dev.exavolt.netlink.client.screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Everything the editor's IntelliSense knows: the Netlink API, the Luau standard library and the script's own names. */
final class LuauIntel {
	private LuauIntel() {
	}

	enum Kind {
		KEYWORD(0xFFC792EA, "kw"), FUNCTION(0xFF82AAFF, "fn"), METHOD(0xFF82AAFF, "fn"), PROPERTY(0xFFFFCB6B, "pr"),
		EVENT(0xFFF78C6C, "ev"), LIBRARY(0xFF6FE6FF, "lib"), VARIABLE(0xFFD7E3F0, "var"), SNIPPET(0xFFC3E88D, "sn");

		final int color;
		final String tag;

		Kind(int color, String tag) {
			this.color = color;
			this.tag = tag;
		}
	}

	/** A completion: {@code insert} may contain "|" to mark where the cursor goes. */
	record Item(String label, Kind kind, String signature, String doc, String insert, String returns) {
	}

	private static final Map<String, List<Item>> MEMBERS = new LinkedHashMap<>();
	private static final List<Item> GLOBALS = new ArrayList<>();
	/** Function name ("signal.set", "Elevator:GoTo") -> item, for signature help. */
	static final Map<String, Item> FUNCTIONS = new LinkedHashMap<>();

	private static void fn(String owner, String name, String params, String doc, String returns) {
		String sig = name + "(" + params + ")" + (returns.isEmpty() ? "" : " → " + returns);
		boolean method = owner.equals("Elevator") || owner.equals("Event") || owner.equals("Connection");
		Item it = new Item(name, method ? Kind.METHOD : Kind.FUNCTION, (owner.isEmpty() ? "" : owner + (method ? ":" : ".")) + sig, doc,
			name + (params.isEmpty() ? "()|" : "(|)"), returns);
		add(owner, it);
		FUNCTIONS.put(owner.isEmpty() ? name : owner + "." + name, it);
	}

	private static void prop(String owner, String name, String type, String doc, Kind kind) {
		add(owner, new Item(name, kind, (owner.isEmpty() ? "" : owner + ".") + name + ": " + type, doc, name + "|", type));
	}

	private static void add(String owner, Item it) {
		if (owner.isEmpty()) {
			GLOBALS.add(it);
		} else {
			MEMBERS.computeIfAbsent(owner, k -> new ArrayList<>()).add(it);
		}
	}

	static {
		// ---- netlink
		prop("", "signal", "library", "Channels of your network", Kind.LIBRARY);
		fn("signal", "get", "channel: string", "Current strength (0-15) of a channel.", "number");
		fn("signal", "set", "channel: string, value: number | boolean", "Drives the channel from this script until cleared.", "");
		fn("signal", "clear", "channel: string", "Stops driving the channel.", "");
		fn("signal", "pulse", "channel: string, ticks: number?, value: number?", "Short pulse (default 10 ticks at 15).", "");
		fn("signal", "list", "", "Names of all channels on the network.", "{string}");
		fn("signal", "changed", "channel: string", "Event fired with (value, old) when the channel changes.", "Event");

		prop("", "task", "library", "Roblox-style scheduler", Kind.LIBRARY);
		fn("task", "wait", "seconds: number?", "Pauses this thread; returns the time waited.", "number");
		fn("task", "spawn", "fn: (...any) -> (), ...: any", "Runs a function in a new thread.", "");
		fn("task", "delay", "seconds: number, fn: (...any) -> (), ...: any", "Runs a function after a delay.", "");
		fn("task", "defer", "fn: (...any) -> (), ...: any", "Runs a function a bit later this tick.", "");

		prop("", "elevator", "library", "Advanced Elevator Pulleys on your network", Kind.LIBRARY);
		fn("elevator", "get", "name: string", "The elevator with that name, or nil.", "Elevator?");
		fn("elevator", "wait", "name: string, timeout: number?", "Waits until the elevator is loaded.", "Elevator?");
		fn("elevator", "list", "", "Names of all loaded elevators.", "{string}");

		fn("Elevator", "GoTo", "floor: string | number", "Sends the cabin to a floor (name, label or 1-based index).", "");
		fn("Elevator", "GetFloors", "", "{Name, Label, Y, Index} for every floor.", "{Floor}");
		fn("Elevator", "IsMoving", "", "Whether the cabin is travelling.", "boolean");
		prop("Elevator", "Name", "string", "Name set with the terminal HUD.", Kind.PROPERTY);
		prop("Elevator", "Floor", "string?", "Floor the cabin stands at (nil while moving).", Kind.PROPERTY);
		prop("Elevator", "Target", "string?", "Floor the cabin is heading to.", Kind.PROPERTY);
		prop("Elevator", "Moving", "boolean", "Whether the cabin is travelling.", Kind.PROPERTY);
		prop("Elevator", "Assembled", "boolean", "Whether a cabin hangs on the pulley.", Kind.PROPERTY);
		prop("Elevator", "Height", "number", "Current height of the cabin contact.", Kind.PROPERTY);
		prop("Elevator", "Speed", "number", "Speed multiplier: 1 normal, 0 brakes smoothly and holds the cabin, 2 twice as fast.", Kind.PROPERTY);
		prop("Elevator", "Velocity", "number", "Real speed right now in blocks/tick (+ up, - down). Read only.", Kind.PROPERTY);
		prop("Elevator", "MaxSpeed", "number", "Top speed in blocks/tick (0 = Create default, max 3).", Kind.PROPERTY);
		prop("Elevator", "Acceleration", "number", "Blocks/tick² (0 = Create default).", Kind.PROPERTY);
		prop("Elevator", "Locked", "boolean", "Cabin panels can't move a locked elevator.", Kind.PROPERTY);
		prop("Elevator", "ArrivalPulse", "number", "Ticks of the <name>/Arrived pulse.", Kind.PROPERTY);
		prop("Elevator", "Departed", "Event", "(from, to) when the cabin starts moving.", Kind.EVENT);
		prop("Elevator", "Arrived", "Event", "(floor) when the cabin stops at a floor.", Kind.EVENT);
		prop("Elevator", "Requested", "Event", "(floor, player) when someone uses a cabin panel.", Kind.EVENT);

		fn("Event", "Connect", "fn: (...any) -> ()", "Calls fn every time the event fires.", "Connection");
		fn("Event", "Once", "fn: (...any) -> ()", "Calls fn the next time only.", "Connection");
		fn("Event", "Wait", "", "Pauses until the event fires; returns its values.", "...any");
		fn("Connection", "Disconnect", "", "Stops the connection.", "");
		prop("Connection", "Connected", "boolean", "False after Disconnect.", Kind.PROPERTY);

		prop("", "storage", "library", "Values that survive restarts, shared by all scripts of the network", Kind.LIBRARY);
		fn("storage", "get", "key: string, default: any?", "Saved value (or the default).", "any");
		fn("storage", "set", "key: string, value: any", "Saves a number, string, boolean or table (nil removes).", "");
		fn("storage", "increment", "key: string, delta: number?", "Adds to a saved number; returns the new value.", "number");
		fn("storage", "has", "key: string", "Whether a key is saved.", "boolean");
		fn("storage", "remove", "key: string", "Deletes a key.", "");
		fn("storage", "keys", "prefix: string?", "Saved keys (optionally starting with a prefix).", "{string}");
		fn("storage", "clear", "prefix: string?", "Deletes keys; returns how many.", "number");
		prop("", "world", "library", "Operators only: commands, blocks, inventories, players, sounds, entities, scores", Kind.LIBRARY);
		fn("world", "run", "command: string", "Runs a command with your permissions; returns its output.", "string");
		fn("world", "broadcast", "...: any", "Chat message to everyone.", "");
		fn("world", "getBlock", "x: number, y: number, z: number, dimension: string?", "Block with properties, e.g. \"minecraft:oak_stairs[facing=east,...]\".", "string?");
		fn("world", "getBlockId", "x: number, y: number, z: number, dimension: string?", "Just the block id, e.g. \"minecraft:stone\".", "string?");
		fn("world", "isAir", "x: number, y: number, z: number, dimension: string?", "Whether the block is air.", "boolean");
		fn("world", "isLoaded", "x: number, y: number, z: number, dimension: string?", "Whether the chunk is loaded.", "boolean");
		fn("world", "setBlock", "x: number, y: number, z: number, block: string, dimension: string?", "Places a block, e.g. \"minecraft:oak_stairs[facing=east]\".", "boolean");
		fn("world", "breakBlock", "x: number, y: number, z: number, drop: boolean?, dimension: string?", "Breaks a block (drops items unless drop = false).", "boolean");
		fn("world", "getProperty", "x: number, y: number, z: number, name: string, dimension: string?", "One block state property, e.g. \"facing\" or \"powered\".", "string?");
		fn("world", "setProperty", "x: number, y: number, z: number, name: string, value: any, dimension: string?", "Changes one block state property.", "boolean");
		fn("world", "fill", "x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, block: string, dimension: string?", "Fills a box (max 32768 blocks); returns how many changed.", "number");
		fn("world", "replace", "x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, from: string, to: string, dimension: string?", "Replaces one block with another inside a box.", "number");
		fn("world", "getRedstone", "x: number, y: number, z: number, dimension: string?", "Strongest redstone signal into a block.", "number");
		fn("world", "getLight", "x: number, y: number, z: number, dimension: string?", "Light level 0-15.", "number");
		fn("world", "getSign", "x: number, y: number, z: number, front: boolean?, dimension: string?", "The 4 lines of a sign.", "{string}?");
		fn("world", "setSign", "x: number, y: number, z: number, lines: {string} | string, front: boolean?, dimension: string?", "Writes up to 4 lines on a sign.", "boolean");
		fn("world", "hasInventory", "x: number, y: number, z: number, dimension: string?", "Whether the block holds items (chest, barrel, vault, depot...).", "boolean");
		fn("world", "getInventory", "x: number, y: number, z: number, dimension: string?", "{Slot, Item, Count, Name, MaxCount} of every filled slot, and the slot count.", "({Stack}?, number)");
		fn("world", "getSlot", "x: number, y: number, z: number, slot: number, dimension: string?", "One slot (1-based) or nil if empty.", "Stack?");
		fn("world", "setSlot", "x: number, y: number, z: number, slot: number, item: string?, count: number?, dimension: string?", "Puts items into a slot (nil item empties it).", "boolean");
		fn("world", "countItem", "x: number, y: number, z: number, item: string?, dimension: string?", "How many of an item (or of everything) are inside.", "number");
		fn("world", "addItem", "x: number, y: number, z: number, item: string, count: number?, dimension: string?", "Adds items; returns how many fit.", "number");
		fn("world", "removeItem", "x: number, y: number, z: number, item: string?, count: number?, dimension: string?", "Removes items; returns how many were removed.", "number");
		fn("world", "clearInventory", "x: number, y: number, z: number, dimension: string?", "Empties the inventory; returns how many items were removed.", "number");
		fn("world", "moveItems", "x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, item: string?, count: number?, dimension: string?", "Moves items between two inventories; returns how many moved.", "number");
		fn("world", "players", "", "Names of online players.", "{string}");
		fn("world", "getPlayer", "name: string", "{Name, UUID, X, Y, Z, Yaw, Pitch, Health, MaxHealth, Food, Level, Sneaking, Dimension, GameMode, HeldItem, HeldCount} or nil.", "Player?");
		fn("world", "playersNear", "x: number, y: number, z: number, radius: number, dimension: string?", "Names of players within the radius, nearest first.", "{string}");
		fn("world", "getPlayerInventory", "name: string", "Filled slots of a player's inventory.", "{Stack}?");
		fn("world", "countPlayerItem", "name: string, item: string?", "How many of an item the player carries.", "number");
		fn("world", "give", "name: string, item: string, count: number?", "Gives items (drops what doesn't fit).", "number");
		fn("world", "take", "name: string, item: string?, count: number?", "Takes items from a player; returns how many.", "number");
		fn("world", "teleport", "name: string, x: number, y: number, z: number, dimension: string?", "Teleports a player.", "boolean");
		fn("world", "tell", "name: string, ...: any", "Private chat message.", "boolean");
		fn("world", "actionbar", "name: string, ...: any", "Text above the hotbar.", "boolean");
		fn("world", "title", "name: string, title: string?, subtitle: string?, fadeIn: number?, stay: number?, fadeOut: number?", "Big title on screen (times in seconds).", "boolean");
		fn("world", "heal", "name: string, amount: number?", "Heals (fully without an amount).", "boolean");
		fn("world", "setGameMode", "name: string, mode: string", "\"survival\", \"creative\", \"adventure\" or \"spectator\".", "string");
		fn("world", "effect", "name: string, effect: string, seconds: number?, level: number?", "Gives a potion effect, e.g. \"speed\".", "string");
		fn("world", "clearEffects", "name: string", "Removes all effects.", "string");
		fn("world", "sound", "x: number, y: number, z: number, sound: string, volume: number?, pitch: number?, dimension: string?", "Plays any sound, e.g. \"entity.player.levelup\".", "");
		fn("world", "note", "x: number, y: number, z: number, instrument: string?, note: number?, volume: number?, dimension: string?", "Note block sound: harp, bell, chime, xylophone, bass... note 0-24.", "");
		fn("world", "particle", "x: number, y: number, z: number, particle: string, count: number?, spread: number?, speed: number?, dimension: string?", "Spawns particles, e.g. \"minecraft:happy_villager\".", "string");
		fn("world", "spawnItem", "x: number, y: number, z: number, item: string, count: number?, dimension: string?", "Drops items on the ground.", "");
		fn("world", "summon", "x: number, y: number, z: number, entity: string, nbt: string?, dimension: string?", "Summons an entity, e.g. \"minecraft:firework_rocket\".", "string");
		fn("world", "lightning", "x: number, y: number, z: number, dimension: string?", "Lightning bolt.", "string");
		fn("world", "explode", "x: number, y: number, z: number, power: number?, breakBlocks: boolean?, dimension: string?", "Explosion (power 4 = TNT).", "");
		fn("world", "entitiesNear", "x: number, y: number, z: number, radius: number, type: string?, dimension: string?", "{Id, Type, Name, X, Y, Z, IsPlayer, Item?, Count?} nearest first.", "{Entity}");
		fn("world", "removeEntities", "x: number, y: number, z: number, radius: number, type: string?, dimension: string?", "Removes non-player entities; returns how many.", "number");
		fn("world", "time", "", "Time of day 0-23999.", "number");
		fn("world", "day", "", "Day number.", "number");
		fn("world", "setTime", "time: number | string", "\"day\", \"night\", \"noon\" or ticks.", "string");
		fn("world", "setWeather", "weather: string, seconds: number?", "\"clear\", \"rain\" or \"thunder\".", "string");
		fn("world", "getScore", "holder: string, objective: string", "Scoreboard score or nil.", "number?");
		fn("world", "setScore", "holder: string, objective: string, value: number", "Sets a score (creates the objective).", "");
		fn("world", "addScore", "holder: string, objective: string, delta: number", "Adds to a score; returns the new value.", "number");
		prop("Stack", "Slot", "number", "Slot number (1-based).", Kind.PROPERTY);
		prop("Stack", "Item", "string", "Item id, e.g. \"minecraft:diamond\".", Kind.PROPERTY);
		prop("Stack", "Count", "number", "How many.", Kind.PROPERTY);
		prop("Stack", "Name", "string", "Display name.", Kind.PROPERTY);
		prop("Stack", "MaxCount", "number", "Max stack size.", Kind.PROPERTY);
		prop("Player", "Name", "string", "Player name.", Kind.PROPERTY);
		prop("Player", "UUID", "string", "Player UUID.", Kind.PROPERTY);
		prop("Player", "X", "number", "", Kind.PROPERTY);
		prop("Player", "Y", "number", "", Kind.PROPERTY);
		prop("Player", "Z", "number", "", Kind.PROPERTY);
		prop("Player", "Yaw", "number", "Horizontal look angle.", Kind.PROPERTY);
		prop("Player", "Pitch", "number", "Vertical look angle.", Kind.PROPERTY);
		prop("Player", "Health", "number", "", Kind.PROPERTY);
		prop("Player", "MaxHealth", "number", "", Kind.PROPERTY);
		prop("Player", "Food", "number", "Hunger 0-20.", Kind.PROPERTY);
		prop("Player", "Level", "number", "XP level.", Kind.PROPERTY);
		prop("Player", "Sneaking", "boolean", "", Kind.PROPERTY);
		prop("Player", "Dimension", "string", "", Kind.PROPERTY);
		prop("Player", "GameMode", "string", "", Kind.PROPERTY);
		prop("Player", "HeldItem", "string?", "Item id in the main hand.", Kind.PROPERTY);
		prop("Player", "HeldCount", "number", "", Kind.PROPERTY);
		prop("Entity", "Id", "number", "Entity id.", Kind.PROPERTY);
		prop("Entity", "Type", "string", "e.g. \"minecraft:zombie\".", Kind.PROPERTY);
		prop("Entity", "Name", "string", "", Kind.PROPERTY);
		prop("Entity", "X", "number", "", Kind.PROPERTY);
		prop("Entity", "Y", "number", "", Kind.PROPERTY);
		prop("Entity", "Z", "number", "", Kind.PROPERTY);
		prop("Entity", "IsPlayer", "boolean", "", Kind.PROPERTY);
		prop("Entity", "Item", "string?", "For dropped items.", Kind.PROPERTY);
		prop("Entity", "Count", "number?", "For dropped items.", Kind.PROPERTY);
		prop("world", "PlayerJoined", "Event", "(name) when a player joins.", Kind.EVENT);
		prop("world", "PlayerLeft", "Event", "(name) when a player leaves.", Kind.EVENT);
		prop("world", "Chatted", "Event", "(name, message) on every chat message.", Kind.EVENT);

		// ---- globals
		fn("", "print", "...: any", "Writes to the console.", "");
		fn("", "warn", "...: any", "Writes a warning to the console.", "");
		fn("", "wait", "seconds: number?", "Same as task.wait.", "number");
		fn("", "tick", "", "Unix time in seconds.", "number");
		fn("", "time", "", "Seconds since the script started.", "number");
		fn("", "typeof", "value: any", "Type name, Roblox style.", "string");
		fn("", "type", "value: any", "Lua type name.", "string");
		fn("", "tostring", "value: any", "", "string");
		fn("", "tonumber", "value: any, base: number?", "", "number?");
		fn("", "pairs", "t: table", "Iterates all keys.", "");
		fn("", "ipairs", "t: table", "Iterates 1..n.", "");
		fn("", "pcall", "fn: (...any) -> ...any, ...: any", "Calls fn, catching errors.", "(boolean, ...any)");
		fn("", "error", "message: any, level: number?", "Raises an error.", "");
		fn("", "assert", "value: any, message: string?", "", "any");
		fn("", "select", "index: number | \"#\", ...: any", "", "...any");
		fn("", "setmetatable", "t: table, mt: table?", "", "table");
		fn("", "getmetatable", "t: any", "", "table?");
		fn("", "rawget", "t: table, k: any", "", "any");
		fn("", "rawset", "t: table, k: any, v: any", "", "table");
		fn("", "unpack", "t: table, i: number?, j: number?", "", "...any");
		prop("", "math", "library", "", Kind.LIBRARY);
		prop("", "string", "library", "", Kind.LIBRARY);
		prop("", "table", "library", "", Kind.LIBRARY);
		prop("", "os", "library", "", Kind.LIBRARY);
		prop("", "coroutine", "library", "", Kind.LIBRARY);
		prop("", "bit32", "library", "", Kind.LIBRARY);

		for (String m : new String[]{"abs", "ceil", "floor", "max", "min", "sqrt", "random", "randomseed", "sin", "cos", "tan", "pow", "exp",
			"log", "fmod", "modf", "clamp", "round", "sign", "asin", "acos", "atan", "atan2", "rad", "deg"}) {
			fn("math", m, m.equals("clamp") ? "x: number, min: number, max: number" : m.equals("random") ? "m: number?, n: number?"
				: m.equals("max") || m.equals("min") ? "...: number" : "x: number", "", "number");
		}
		prop("math", "pi", "number", "", Kind.PROPERTY);
		prop("math", "huge", "number", "", Kind.PROPERTY);
		for (String m : new String[]{"len", "sub", "upper", "lower", "rep", "reverse", "byte", "char", "format", "find", "match", "gmatch",
			"gsub", "split"}) {
			fn("string", m, switch (m) {
				case "sub" -> "s: string, i: number, j: number?";
				case "rep" -> "s: string, n: number";
				case "format" -> "fmt: string, ...: any";
				case "find", "match" -> "s: string, pattern: string, init: number?";
				case "gsub" -> "s: string, pattern: string, repl: any, n: number?";
				case "split" -> "s: string, sep: string?";
				default -> "s: string";
			}, "", m.equals("split") ? "{string}" : "string");
		}
		for (String m : new String[]{"insert", "remove", "concat", "sort", "find", "clear", "create", "freeze", "unpack", "pack"}) {
			fn("table", m, switch (m) {
				case "insert" -> "t: table, pos: number?, value: any";
				case "remove" -> "t: table, pos: number?";
				case "concat" -> "t: table, sep: string?";
				case "sort" -> "t: table, comp: ((a, b) -> boolean)?";
				case "find" -> "t: table, value: any, init: number?";
				case "create" -> "n: number, value: any?";
				default -> "t: table";
			}, "", "");
		}
		fn("os", "clock", "", "High resolution seconds.", "number");
		fn("os", "time", "", "Unix time.", "number");
		for (String m : new String[]{"create", "resume", "yield", "status", "wrap", "running"}) {
			fn("coroutine", m, "...", "", "");
		}

		for (String k : new String[]{"local", "function", "end", "if", "then", "else", "elseif", "for", "in", "do", "while", "repeat", "until",
			"return", "break", "continue", "and", "or", "not", "nil", "true", "false", "type"}) {
			GLOBALS.add(new Item(k, Kind.KEYWORD, k, "", k + " |", ""));
		}
		GLOBALS.add(new Item("fn", Kind.SNIPPET, "local function name(args) … end", "Function", "local function |()\n\nend", ""));
		GLOBALS.add(new Item("forp", Kind.SNIPPET, "for key, value in pairs(t) do … end", "Loop over a table", "for key, value in pairs(|) do\n\nend", ""));
		GLOBALS.add(new Item("fori", Kind.SNIPPET, "for i = 1, n do … end", "Numeric loop", "for i = 1, | do\n\nend", ""));
		GLOBALS.add(new Item("loop", Kind.SNIPPET, "while true do … task.wait() end", "Endless loop with a wait", "while true do\n\t|\n\ttask.wait(1)\nend", ""));
		GLOBALS.add(new Item("on", Kind.SNIPPET, "signal.changed(…):Connect(function(value) … end)", "React to a channel",
			"signal.changed(\"|\"):Connect(function(value, old)\n\nend)", ""));
	}

	// ---------------------------------------------------------------- inference

	private static final Pattern ASSIGN = Pattern.compile("(?:local\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^\\n]*)");
	private static final Pattern NAMES = Pattern.compile("(?:local\\s+function|function|local)\\s+([A-Za-z_][A-Za-z0-9_]*)|"
		+ "for\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*))?|function\\s*[A-Za-z0-9_.:]*\\(([^)]*)\\)");

	/** Type of an expression as far as we can tell: a library name, "Elevator", "Event", "Connection" or null. */
	static String typeOf(String expr, String text) {
		expr = expr.strip();
		if (MEMBERS.containsKey(expr) && Set.of("signal", "task", "elevator", "world", "storage", "math", "string", "table", "os", "coroutine").contains(expr)) {
			return expr;
		}
		if (expr.startsWith("elevator.get(") || expr.startsWith("elevator.wait(")) {
			return "Elevator";
		}
		if (expr.startsWith("signal.changed(") || expr.startsWith("signal.Changed(")) {
			return "Event";
		}
		if (expr.endsWith(":Connect()") || expr.matches(".*:(Connect|Once)\\(.*\\)$")) {
			return "Connection";
		}
		// a.b where a has a known type
		int dot = Math.max(expr.lastIndexOf('.'), expr.lastIndexOf(':'));
		if (dot > 0) {
			String base = typeOf(expr.substring(0, dot), text);
			String member = expr.substring(dot + 1).replaceAll("\\(.*$", "");
			if (base != null && MEMBERS.containsKey(base)) {
				for (Item it : MEMBERS.get(base)) {
					if (it.label().equals(member)) {
						String r = it.returns().replace("?", "");
						return MEMBERS.containsKey(r) ? r : null;
					}
				}
			}
			return null;
		}
		// a plain variable: look at its last assignment before
		String found = null;
		Matcher m = ASSIGN.matcher(text);
		while (m.find()) {
			if (m.group(1).equals(expr)) {
				found = m.group(2);
			}
		}
		if (found != null && !found.strip().equals(expr)) {
			return typeOf(found.replaceAll("--.*$", "").strip(), "");
		}
		return null;
	}

	/** Words the script defines itself. */
	static Set<String> localNames(String text) {
		Set<String> out = new LinkedHashSet<>();
		Matcher m = NAMES.matcher(text);
		while (m.find()) {
			for (int g = 1; g <= 3; g++) {
				if (m.group(g) != null) {
					out.add(m.group(g));
				}
			}
			if (m.group(4) != null) {
				for (String p : m.group(4).split(",")) {
					String n = p.strip().replaceAll(":.*$", "").strip();
					if (n.matches("[A-Za-z_][A-Za-z0-9_]*")) {
						out.add(n);
					}
				}
			}
		}
		Matcher l = Pattern.compile("local\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*)").matcher(text);
		while (l.find()) {
			for (String n : l.group(1).split(",")) {
				out.add(n.strip());
			}
		}
		return out;
	}

	/** Completions for the text before the cursor; {@code prefix[0]} receives the partial word being replaced. */
	static List<Item> complete(String text, int cursor, String[] prefix) {
		int i = cursor;
		while (i > 0 && (Character.isLetterOrDigit(text.charAt(i - 1)) || text.charAt(i - 1) == '_')) {
			i--;
		}
		String word = text.substring(i, cursor);
		prefix[0] = word;
		List<Item> pool = new ArrayList<>();
		if (i > 0 && (text.charAt(i - 1) == '.' || text.charAt(i - 1) == ':')) {
			boolean colon = text.charAt(i - 1) == ':';
			String base = exprBefore(text, i - 1);
			String type = typeOf(base, text.substring(0, Math.max(0, i - 1 - base.length())));
			if (type == null || !MEMBERS.containsKey(type)) {
				return List.of();
			}
			for (Item it : MEMBERS.get(type)) {
				if (colon == (it.kind() == Kind.METHOD)) {
					pool.add(it);
				}
			}
		} else {
			if (insideStringOrComment(text, cursor) || word.isEmpty()) {
				return List.of();
			}
			pool.addAll(GLOBALS);
			for (String n : localNames(text)) {
				if (!n.equals(word)) {
					pool.add(new Item(n, Kind.VARIABLE, n, "", n + "|", ""));
				}
			}
		}
		String w = word.toLowerCase(Locale.ROOT);
		List<Item> starts = new ArrayList<>(), contains = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Item it : pool) {
			String l = it.label().toLowerCase(Locale.ROOT);
			if (!seen.add(it.label() + it.kind())) {
				continue;
			}
			if (l.startsWith(w)) {
				starts.add(it);
			} else if (!w.isEmpty() && l.contains(w)) {
				contains.add(it);
			}
		}
		starts.addAll(contains);
		return starts;
	}

	/** The expression right before position {@code end} (e.g. "lift", "signal.changed(\"x\")", "lift.Arrived"). */
	static String exprBefore(String text, int end) {
		int i = end;
		int depth = 0;
		while (i > 0) {
			char c = text.charAt(i - 1);
			if (c == ')' || c == ']') {
				depth++;
			} else if (c == '(' || c == '[') {
				if (depth == 0) {
					break;
				}
				depth--;
			} else if (depth == 0 && !(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ':')) {
				break;
			}
			i--;
		}
		return text.substring(i, end);
	}

	static boolean insideStringOrComment(String text, int cursor) {
		int lineStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
		String line = text.substring(lineStart, cursor);
		char quote = 0;
		for (int k = 0; k < line.length(); k++) {
			char c = line.charAt(k);
			if (quote != 0) {
				if (c == '\\') {
					k++;
				} else if (c == quote) {
					quote = 0;
				}
			} else if (c == '"' || c == '\'' || c == '`') {
				quote = c;
			} else if (c == '-' && k + 1 < line.length() && line.charAt(k + 1) == '-') {
				return true;
			}
		}
		return quote != 0;
	}

	/** Signature help: the function whose argument list the cursor is in, and the index of the current argument. */
	static Item signatureAt(String text, int cursor, int[] argIndex) {
		int depth = 0, args = 0;
		int lineStart = Math.max(0, text.lastIndexOf('\n', Math.max(0, cursor - 1)) - 400);
		for (int i = cursor - 1; i >= lineStart; i--) {
			char c = text.charAt(i);
			if (c == ')' || c == '}' || c == ']') {
				depth++;
			} else if (c == '{' || c == '[') {
				depth--;
			} else if (c == '(') {
				if (depth == 0) {
					String callee = exprBefore(text, i);
					argIndex[0] = args;
					if (callee.isEmpty()) {
						return null;
					}
					if (FUNCTIONS.containsKey(callee)) {
						return FUNCTIONS.get(callee);
					}
					int sep = Math.max(callee.lastIndexOf('.'), callee.lastIndexOf(':'));
					if (sep > 0) {
						String type = typeOf(callee.substring(0, sep), text.substring(0, Math.max(0, i - callee.length())));
						if (type != null) {
							return FUNCTIONS.get(type + "." + callee.substring(sep + 1));
						}
					}
					return null;
				}
				depth--;
			} else if (c == ',' && depth == 0) {
				args++;
			} else if (c == '\n' && depth == 0 && i < cursor - 1 && args == 0) {
				// keep looking: calls may span lines
			}
		}
		return null;
	}
}
