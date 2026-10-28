package dev.exavolt.netlinkdecor;

import dev.exavolt.netlinkdecor.block.AntennaMastBlock;
import dev.exavolt.netlinkdecor.block.CableDuctBlock;
import dev.exavolt.netlinkdecor.block.LiveBlockEntity;
import dev.exavolt.netlinkdecor.block.NeonBlock;
import dev.exavolt.netlinkdecor.block.NeonPanelBlock;
import dev.exavolt.netlinkdecor.block.PropBlock;
import dev.exavolt.netlinkdecor.item.ChiselSettings;
import dev.exavolt.netlinkdecor.item.VoxelChiselItem;
import dev.exavolt.netlinkdecor.voxel.VoxelBlock;
import dev.exavolt.netlinkdecor.voxel.VoxelBlockEntity;
import dev.exavolt.netlinkdecor.voxel.VoxelData;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class DecorRegistry {
	private DecorRegistry() {
	}

	// ---- data components ----
	public static final DataComponentType<ChiselSettings> CHISEL_SETTINGS = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE, NetlinkDecor.id("chisel_settings"),
		DataComponentType.<ChiselSettings>builder().persistent(ChiselSettings.CODEC).networkSynchronized(ChiselSettings.STREAM_CODEC).build()
	);
	public static final DataComponentType<VoxelData> VOXEL_DATA = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE, NetlinkDecor.id("voxel_data"),
		DataComponentType.<VoxelData>builder().persistent(VoxelData.CODEC).networkSynchronized(VoxelData.STREAM_CODEC).build()
	);

	// ---- neon ----
	public static final Map<DyeColor, NeonBlock> NEON = new EnumMap<>(DyeColor.class);
	public static final Map<DyeColor, NeonPanelBlock> NEON_PANEL = new EnumMap<>(DyeColor.class);

	static {
		for (DyeColor c : DyeColor.values()) {
			NEON.put(c, block("neon_" + c.getName(), p -> new NeonBlock(c, p), BlockBehaviour.Properties.of()
				.mapColor(c.getMapColor()).strength(0.6F).sound(SoundType.GLASS).lightLevel(s -> 15)));
			NEON_PANEL.put(c, block("neon_panel_" + c.getName(), p -> new NeonPanelBlock(c, p), BlockBehaviour.Properties.of()
				.mapColor(c.getMapColor()).strength(0.4F).sound(SoundType.GLASS).lightLevel(s -> 15).noOcclusion()));
		}
	}

	// ---- wireless props ----
	private static BlockBehaviour.Properties metal() {
		return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0F, 6.0F).sound(SoundType.NETHERITE_BLOCK).noOcclusion();
	}

	private static PropBlock prop(String name, VoxelShape north, boolean live, boolean wall) {
		return block(name, p -> new PropBlock(p, north, live, wall), metal());
	}

	public static final PropBlock SERVER_RACK = prop("server_rack", Block.box(0, 0, 0, 16, 16, 16), true, false);
	public static final PropBlock NETWORK_SWITCH = prop("network_switch", Block.box(1, 0, 2, 15, 4, 15), true, false);
	public static final PropBlock ROUTER = prop("router", Block.box(3, 0, 4, 13, 3, 12), true, false);
	public static final PropBlock SATELLITE_DISH = prop("satellite_dish", Block.box(2, 0, 2, 14, 15, 14), true, false);
	public static final PropBlock STATUS_MONITOR = prop("status_monitor", Block.box(1, 0, 6, 15, 13, 10), true, false);
	public static final PropBlock PATCH_PANEL = prop("patch_panel", Block.box(0, 2, 13, 16, 14, 16), true, true);
	public static final AntennaMastBlock ANTENNA_MAST = block("antenna_mast",
		p -> new AntennaMastBlock(p, Block.box(4, 0, 4, 12, 16, 12)), metal());
	public static final CableDuctBlock CABLE_DUCT = block("cable_duct", CableDuctBlock::new, metal());

	public static final PropBlock[] LIVE_PROPS = {SERVER_RACK, NETWORK_SWITCH, ROUTER, SATELLITE_DISH, STATUS_MONITOR, PATCH_PANEL, ANTENNA_MAST};

	public static final BlockEntityType<LiveBlockEntity> LIVE_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, NetlinkDecor.id("live_prop"),
		FabricBlockEntityTypeBuilder.create(LiveBlockEntity::new, LIVE_PROPS).build()
	);

	// ---- voxels ----
	public static final VoxelBlock VOXEL_BLOCK = block("voxel_block", VoxelBlock::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.STONE).strength(1.0F, 6.0F).noOcclusion().noLootTable().dynamicShape()
		.lightLevel(s -> s.getValue(VoxelBlock.LIGHT)));

	public static final BlockEntityType<VoxelBlockEntity> VOXEL_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, NetlinkDecor.id("voxel_block"),
		FabricBlockEntityTypeBuilder.create(VoxelBlockEntity::new, VOXEL_BLOCK).build()
	);

	// ---- items ----
	public static final List<Item> BLOCK_ITEMS = new ArrayList<>();

	static {
		for (DyeColor c : DyeColor.values()) {
			blockItem(NEON.get(c));
		}
		for (DyeColor c : DyeColor.values()) {
			blockItem(NEON_PANEL.get(c));
		}
		for (Block b : new Block[]{SERVER_RACK, NETWORK_SWITCH, ROUTER, SATELLITE_DISH, ANTENNA_MAST, STATUS_MONITOR, PATCH_PANEL, CABLE_DUCT}) {
			blockItem(b);
		}
	}

	public static final VoxelChiselItem VOXEL_CHISEL = item("voxel_chisel", VoxelChiselItem::new,
		new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));
	public static final BlockItem VOXEL_MODEL = item("voxel_block", p -> new BlockItem(VOXEL_BLOCK, p),
		new Item.Properties().useBlockDescriptionPrefix());

	public static final CreativeModeTab TAB = Registry.register(
		BuiltInRegistries.CREATIVE_MODE_TAB, NetlinkDecor.id("main"),
		FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.netlinkdecor.main"))
			.icon(() -> new ItemStack(SERVER_RACK))
			.displayItems((params, output) -> {
				output.accept(VOXEL_CHISEL);
				for (Item i : BLOCK_ITEMS) {
					output.accept(i);
				}
			})
			.build()
	);

	private static void blockItem(Block b) {
		String name = BuiltInRegistries.BLOCK.getKey(b).getPath();
		BLOCK_ITEMS.add(item(name, p -> new BlockItem(b, p), new Item.Properties().useBlockDescriptionPrefix()));
	}

	private static <T extends Block> T block(String name, Function<BlockBehaviour.Properties, T> factory, BlockBehaviour.Properties props) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, NetlinkDecor.id(name));
		return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
	}

	private static <T extends Item> T item(String name, Function<Item.Properties, T> factory, Item.Properties props) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, NetlinkDecor.id(name));
		T item = factory.apply(props.setId(key));
		if (item instanceof BlockItem blockItem) {
			blockItem.registerBlocks(Item.BY_BLOCK, item);
		}
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	public static void register() {
		// class loading runs the registrations above
	}
}
