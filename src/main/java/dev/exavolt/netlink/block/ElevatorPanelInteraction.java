package dev.exavolt.netlink.block;

import com.zurrtum.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.zurrtum.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

import java.util.function.BiConsumer;

/** Right-clicking an Elevator Panel on a contraption toggles keyboard focus on it (client side). */
public class ElevatorPanelInteraction extends MovingInteractionBehaviour {
	/** Client hook: (contraption entity, local pos of the panel). */
	public static BiConsumer<AbstractContraptionEntity, BlockPos> clientToggle = (e, p) -> {
	};

	@Override
	public boolean handlePlayerInteraction(Player player, InteractionHand hand, BlockPos localPos, AbstractContraptionEntity entity) {
		if (player.level().isClientSide() && hand == InteractionHand.MAIN_HAND) {
			clientToggle.accept(entity, localPos);
		}
		return true;
	}
}
