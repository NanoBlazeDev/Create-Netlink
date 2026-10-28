package dev.exavolt.netlink;

import com.mojang.serialization.Codec;
import dev.exavolt.netlink.block.AdvancedRedstoneLinkBlock;
import dev.exavolt.netlink.block.AdvancedRedstoneLinkBlockEntity;
import dev.exavolt.netlink.item.KeyboardItem;
import dev.exavolt.netlink.item.BugItem;
import dev.exavolt.netlink.item.KeyboardConfig;
import dev.exavolt.netlink.item.TerminalItem;
import dev.exavolt.netlink.item.NetlinkGlueItem;
import dev.exavolt.netlink.item.WallExtenderItem;
import dev.exavolt.netlink.block.WallExtenderBlock;
import dev.exavolt.netlink.block.WallExtenderBlockEntity;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.function.Function;

public final class NetlinkRegistry {
	private NetlinkRegistry() {
	}

	// ---- player modification: survives death, item loss and inventory wipes ----
	public static final AttachmentType<Boolean> IMPLANT = AttachmentRegistry.create(
		Netlink.id("terminal_implant"),
		builder -> builder.persistent(Codec.BOOL)
			.copyOnDeath()
			.syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.targetOnly())
	);

	/** Script-set movement tuning of an Advanced Elevator Pulley, synced so client and server move the cabin alike. */
	public static final AttachmentType<dev.exavolt.netlink.block.ElevatorTuning> ELEVATOR_TUNING = AttachmentRegistry.create(
		Netlink.id("elevator_tuning"),
		builder -> builder.persistent(dev.exavolt.netlink.block.ElevatorTuning.CODEC)
			.syncWith(dev.exavolt.netlink.block.ElevatorTuning.STREAM_CODEC, AttachmentSyncPredicate.all())
	);

	// ---- data components ----
	public static final DataComponentType<KeyboardConfig> KEYBOARD_CONFIG = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		Netlink.id("keyboard_config"),
		DataComponentType.<KeyboardConfig>builder()
			.persistent(KeyboardConfig.CODEC)
			.networkSynchronized(KeyboardConfig.STREAM_CODEC)
			.build()
	);

	// ---- blocks ----
	public static final AdvancedRedstoneLinkBlock ADVANCED_REDSTONE_LINK = block(
		"advanced_redstone_link",
		AdvancedRedstoneLinkBlock::new,
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_CYAN)
			.strength(1.0F, 6.0F)
			.sound(SoundType.NETHERITE_BLOCK)
			.noOcclusion()
			.forceSolidOn()
	);

	public static final BlockEntityType<AdvancedRedstoneLinkBlockEntity> ADVANCED_REDSTONE_LINK_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		Netlink.id("advanced_redstone_link"),
		FabricBlockEntityTypeBuilder.create(AdvancedRedstoneLinkBlockEntity::new, ADVANCED_REDSTONE_LINK).build()
	);

	public static final WallExtenderBlock WALL_EXTENDER = block(
		"wall_extender",
		WallExtenderBlock::new,
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(3.0F, 6.0F)
			.noLootTable()
			.pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
			.sound(SoundType.NETHERITE_BLOCK)
	);

	public static final dev.exavolt.netlink.block.ExtenderGhostBlock EXTENDER_GHOST = block(
		"extender_ghost",
		dev.exavolt.netlink.block.ExtenderGhostBlock::new,
		BlockBehaviour.Properties.of()
			.strength(-1.0F, 3600000.0F)
			.noLootTable()
			.noOcclusion()
			.noTerrainParticles()
			.isSuffocating((s, l, p) -> false)
			.isViewBlocking((s, l, p) -> false)
			.isValidSpawn((s, l, p, e) -> false)
			.pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
	);

	public static final BlockEntityType<WallExtenderBlockEntity> WALL_EXTENDER_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		Netlink.id("wall_extender"),
		FabricBlockEntityTypeBuilder.create(WallExtenderBlockEntity::new, WALL_EXTENDER).build()
	);

	public static final dev.exavolt.netlink.block.AdvancedElevatorPulleyBlock ADVANCED_ELEVATOR_PULLEY = block(
		"advanced_elevator_pulley",
		dev.exavolt.netlink.block.AdvancedElevatorPulleyBlock::new,
		BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.IRON_BLOCK).mapColor(MapColor.COLOR_CYAN).noOcclusion()
	);

	public static final dev.exavolt.netlink.block.ElevatorPanelBlock ELEVATOR_PANEL = block(
		"elevator_panel",
		dev.exavolt.netlink.block.ElevatorPanelBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(1.0F, 4.0F).sound(SoundType.NETHERITE_BLOCK)
			.noOcclusion().noCollision()
	);

	public static final BlockEntityType<dev.exavolt.netlink.block.ElevatorPanelBlockEntity> ELEVATOR_PANEL_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		Netlink.id("elevator_panel"),
		FabricBlockEntityTypeBuilder.create(dev.exavolt.netlink.block.ElevatorPanelBlockEntity::new, ELEVATOR_PANEL).build()
	);

	// ---- items ----
	public static final TerminalItem TERMINAL = item(
		"terminal", TerminalItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.RARE)
	);
	public static final BugItem BUG = item("bug", BugItem::new, new Item.Properties().stacksTo(16));
	public static final KeyboardItem KEYBOARD = item(
		"keyboard", KeyboardItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)
	);
	public static final NetlinkGlueItem NETLINK_GLUE = item(
		"netlink_glue", NetlinkGlueItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)
	);
	public static final WallExtenderItem WALL_EXTENDER_ITEM = item(
		"wall_extender", WallExtenderItem::new, new Item.Properties().stacksTo(16)
	);
	public static final BlockItem ADVANCED_ELEVATOR_PULLEY_ITEM = item(
		"advanced_elevator_pulley", props -> new BlockItem(ADVANCED_ELEVATOR_PULLEY, props), new Item.Properties().useBlockDescriptionPrefix()
	);
	public static final BlockItem ELEVATOR_PANEL_ITEM = item(
		"elevator_panel", props -> new BlockItem(ELEVATOR_PANEL, props), new Item.Properties().useBlockDescriptionPrefix()
	);
	public static final BlockItem ADVANCED_REDSTONE_LINK_ITEM = item(
		"advanced_redstone_link",
		props -> new BlockItem(ADVANCED_REDSTONE_LINK, props),
		new Item.Properties().useBlockDescriptionPrefix()
	);

	public static final CreativeModeTab TAB = Registry.register(
		BuiltInRegistries.CREATIVE_MODE_TAB,
		Netlink.id("main"),
		FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.createnetlink.main"))
			.icon(() -> new ItemStack(TERMINAL))
			.displayItems((params, output) -> {
				output.accept(TERMINAL);
				output.accept(ADVANCED_REDSTONE_LINK_ITEM);
				output.accept(KEYBOARD);
				output.accept(BUG);
				output.accept(NETLINK_GLUE);
				output.accept(WALL_EXTENDER_ITEM);
				output.accept(ADVANCED_ELEVATOR_PULLEY_ITEM);
				output.accept(ELEVATOR_PANEL_ITEM);
			})
			.build()
	);

	private static <T extends Block> T block(String name, Function<BlockBehaviour.Properties, T> factory, BlockBehaviour.Properties props) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Netlink.id(name));
		return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
	}

	private static <T extends Item> T item(String name, Function<Item.Properties, T> factory, Item.Properties props) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Netlink.id(name));
		T item = factory.apply(props.setId(key));
		if (item instanceof BlockItem blockItem) {
			blockItem.registerBlocks(Item.BY_BLOCK, item);
		}
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	public static void register() {
		// class loading triggers the static registrations above; then hook our blocks into Create
		com.zurrtum.create.AllBlockEntityTypes.ELEVATOR_PULLEY.addValidBlock(ADVANCED_ELEVATOR_PULLEY);
		com.zurrtum.create.AllInteractionBehaviours.register(new dev.exavolt.netlink.block.ElevatorPanelInteraction(), ELEVATOR_PANEL);
		// the panel hangs on a wall like a sign: contraptions must pick it up together with the wall it's on
		com.zurrtum.create.api.contraption.BlockMovementChecks.registerBrittleCheck(state ->
			state.is(ELEVATOR_PANEL) ? com.zurrtum.create.api.contraption.BlockMovementChecks.CheckResult.SUCCESS
				: com.zurrtum.create.api.contraption.BlockMovementChecks.CheckResult.PASS);
		com.zurrtum.create.api.contraption.BlockMovementChecks.registerMovementNecessaryCheck((state, world, pos) ->
			state.is(ELEVATOR_PANEL) ? com.zurrtum.create.api.contraption.BlockMovementChecks.CheckResult.SUCCESS
				: com.zurrtum.create.api.contraption.BlockMovementChecks.CheckResult.PASS);
		com.zurrtum.create.api.contraption.BlockMovementChecks.registerAttachedCheck((state, world, pos, direction) ->
			state.is(ELEVATOR_PANEL)
				? com.zurrtum.create.api.contraption.BlockMovementChecks.CheckResult.of(
					direction == state.getValue(dev.exavolt.netlink.block.ElevatorPanelBlock.FACING).getOpposite())
				: com.zurrtum.create.api.contraption.BlockMovementChecks.CheckResult.PASS);
	}
}
