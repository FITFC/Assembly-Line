package assemblyline.common.inventory.container;

import assemblyline.DeferredRegisters;
import assemblyline.common.packet.NetworkHandler;
import assemblyline.common.packet.types.PacketFarmer;
import assemblyline.common.tile.TileFarmer;
import electrodynamics.common.item.subtype.SubtypeItemUpgrade;
import electrodynamics.prefab.inventory.container.GenericContainerBlockEntity;
import electrodynamics.prefab.inventory.container.slot.item.SlotGeneric;
import electrodynamics.prefab.inventory.container.slot.item.type.SlotRestricted;
import electrodynamics.prefab.inventory.container.slot.item.type.SlotUpgrade;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.BoneMealItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public class ContainerFarmer extends GenericContainerBlockEntity<TileFarmer> {

	public static final SubtypeItemUpgrade[] VALID_UPGRADES = new SubtypeItemUpgrade[] { SubtypeItemUpgrade.advancedspeed, SubtypeItemUpgrade.basicspeed, SubtypeItemUpgrade.itemoutput, SubtypeItemUpgrade.range };

	public ContainerFarmer(int id, Inventory playerinv) {
		this(id, playerinv, new SimpleContainer(22), new SimpleContainerData(3));
	}

	public ContainerFarmer(int id, Inventory playerinv, Container inventory, ContainerData inventorydata) {
		super(DeferredRegisters.CONTAINER_FARMER.get(), id, playerinv, inventory, inventorydata);
	}

	@Override
	public void addInventorySlots(Container inv, Inventory playerinv) {
		playerInvOffset = 58;
		for (int i = 0; i < 3; ++i) {
			for (int j = 0; j < 3; ++j) {
				addSlot(new SlotGeneric(inv, nextIndex(), 85 + j * 18, 17 + i * 18));
			}
		}
		addSlot(new SlotRestricted(inv, nextIndex(), 153, 17, BoneMealItem.class));
		for (int i = 0; i < 3; ++i) {
			for (int j = 0; j < 3; ++j) {
				addSlot(new SlotRestricted(inv, nextIndex(), 85 + j * 18, 75 + i * 18));
			}
		}
		addSlot(new SlotUpgrade(inv, nextIndex(), 153, 71, VALID_UPGRADES));
		addSlot(new SlotUpgrade(inv, nextIndex(), 153, 91, VALID_UPGRADES));
		addSlot(new SlotUpgrade(inv, nextIndex(), 153, 111, VALID_UPGRADES));
	}

	@OnlyIn(Dist.CLIENT)
	public void toggleBoolean(int num) {
		if (getHostFromIntArray() != null) {
			NetworkHandler.CHANNEL.sendToServer(new PacketFarmer(num, getHostFromIntArray().getBlockPos()));
		}
	}

}
