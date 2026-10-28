package dev.exavolt.netlink.script;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.zurrtum.create.foundation.item.ItemHelper;
import dev.exavolt.netlink.network.NetlinkServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jspecify.annotations.Nullable;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The {@code world} library, only for scripts of operators: commands, blocks, inventories, players, sounds, entities and
 * scoreboards. Every call is executed on the server thread through {@link ScriptRuntime#callMain}. Functions that take a
 * position accept an optional dimension id ("minecraft:the_nether") as their last argument.
 */
final class WorldApi {
	private WorldApi() {
	}

	private static final int MAX_FILL = 32768;

	/** World access needs the network owner and the player who last edited or started the script to be operators. */
	static boolean allowed(ScriptRuntime rt) {
		return isOp(rt.owner) && (rt.author == null || rt.author.equals(rt.owner) || isOp(rt.author));
	}

	static boolean isOp(UUID owner) {
		MinecraftServer server = NetlinkServer.server();
		if (server == null) {
			return false;
		}
		return server.getPlayerList().isOp(new NameAndId(owner, NetlinkServer.ownerName(owner)));
	}

	// ---------------------------------------------------------------- helpers

	private static MinecraftServer server() {
		MinecraftServer server = NetlinkServer.server();
		if (server == null) {
			throw new LuaError("server is not running");
		}
		return server;
	}

	private static ServerLevel level(LuaValue dim) {
		MinecraftServer server = server();
		if (dim.isnil()) {
			return server.overworld();
		}
		Identifier id = Identifier.tryParse(dim.checkjstring());
		ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		if (level == null) {
			throw new LuaError("unknown dimension '" + dim.tojstring() + "'");
		}
		return level;
	}

	private static BlockPos pos(Varargs a, int i) {
		return BlockPos.containing(a.checkdouble(i), a.checkdouble(i + 1), a.checkdouble(i + 2));
	}

	private static LuaFunction main(ScriptRuntime rt, Function<Varargs, Varargs> body) {
		return new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				Supplier<Varargs> job = () -> {
					if (!allowed(rt)) {
						throw new LuaError("the world library needs the network owner and the script's last editor to be operators");
					}
					return body.apply(args);
				};
				return rt.callMain(job);
			}
		};
	}

	private static String describe(BlockState state) {
		return BlockStateParser.serialize(state);
	}

	private static BlockState parseBlock(ServerLevel level, String text) {
		try {
			return BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), text, false).blockState();
		} catch (CommandSyntaxException e) {
			throw new LuaError("bad block '" + text + "': " + e.getMessage());
		}
	}

	private static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static Item item(String text) {
		Identifier id = Identifier.tryParse(text.trim().toLowerCase(Locale.ROOT));
		Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
		if (item.isEmpty() || item.get() == Items.AIR) {
			throw new LuaError("unknown item '" + text + "'");
		}
		return item.get();
	}

	/** nil, "" or "*" match any item. */
	private static Predicate<ItemStack> matcher(LuaValue v) {
		if (v.isnil() || v.tojstring().isEmpty() || v.tojstring().equals("*")) {
			return s -> !s.isEmpty();
		}
		Item it = item(v.checkjstring());
		return s -> !s.isEmpty() && s.is(it);
	}

	private static String itemId(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	private static LuaTable stackTable(int slot, ItemStack s) {
		LuaTable t = new LuaTable();
		t.set("Slot", slot);
		t.set("Item", itemId(s));
		t.set("Count", s.getCount());
		t.set("Name", s.getHoverName().getString());
		t.set("MaxCount", s.getMaxStackSize());
		return t;
	}

	private static int count(Varargs a, int i, int def) {
		return a.arg(i).isnil() ? def : Math.max(0, a.checkint(i));
	}

	private static @Nullable Container container(ServerLevel level, BlockPos p) {
		if (!level.isLoaded(p)) {
			return null;
		}
		return ItemHelper.getInventory(level, p, null);
	}

	private static Container needContainer(ServerLevel level, BlockPos p) {
		Container c = container(level, p);
		if (c == null) {
			throw new LuaError("no inventory at " + p.getX() + " " + p.getY() + " " + p.getZ());
		}
		return c;
	}

	/** Puts as much of the stack as fits; returns how many went in. */
	private static int insert(Container c, ItemStack stack) {
		int left = stack.getCount();
		int size = c.getContainerSize();
		for (int pass = 0; pass < 2 && left > 0; pass++) {
			for (int i = 0; i < size && left > 0; i++) {
				ItemStack in = c.getItem(i);
				boolean merge = pass == 0;
				if (merge ? in.isEmpty() || !ItemStack.isSameItemSameComponents(in, stack) : !in.isEmpty()) {
					continue;
				}
				if (!c.canPlaceItem(i, stack)) {
					continue;
				}
				int max = c.getMaxStackSize(stack);
				int room = max - (merge ? in.getCount() : 0);
				int n = Math.min(room, left);
				if (n <= 0) {
					continue;
				}
				if (merge) {
					in.grow(n);
					c.setItem(i, in);
				} else {
					c.setItem(i, stack.copyWithCount(n));
				}
				left -= n;
			}
		}
		if (left != stack.getCount()) {
			c.setChanged();
		}
		return stack.getCount() - left;
	}

	/** Takes up to {@code max} matching items out; the taken stacks are given to {@code sink} (may be null). */
	private static int extract(Container c, Predicate<ItemStack> match, int max, @Nullable List<ItemStack> sink) {
		int taken = 0;
		for (int i = 0; i < c.getContainerSize() && taken < max; i++) {
			ItemStack in = c.getItem(i);
			if (!match.test(in)) {
				continue;
			}
			int n = Math.min(in.getCount(), max - taken);
			ItemStack out = c.removeItem(i, n);
			taken += out.getCount();
			if (sink != null && !out.isEmpty()) {
				sink.add(out);
			}
		}
		if (taken > 0) {
			c.setChanged();
		}
		return taken;
	}

	private static int countIn(Container c, Predicate<ItemStack> match) {
		int n = 0;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (match.test(s)) {
				n += s.getCount();
			}
		}
		return n;
	}

	private static LuaTable listOf(Container c) {
		LuaTable t = new LuaTable();
		int n = 1;
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack s = c.getItem(i);
			if (!s.isEmpty()) {
				t.set(n++, stackTable(i + 1, s));
			}
		}
		return t;
	}

	private static @Nullable ServerPlayer player(String name) {
		return server().getPlayerList().getPlayerByName(name);
	}

	private static String runCommand(UUID owner, String cmd) {
		MinecraftServer server = server();
		List<String> out = new ArrayList<>();
		CommandSource collector = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				out.add(message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		NameAndId who = new NameAndId(owner, NetlinkServer.ownerName(owner));
		CommandSourceStack stack = server.createCommandSourceStack()
			.withSource(collector)
			.withPermission(server.getProfilePermissions(who));
		server.getCommands().performPrefixedCommand(stack, cmd.startsWith("/") ? cmd.substring(1) : cmd);
		return String.join("\n", out);
	}

	private static String dimArg(LuaValue dim) {
		return dim.isnil() ? "" : "execute in " + level(dim).dimension().identifier() + " run ";
	}

	private static String num(double d) {
		return String.format(Locale.ROOT, "%.4f", d);
	}

	private static <T extends Comparable<T>> String propertyValue(BlockState s, Property<T> p) {
		return p.getName(s.getValue(p));
	}

	private static <T extends Comparable<T>> @Nullable BlockState withProperty(BlockState s, Property<T> p, String value) {
		Optional<T> v = p.getValue(value);
		return v.map(x -> s.setValue(p, x)).orElse(null);
	}

	private static Objective objective(Scoreboard board, String name, boolean create) {
		Objective o = board.getObjective(name);
		if (o == null) {
			if (!create) {
				return null;
			}
			o = board.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(name), ObjectiveCriteria.RenderType.INTEGER, true, null);
		}
		return o;
	}

	private static LuaTable playerTable(ServerPlayer p) {
		LuaTable t = new LuaTable();
		t.set("Name", p.getName().getString());
		t.set("UUID", p.getStringUUID());
		t.set("X", p.getX());
		t.set("Y", p.getY());
		t.set("Z", p.getZ());
		t.set("Yaw", p.getYRot());
		t.set("Pitch", p.getXRot());
		t.set("Health", p.getHealth());
		t.set("MaxHealth", p.getMaxHealth());
		t.set("Food", p.getFoodData().getFoodLevel());
		t.set("Level", p.experienceLevel);
		t.set("Sneaking", LuaValue.valueOf(p.isShiftKeyDown()));
		t.set("Dimension", p.level().dimension().identifier().toString());
		t.set("GameMode", p.gameMode().getName());
		ItemStack held = p.getMainHandItem();
		t.set("HeldItem", held.isEmpty() ? LuaValue.NIL : LuaValue.valueOf(itemId(held)));
		t.set("HeldCount", held.getCount());
		return t;
	}

	// ---------------------------------------------------------------- the library

	static void install(ScriptRuntime rt) {
		LuaTable world = new LuaTable();

		// ---- commands and chat
		world.set("run", main(rt, a -> LuaValue.valueOf(runCommand(rt.owner, a.checkjstring(1)))));

		world.set("broadcast", main(rt, a -> {
			server().getPlayerList().broadcastSystemMessage(Component.literal(ScriptRuntime.concat(a)), false);
			return LuaValue.NONE;
		}));

		// ---- blocks
		world.set("getBlock", main(rt, a -> {
			ServerLevel level = level(a.arg(4));
			BlockPos p = pos(a, 1);
			return level.isLoaded(p) ? LuaValue.valueOf(describe(level.getBlockState(p))) : LuaValue.NIL;
		}));

		world.set("getBlockId", main(rt, a -> {
			ServerLevel level = level(a.arg(4));
			BlockPos p = pos(a, 1);
			return level.isLoaded(p) ? LuaValue.valueOf(blockId(level.getBlockState(p))) : LuaValue.NIL;
		}));

		world.set("isAir", main(rt, a -> {
			ServerLevel level = level(a.arg(4));
			BlockPos p = pos(a, 1);
			return LuaValue.valueOf(level.isLoaded(p) && level.getBlockState(p).isAir());
		}));

		world.set("isLoaded", main(rt, a -> LuaValue.valueOf(level(a.arg(4)).isLoaded(pos(a, 1)))));

		world.set("setBlock", main(rt, a -> {
			ServerLevel level = level(a.arg(5));
			BlockPos p = pos(a, 1);
			if (!level.isLoaded(p)) {
				return LuaValue.FALSE;
			}
			return LuaValue.valueOf(level.setBlock(p, parseBlock(level, a.checkjstring(4)), Block.UPDATE_ALL));
		}));

		world.set("breakBlock", main(rt, a -> {
			ServerLevel level = level(a.arg(5));
			BlockPos p = pos(a, 1);
			if (!level.isLoaded(p) || level.getBlockState(p).isAir()) {
				return LuaValue.FALSE;
			}
			return LuaValue.valueOf(level.destroyBlock(p, a.arg(4).isnil() || a.arg(4).toboolean()));
		}));

		world.set("getProperty", main(rt, a -> {
			ServerLevel level = level(a.arg(5));
			BlockPos p = pos(a, 1);
			if (!level.isLoaded(p)) {
				return LuaValue.NIL;
			}
			BlockState s = level.getBlockState(p);
			Property<?> prop = s.getBlock().getStateDefinition().getProperty(a.checkjstring(4));
			return prop == null ? LuaValue.NIL : LuaValue.valueOf(propertyValue(s, prop));
		}));

		world.set("setProperty", main(rt, a -> {
			ServerLevel level = level(a.arg(6));
			BlockPos p = pos(a, 1);
			if (!level.isLoaded(p)) {
				return LuaValue.FALSE;
			}
			BlockState s = level.getBlockState(p);
			Property<?> prop = s.getBlock().getStateDefinition().getProperty(a.checkjstring(4));
			if (prop == null) {
				throw new LuaError(blockId(s) + " has no property '" + a.checkjstring(4) + "'");
			}
			BlockState next = withProperty(s, prop, a.arg(5).tojstring());
			if (next == null) {
				throw new LuaError("bad value '" + a.arg(5).tojstring() + "' for " + prop.getName());
			}
			return LuaValue.valueOf(level.setBlock(p, next, Block.UPDATE_ALL));
		}));

		world.set("fill", main(rt, a -> {
			ServerLevel level = level(a.arg(8));
			BlockPos p1 = pos(a, 1), p2 = pos(a, 4);
			BlockState state = parseBlock(level, a.checkjstring(7));
			return LuaValue.valueOf(fill(level, p1, p2, state, null));
		}));

		world.set("replace", main(rt, a -> {
			ServerLevel level = level(a.arg(9));
			BlockPos p1 = pos(a, 1), p2 = pos(a, 4);
			String from = a.checkjstring(7);
			BlockState to = parseBlock(level, a.checkjstring(8));
			Predicate<BlockState> match;
			if (from.contains("[")) {
				String want = describe(parseBlock(level, from));
				match = s -> describe(s).equals(want);
			} else {
				Identifier fid = Identifier.tryParse(from);
				String want = fid == null ? from : fid.toString();
				match = s -> blockId(s).equals(want);
			}
			return LuaValue.valueOf(fill(level, p1, p2, to, match));
		}));

		world.set("getRedstone", main(rt, a -> {
			ServerLevel level = level(a.arg(4));
			BlockPos p = pos(a, 1);
			return LuaValue.valueOf(level.isLoaded(p) ? level.getBestNeighborSignal(p) : 0);
		}));

		world.set("getLight", main(rt, a -> {
			ServerLevel level = level(a.arg(4));
			BlockPos p = pos(a, 1);
			return LuaValue.valueOf(level.isLoaded(p) ? level.getMaxLocalRawBrightness(p) : 0);
		}));

		world.set("getSign", main(rt, a -> {
			ServerLevel level = level(a.arg(5));
			BlockPos p = pos(a, 1);
			if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof SignBlockEntity sign)) {
				return LuaValue.NIL;
			}
			SignText text = sign.getText(a.arg(4).isnil() || a.arg(4).toboolean());
			LuaTable t = new LuaTable();
			for (int i = 0; i < 4; i++) {
				t.set(i + 1, text.getMessage(i, false).getString());
			}
			return t;
		}));

		world.set("setSign", main(rt, a -> {
			ServerLevel level = level(a.arg(6));
			BlockPos p = pos(a, 1);
			if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof SignBlockEntity sign)) {
				return LuaValue.FALSE;
			}
			boolean front = a.arg(5).isnil() || a.arg(5).toboolean();
			SignText text = sign.getText(front);
			LuaValue lines = a.arg(4);
			for (int i = 0; i < 4; i++) {
				LuaValue v = lines.istable() ? lines.get(i + 1) : i == 0 ? lines : LuaValue.NIL;
				text = text.setMessage(i, Component.literal(v.isnil() ? "" : v.tojstring()));
			}
			sign.setText(text, front);
			return LuaValue.TRUE;
		}));

		// ---- inventories (chests, barrels, vaults, depots, anything with items)
		world.set("hasInventory", main(rt, a -> LuaValue.valueOf(container(level(a.arg(4)), pos(a, 1)) != null)));

		world.set("getInventory", main(rt, a -> {
			Container c = container(level(a.arg(4)), pos(a, 1));
			if (c == null) {
				return LuaValue.NIL;
			}
			return LuaValue.varargsOf(listOf(c), LuaValue.valueOf(c.getContainerSize()));
		}));

		world.set("getSlot", main(rt, a -> {
			Container c = needContainer(level(a.arg(5)), pos(a, 1));
			int slot = a.checkint(4) - 1;
			if (slot < 0 || slot >= c.getContainerSize() || c.getItem(slot).isEmpty()) {
				return LuaValue.NIL;
			}
			return stackTable(slot + 1, c.getItem(slot));
		}));

		world.set("setSlot", main(rt, a -> {
			Container c = needContainer(level(a.arg(7)), pos(a, 1));
			int slot = a.checkint(4) - 1;
			if (slot < 0 || slot >= c.getContainerSize()) {
				throw new LuaError("slot " + (slot + 1) + " is out of 1.." + c.getContainerSize());
			}
			if (a.arg(5).isnil() || a.arg(5).tojstring().isEmpty()) {
				c.setItem(slot, ItemStack.EMPTY);
			} else {
				Item it = item(a.checkjstring(5));
				c.setItem(slot, new ItemStack(it, Math.max(1, Math.min(count(a, 6, 1), 99))));
			}
			c.setChanged();
			return LuaValue.TRUE;
		}));

		world.set("countItem", main(rt, a -> {
			Container c = container(level(a.arg(5)), pos(a, 1));
			return LuaValue.valueOf(c == null ? 0 : countIn(c, matcher(a.arg(4))));
		}));

		world.set("addItem", main(rt, a -> {
			Container c = needContainer(level(a.arg(6)), pos(a, 1));
			Item it = item(a.checkjstring(4));
			int want = count(a, 5, 1);
			int added = 0;
			while (added < want) {
				int chunk = Math.min(want - added, new ItemStack(it).getMaxStackSize());
				int n = insert(c, new ItemStack(it, chunk));
				added += n;
				if (n < chunk) {
					break;
				}
			}
			return LuaValue.valueOf(added);
		}));

		world.set("removeItem", main(rt, a -> {
			Container c = needContainer(level(a.arg(6)), pos(a, 1));
			return LuaValue.valueOf(extract(c, matcher(a.arg(4)), count(a, 5, Integer.MAX_VALUE), null));
		}));

		world.set("clearInventory", main(rt, a -> {
			Container c = needContainer(level(a.arg(4)), pos(a, 1));
			return LuaValue.valueOf(extract(c, s -> !s.isEmpty(), Integer.MAX_VALUE, null));
		}));

		world.set("moveItems", main(rt, a -> {
			ServerLevel level = level(a.arg(9));
			Container from = needContainer(level, pos(a, 1));
			Container to = needContainer(level, pos(a, 4));
			Predicate<ItemStack> match = matcher(a.arg(7));
			int max = count(a, 8, Integer.MAX_VALUE);
			int moved = 0;
			for (int i = 0; i < from.getContainerSize() && moved < max; i++) {
				ItemStack in = from.getItem(i);
				if (!match.test(in)) {
					continue;
				}
				ItemStack probe = in.copyWithCount(Math.min(in.getCount(), max - moved));
				int n = insert(to, probe);
				if (n > 0) {
					from.removeItem(i, n);
					moved += n;
				}
			}
			if (moved > 0) {
				from.setChanged();
			}
			return LuaValue.valueOf(moved);
		}));

		// ---- players
		world.set("players", main(rt, a -> {
			LuaTable t = new LuaTable();
			int i = 1;
			for (ServerPlayer p : server().getPlayerList().getPlayers()) {
				t.set(i++, LuaValue.valueOf(p.getName().getString()));
			}
			return t;
		}));

		world.set("getPlayer", main(rt, a -> {
			ServerPlayer p = player(a.checkjstring(1));
			return p == null ? LuaValue.NIL : playerTable(p);
		}));

		world.set("playersNear", main(rt, a -> {
			ServerLevel level = level(a.arg(5));
			Vec3 c = new Vec3(a.checkdouble(1), a.checkdouble(2), a.checkdouble(3));
			double r = a.checkdouble(4);
			List<ServerPlayer> near = new ArrayList<>();
			for (ServerPlayer p : level.players()) {
				if (p.position().distanceTo(c) <= r) {
					near.add(p);
				}
			}
			near.sort(Comparator.comparingDouble(p -> p.position().distanceTo(c)));
			LuaTable t = new LuaTable();
			for (int i = 0; i < near.size(); i++) {
				t.set(i + 1, near.get(i).getName().getString());
			}
			return t;
		}));

		world.set("getPlayerInventory", main(rt, a -> {
			ServerPlayer p = player(a.checkjstring(1));
			return p == null ? LuaValue.NIL : listOf(p.getInventory());
		}));

		world.set("countPlayerItem", main(rt, a -> {
			ServerPlayer p = player(a.checkjstring(1));
			return LuaValue.valueOf(p == null ? 0 : countIn(p.getInventory(), matcher(a.arg(2))));
		}));

		world.set("give", main(rt, a -> {
			ServerPlayer p = player(a.checkjstring(1));
			if (p == null) {
				return LuaValue.valueOf(0);
			}
			Item it = item(a.checkjstring(2));
			int want = count(a, 3, 1);
			int left = want;
			while (left > 0) {
				int chunk = Math.min(left, new ItemStack(it).getMaxStackSize());
				ItemStack stack = new ItemStack(it, chunk);
				p.getInventory().add(stack);
				if (!stack.isEmpty()) {
					p.drop(stack, false);
				}
				left -= chunk;
			}
			p.containerMenu.broadcastChanges();
			return LuaValue.valueOf(want);
		}));

		world.set("take", main(rt, a -> {
			ServerPlayer p = player(a.checkjstring(1));
			if (p == null) {
				return LuaValue.valueOf(0);
			}
			int n = extract(p.getInventory(), matcher(a.arg(2)), count(a, 3, 1), null);
			p.containerMenu.broadcastChanges();
			return LuaValue.valueOf(n);
		}));

		world.set("teleport", main(rt, a -> {
			ServerPlayer p = player(a.checkjstring(1));
			if (p == null) {
				return LuaValue.FALSE;
			}
			ServerLevel level = a.arg(5).isnil() ? p.level() : level(a.arg(5));
			p.teleportTo(level, a.checkdouble(2), a.checkdouble(3), a.checkdouble(4), java.util.Set.of(), p.getYRot(), p.getXRot(), false);
			return LuaValue.TRUE;
		}));

		world.set("tell", main(rt, a -> {
			ServerPlayer p = player(a.checkjstring(1));
			if (p != null) {
				p.sendSystemMessage(Component.literal(ScriptRuntime.concat(a.subargs(2))));
			}
			return LuaValue.valueOf(p != null);
		}));

		world.set("actionbar", main(rt, a -> {
			ServerPlayer p = player(a.checkjstring(1));
			if (p != null) {
				p.sendOverlayMessage(Component.literal(ScriptRuntime.concat(a.subargs(2))));
			}
			return LuaValue.valueOf(p != null);
		}));

		world.set("title", main(rt, a -> {
			ServerPlayer p = player(a.checkjstring(1));
			if (p == null) {
				return LuaValue.FALSE;
			}
			int in = (int) Math.round(a.optdouble(4, 0.5) * 20), stay = (int) Math.round(a.optdouble(5, 3) * 20),
				out = (int) Math.round(a.optdouble(6, 1) * 20);
			p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
			if (!a.arg(3).isnil()) {
				p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(a.arg(3).tojstring())));
			}
			p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(a.arg(2).isnil() ? "" : a.arg(2).tojstring())));
			return LuaValue.TRUE;
		}));

		world.set("heal", main(rt, a -> {
			ServerPlayer p = player(a.checkjstring(1));
			if (p == null) {
				return LuaValue.FALSE;
			}
			if (a.arg(2).isnil()) {
				p.setHealth(p.getMaxHealth());
			} else {
				p.heal((float) a.checkdouble(2));
			}
			return LuaValue.TRUE;
		}));

		world.set("setGameMode", main(rt, a -> LuaValue.valueOf(runCommand(rt.owner,
			"gamemode " + a.checkjstring(2).toLowerCase(Locale.ROOT) + " " + a.checkjstring(1)))));

		world.set("effect", main(rt, a -> LuaValue.valueOf(runCommand(rt.owner, "effect give " + a.checkjstring(1) + " "
			+ a.checkjstring(2) + " " + (int) Math.max(1, a.optdouble(3, 10)) + " " + Math.max(0, a.optint(4, 1) - 1) + " true"))));

		world.set("clearEffects", main(rt, a -> LuaValue.valueOf(runCommand(rt.owner, "effect clear " + a.checkjstring(1)))));

		// ---- sounds and particles
		world.set("sound", main(rt, a -> {
			ServerLevel level = level(a.arg(7));
			Identifier id = Identifier.tryParse(a.checkjstring(4));
			if (id == null) {
				throw new LuaError("bad sound '" + a.checkjstring(4) + "'");
			}
			SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(SoundEvent.createVariableRangeEvent(id));
			level.playSound(null, a.checkdouble(1), a.checkdouble(2), a.checkdouble(3), sound, SoundSource.MASTER,
				(float) a.optdouble(5, 1), (float) Math.max(0.5, Math.min(2, a.optdouble(6, 1))));
			return LuaValue.NONE;
		}));

		world.set("note", main(rt, a -> {
			ServerLevel level = level(a.arg(7));
			String instrument = a.optjstring(4, "harp");
			Identifier id = Identifier.tryParse("minecraft:block.note_block." + instrument);
			SoundEvent sound = id == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
			if (sound == null) {
				throw new LuaError("unknown instrument '" + instrument + "'");
			}
			float pitch = (float) Math.pow(2, (Math.max(0, Math.min(24, a.optint(5, 12))) - 12) / 12.0);
			level.playSound(null, a.checkdouble(1), a.checkdouble(2), a.checkdouble(3), sound, SoundSource.MASTER,
				(float) a.optdouble(6, 1), pitch);
			return LuaValue.NONE;
		}));

		world.set("particle", main(rt, a -> {
			double spread = a.optdouble(6, 0.3);
			String cmd = dimArg(a.arg(8)) + "particle " + a.checkjstring(4) + " " + num(a.checkdouble(1)) + " " + num(a.checkdouble(2))
				+ " " + num(a.checkdouble(3)) + " " + num(spread) + " " + num(spread) + " " + num(spread) + " " + num(a.optdouble(7, 0.02))
				+ " " + Math.max(1, Math.min(1000, a.optint(5, 10))) + " force";
			return LuaValue.valueOf(runCommand(rt.owner, cmd));
		}));

		// ---- entities
		world.set("spawnItem", main(rt, a -> {
			ServerLevel level = level(a.arg(6));
			Item it = item(a.checkjstring(4));
			int left = count(a, 5, 1);
			while (left > 0) {
				int chunk = Math.min(left, new ItemStack(it).getMaxStackSize());
				ItemEntity e = new ItemEntity(level, a.checkdouble(1), a.checkdouble(2), a.checkdouble(3), new ItemStack(it, chunk));
				e.setDefaultPickUpDelay();
				level.addFreshEntity(e);
				left -= chunk;
			}
			return LuaValue.NONE;
		}));

		world.set("summon", main(rt, a -> {
			String cmd = dimArg(a.arg(6)) + "summon " + a.checkjstring(4) + " " + num(a.checkdouble(1)) + " " + num(a.checkdouble(2))
				+ " " + num(a.checkdouble(3)) + (a.arg(5).isnil() ? "" : " " + a.checkjstring(5));
			return LuaValue.valueOf(runCommand(rt.owner, cmd));
		}));

		world.set("lightning", main(rt, a -> LuaValue.valueOf(runCommand(rt.owner, dimArg(a.arg(4)) + "summon minecraft:lightning_bolt "
			+ num(a.checkdouble(1)) + " " + num(a.checkdouble(2)) + " " + num(a.checkdouble(3))))));

		world.set("explode", main(rt, a -> {
			ServerLevel level = level(a.arg(6));
			float power = (float) Math.max(0, Math.min(20, a.optdouble(4, 4)));
			boolean breaks = a.arg(5).isnil() || a.arg(5).toboolean();
			level.explode(null, a.checkdouble(1), a.checkdouble(2), a.checkdouble(3), power,
				breaks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
			return LuaValue.NONE;
		}));

		world.set("entitiesNear", main(rt, a -> {
			ServerLevel level = level(a.arg(6));
			List<Entity> list = near(level, a);
			LuaTable t = new LuaTable();
			int i = 1;
			for (Entity e : list) {
				LuaTable et = new LuaTable();
				et.set("Id", e.getId());
				et.set("Type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
				et.set("Name", e.getName().getString());
				et.set("X", e.getX());
				et.set("Y", e.getY());
				et.set("Z", e.getZ());
				et.set("IsPlayer", LuaValue.valueOf(e instanceof Player));
				if (e instanceof ItemEntity ie) {
					et.set("Item", itemId(ie.getItem()));
					et.set("Count", ie.getItem().getCount());
				}
				t.set(i++, et);
			}
			return t;
		}));

		world.set("removeEntities", main(rt, a -> {
			ServerLevel level = level(a.arg(6));
			int n = 0;
			for (Entity e : near(level, a)) {
				if (!(e instanceof Player)) {
					e.discard();
					n++;
				}
			}
			return LuaValue.valueOf(n);
		}));

		// ---- time and weather
		world.set("time", main(rt, a -> LuaValue.valueOf(server().overworld().getOverworldClockTime() % 24000)));
		world.set("day", main(rt, a -> LuaValue.valueOf(server().overworld().getOverworldClockTime() / 24000)));
		world.set("setTime", main(rt, a -> LuaValue.valueOf(runCommand(rt.owner, "time set " + (a.arg(1).isnumber()
			? String.valueOf(a.checkint(1)) : a.checkjstring(1))))));
		world.set("setWeather", main(rt, a -> LuaValue.valueOf(runCommand(rt.owner, "weather " + a.checkjstring(1)
			+ (a.arg(2).isnil() ? "" : " " + (int) Math.max(1, a.checkdouble(2)) + "s")))));

		// ---- scoreboard (money, points, anything per player)
		world.set("getScore", main(rt, a -> {
			Scoreboard board = server().getScoreboard();
			Objective o = objective(board, a.checkjstring(2), false);
			if (o == null) {
				return LuaValue.NIL;
			}
			ReadOnlyScoreInfo info = board.getPlayerScoreInfo(ScoreHolder.forNameOnly(a.checkjstring(1)), o);
			return info == null ? LuaValue.NIL : LuaValue.valueOf(info.value());
		}));

		world.set("setScore", main(rt, a -> {
			Scoreboard board = server().getScoreboard();
			Objective o = objective(board, a.checkjstring(2), true);
			board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(a.checkjstring(1)), o).set(a.checkint(3));
			return LuaValue.NONE;
		}));

		world.set("addScore", main(rt, a -> {
			Scoreboard board = server().getScoreboard();
			Objective o = objective(board, a.checkjstring(2), true);
			return LuaValue.valueOf(board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(a.checkjstring(1)), o).add(a.checkint(3)));
		}));

		rt.globals.set("world", world);
	}

	private static List<Entity> near(ServerLevel level, Varargs a) {
		Vec3 c = new Vec3(a.checkdouble(1), a.checkdouble(2), a.checkdouble(3));
		double r = Math.min(256, a.checkdouble(4));
		String type = a.arg(5).isnil() ? null : a.checkjstring(5);
		Identifier typeId = type == null ? null : Identifier.tryParse(type);
		List<Entity> list = new ArrayList<>(level.getEntities((Entity) null, new AABB(c, c).inflate(r),
			e -> e.isAlive() && e.position().distanceTo(c) <= r
				&& (typeId == null || BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).equals(typeId))));
		list.sort(Comparator.comparingDouble(e -> e.position().distanceTo(c)));
		return list;
	}

	private static int fill(ServerLevel level, BlockPos p1, BlockPos p2, BlockState state, @Nullable Predicate<BlockState> only) {
		int minX = Math.min(p1.getX(), p2.getX()), maxX = Math.max(p1.getX(), p2.getX());
		int minY = Math.min(p1.getY(), p2.getY()), maxY = Math.max(p1.getY(), p2.getY());
		int minZ = Math.min(p1.getZ(), p2.getZ()), maxZ = Math.max(p1.getZ(), p2.getZ());
		long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
		if (volume > MAX_FILL) {
			throw new LuaError("area too big: " + volume + " blocks (max " + MAX_FILL + ")");
		}
		int changed = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					p.set(x, y, z);
					if (!level.isLoaded(p)) {
						continue;
					}
					BlockState cur = level.getBlockState(p);
					if (cur == state || only != null && !only.test(cur)) {
						continue;
					}
					if (level.setBlock(p, state, Block.UPDATE_ALL)) {
						changed++;
					}
				}
			}
		}
		return changed;
	}
}
