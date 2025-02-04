package assemblyline.common.tile;

import java.util.ArrayList;

import com.mojang.math.Vector3f;

import assemblyline.DeferredRegisters;
import assemblyline.common.settings.Constants;
import electrodynamics.prefab.tile.GenericTile;
import electrodynamics.prefab.tile.components.ComponentType;
import electrodynamics.prefab.tile.components.type.ComponentDirection;
import electrodynamics.prefab.tile.components.type.ComponentElectrodynamic;
import electrodynamics.prefab.tile.components.type.ComponentInventory;
import electrodynamics.prefab.tile.components.type.ComponentPacketHandler;
import electrodynamics.prefab.tile.components.type.ComponentTickable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

public class TileConveyorBelt extends GenericTile {
	public int currentSpread = 0;
	public int wait = 0;
	public boolean running;
	public ConveyorType conveyorType = ConveyorType.Horizontal;
	public ArrayList<TileConveyorBelt> inQueue = new ArrayList<>();
	public boolean isQueueReady = false;
	public boolean waiting = false;
	public boolean isPusher = false;
	public boolean isPuller = false;
	public ConveyorObject object = new ConveyorObject();

	public TileConveyorBelt(BlockPos worldPosition, BlockState blockState) {
		super(DeferredRegisters.TILE_BELT.get(), worldPosition, blockState);
		addComponent(new ComponentTickable().tickCommon(this::tickCommon));
		addComponent(new ComponentDirection());
		addComponent(new ComponentPacketHandler().guiPacketReader(this::loadFromNBT).guiPacketWriter(this::saveToNBT));
		addComponent(new ComponentInventory(this).size(1));
		addComponent(new ComponentElectrodynamic(this).input(Direction.DOWN).relativeInput(Direction.EAST).relativeInput(Direction.WEST).maxJoules(Constants.CONVEYORBELT_USAGE * 100));
	}

	protected void sync() {
		this.<ComponentPacketHandler>getComponent(ComponentType.PacketHandler).sendGuiPacketToTracking();
	}

	public ItemStack getStackOnBelt() {
		return this.<ComponentInventory>getComponent(ComponentType.Inventory).getItem(0);
	}

	public ItemStack addItemOnBelt(ItemStack add) {
		ItemStack onBelt = getStackOnBelt();
		if (onBelt.isEmpty()) {
			object.pos = new Vector3f(0.5f + worldPosition.getX(), worldPosition.getY() + (conveyorType == ConveyorType.SlopedDown ? -4.0f / 16.0f : conveyorType == ConveyorType.SlopedUp ? 8.0f / 16.0f : 0), 0.5f + worldPosition.getZ());
		}
		if (!add.isEmpty()) {
			ComponentInventory inventory = getComponent(ComponentType.Inventory);
			ItemStack returner = new InvWrapper(inventory).insertItem(0, add, false);
			if (returner.getCount() != add.getCount()) {
				this.<ComponentPacketHandler>getComponent(ComponentType.PacketHandler).sendGuiPacketToTracking();
				return returner;
			}
		}
		return add;
	}

	public ItemStack addItemOnBelt(ItemStack add, ConveyorObject object) {
		if (!add.isEmpty()) {
			ComponentInventory inventory = getComponent(ComponentType.Inventory);
			ItemStack returner = new InvWrapper(inventory).insertItem(0, add, false);
			this.object.pos = object.pos.copy();
			if (conveyorType == ConveyorType.Vertical) {
				this.object.pos.sub(getDirectionAsVector());
			}
			if (returner.getCount() != add.getCount()) {
				this.<ComponentPacketHandler>getComponent(ComponentType.PacketHandler).sendGuiPacketToTracking();
				return returner;
			}
		}
		return add;
	}

	protected void tickCommon(ComponentTickable tickable) {
		ComponentElectrodynamic electro = getComponent(ComponentType.Electrodynamic);
		ItemStack stackOnBelt = getStackOnBelt();
		isQueueReady = stackOnBelt.isEmpty();
		running = currentSpread > 0 && isQueueReady;
		BlockEntity nextBlockEntity = getNextEntity();
		Direction direction = this.<ComponentDirection>getComponent(ComponentType.Direction).getDirection();
		isPusher = false;
		if (nextBlockEntity != null && !(nextBlockEntity instanceof TileConveyorBelt)) {
			LazyOptional<IItemHandler> handlerOptional = nextBlockEntity.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, direction);
			isPusher = handlerOptional.isPresent();
		}
		if (currentSpread > 0) {
			attemptMove();
		}
		BlockEntity lastBlockEntity = level.getBlockEntity(worldPosition.offset(direction.getNormal()));
		isPuller = false;
		if (lastBlockEntity != null && !(lastBlockEntity instanceof TileConveyorBelt)) {
			LazyOptional<IItemHandler> handlerOptional = lastBlockEntity.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, direction);
			isPuller = handlerOptional.isPresent();
		}
		if (isQueueReady) {
			if (!inQueue.isEmpty()) {
				while (true) {
					TileConveyorBelt queue = inQueue.get(0);
					if (!queue.isRemoved() && queue.waiting) {
						break;
					}
					inQueue.remove(0);
					if (inQueue.isEmpty()) {
						break;
					}
				}
			} else if (lastBlockEntity != null && isPuller) {
				LazyOptional<IItemHandler> cap = lastBlockEntity.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, direction.getOpposite());
				if (cap.isPresent()) {
					IItemHandler handler = cap.resolve().get();
					for (int slot = 0; slot < handler.getSlots(); slot++) {
						ItemStack returned = handler.extractItem(slot, 64, false);
						if (!returned.isEmpty()) {
							addItemOnBelt(returned);
						}
					}
				}
			}
		}
		if (!level.isClientSide) {
			checkForSpread();
			if (currentSpread == 0 || currentSpread == 16) {
				if (electro.getJoulesStored() < Constants.CONVEYORBELT_USAGE) {
					currentSpread = 0;
				} else {
					electro.joules(electro.getJoulesStored() - Constants.CONVEYORBELT_USAGE);
					currentSpread = 16;
				}
				sync();
			}
			if (level.getDayTime() % 20 == 0) {
				sync();
			}
		}
	}

	public Vector3f getObjectLocal() {
		return new Vector3f(object.pos.x() - worldPosition.getX(), object.pos.y() - worldPosition.getY(), object.pos.z() - worldPosition.getZ());
	}

	public Vector3f getDirectionAsVector() {
		Direction direction = this.<ComponentDirection>getComponent(ComponentType.Direction).getDirection().getOpposite();
		return new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ());
	}

	public boolean shouldTransfer() {
		TileConveyorBelt belt = getNextConveyor();
		BlockPos pos = new BlockPos(Math.floor(object.pos.x()), getBlockPos().getY(), Math.floor(object.pos.z()));
		Vector3f local = getObjectLocal();
		Vector3f direction = getDirectionAsVector();
		float coordComponent = local.dot(direction);
		if (conveyorType != ConveyorType.Horizontal) {
			return conveyorType == ConveyorType.SlopedDown ? object.pos.y() <= worldPosition.getY() - 1 : object.pos.y() >= worldPosition.getY() + 1;
		}
		float value = belt != null && (belt.inQueue.isEmpty() || belt.inQueue.get(0) == this) && belt.isQueueReady ? belt.conveyorType == ConveyorType.SlopedUp || conveyorType == ConveyorType.Vertical ? 1 : 1.25f : 1;
		if (direction.x() + direction.y() + direction.z() > 0) {
			return !pos.equals(worldPosition) && coordComponent >= value;
		}
		return !pos.equals(worldPosition) && coordComponent >= value - 1;
	}

	public void attemptMove() {
		Vector3f move = getDirectionAsVector();
		ItemStack stackOnBelt = getStackOnBelt();
		if (!stackOnBelt.isEmpty()) {
			boolean shouldTransfer = shouldTransfer();
			BlockEntity nextBlockEntity = getNextEntity();
			if (nextBlockEntity instanceof TileConveyorBelt belt) {
				if (shouldTransfer) {
					if (!belt.inQueue.contains(this)) {
						belt.inQueue.add(this);
					}
					if (belt.inQueue.get(0) == this && belt.isQueueReady) {
						waiting = false;
						belt.inQueue.remove(0);
						belt.addItemOnBelt(getStackOnBelt(), object);
						this.<ComponentInventory>getComponent(ComponentType.Inventory).setItem(0, ItemStack.EMPTY);
					} else {
						waiting = true;
					}
				}
			} else if (nextBlockEntity != null) {
				if (shouldTransfer) {
					Direction direction = this.<ComponentDirection>getComponent(ComponentType.Direction).getDirection();
					LazyOptional<IItemHandler> handlerOptional = nextBlockEntity.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, direction);
					ComponentInventory inventory = getComponent(ComponentType.Inventory);
					if (handlerOptional.isPresent()) {
						if (wait == 0) {
							if (putItemsIntoInventory(handlerOptional, inventory) == 0) {
								wait = 20;
							}
						} else {
							wait--;
						}
					} else {
						dropItem(stackOnBelt, move);
						this.<ComponentInventory>getComponent(ComponentType.Inventory).setItem(0, ItemStack.EMPTY);
					}
				}
			} else {
				dropItem(stackOnBelt, move);
				this.<ComponentInventory>getComponent(ComponentType.Inventory).setItem(0, ItemStack.EMPTY);
			}
			if (!shouldTransfer) {
				move.mul(1 / 16.0f);
				object.pos.add(move);
				if (conveyorType != ConveyorType.Horizontal) {
					object.pos.add(0, 1 / 16.0f * (conveyorType == ConveyorType.SlopedDown ? -1 : 1), 0);
				}
			}
		}
		isQueueReady = stackOnBelt.isEmpty();
		running = currentSpread > 0 && (!waiting || isQueueReady);
	}

	public void dropItem(ItemStack stackOnBelt, Vector3f move) {
		ItemEntity entity = new ItemEntity(level, worldPosition.getX() + 0.5 + move.x() / 2.0f, worldPosition.getY() + 0.4, worldPosition.getZ() + 0.5 + move.z() / 2.0f, stackOnBelt);
		entity.setDeltaMovement(move.x() / 12.0, 1.5 / 16.0, move.z() / 12.0);
		entity.setPickUpDelay(20);
		level.addFreshEntity(entity);
	}

	private static int putItemsIntoInventory(LazyOptional<IItemHandler> handlerOptional, ComponentInventory inventory) {
		IItemHandler handler = handlerOptional.resolve().get();
		int amount = 0;
		for (int indexHere = 0; indexHere < inventory.getContainerSize(); indexHere++) {
			ItemStack stackHere = inventory.getItem(indexHere);
			if (!stackHere.isEmpty()) {
				for (int indexThere = 0; indexThere < handler.getSlots(); indexThere++) {
					ItemStack set = handler.insertItem(indexThere, stackHere, false);
					amount += stackHere.getCount() - set.getCount();
					inventory.setItem(indexHere, set);
					stackHere = set;
					if (inventory.getItem(indexHere).isEmpty()) {
						break;
					}
				}
			}
		}
		return amount;
	}

	public BlockPos getNextPos() {
		Direction direction = this.<ComponentDirection>getComponent(ComponentType.Direction).getDirection().getOpposite();
		return switch (conveyorType) {
		case Horizontal -> worldPosition.relative(direction);
		case SlopedDown -> worldPosition.relative(direction).below();
		case SlopedUp -> worldPosition.relative(direction).above();
		case Vertical -> level.getBlockEntity(worldPosition.relative(Direction.UP)) instanceof TileConveyorBelt belt && belt.conveyorType == ConveyorType.Vertical ? worldPosition.relative(Direction.UP) : worldPosition.relative(direction).above();
		default -> null;
		};
	}

	protected BlockEntity getNextEntity() {
		BlockEntity nextBlockEntity = null;
		BlockPos nextBlockPos = getNextPos();
		if (nextBlockPos != null) {
			nextBlockEntity = level.getBlockEntity(nextBlockPos);
		}
		return nextBlockEntity;
	}

	protected TileConveyorBelt getNextConveyor() {
		return getNextEntity() instanceof TileConveyorBelt belt ? belt : null;
	}

	protected void loadFromNBT(CompoundTag nbt) {
		NonNullList<ItemStack> obj = this.<ComponentInventory>getComponent(ComponentType.Inventory).getItems();
		obj.clear();
		ContainerHelper.loadAllItems(nbt, obj);
		currentSpread = nbt.getInt("currentSpread");
		running = nbt.getBoolean("running");
		conveyorType = ConveyorType.values()[nbt.getInt("conveyorType")];
		isQueueReady = nbt.getBoolean("isQueueReady");
		waiting = nbt.getBoolean("waiting");
		object.pos = new Vector3f(nbt.getFloat("convX"), nbt.getFloat("convY"), nbt.getFloat("convZ"));
	}

	@Override
	public void load(CompoundTag nbt) {
		super.load(nbt);
		conveyorType = ConveyorType.values()[nbt.getInt("conveyorType")];
		object.pos = new Vector3f(nbt.getFloat("convX"), nbt.getFloat("convY"), nbt.getFloat("convZ"));
	}

	@Override
	public AABB getRenderBoundingBox() {
		return super.getRenderBoundingBox().inflate(3);
	}

	@Override
	public void saveAdditional(CompoundTag compound) {
		compound.putInt("conveyorType", conveyorType.ordinal());
		compound.putFloat("convX", object.pos.x());
		compound.putFloat("convY", object.pos.y());
		compound.putFloat("convZ", object.pos.z());
		super.saveAdditional(compound);
	}

	protected void saveToNBT(CompoundTag nbt) {
		ContainerHelper.saveAllItems(nbt, this.<ComponentInventory>getComponent(ComponentType.Inventory).getItems());
		nbt.putInt("currentSpread", currentSpread);
		nbt.putBoolean("running", running);
		nbt.putInt("conveyorType", conveyorType.ordinal());
		nbt.putBoolean("isQueueReady", isQueueReady);
		nbt.putBoolean("waiting", waiting);
		nbt.putFloat("convX", object.pos.x());
		nbt.putFloat("convY", object.pos.y());
		nbt.putFloat("convZ", object.pos.z());
	}

	public void checkForSpread() {
		int lastMax = currentSpread;
		int max = 0;
		for (BlockPos po : TileConveyorBelt.offsets) {
			BlockEntity at = level.getBlockEntity(worldPosition.offset(po));
			if (at instanceof TileConveyorBelt belt) {
				int their = belt.currentSpread;
				if (their - 1 > max) {
					max = their - 1;
				}
			} else if (at instanceof TileSorterBelt sbelt) {
				int their = sbelt.currentSpread;
				if (their - 1 > max) {
					max = their - 1;
				}
			}
		}
		currentSpread = max;
		if (lastMax > currentSpread) {
			currentSpread = 0;
		}
	}

	public static ArrayList<BlockPos> offsets = new ArrayList<>();
	static {
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.EAST));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.WEST));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.NORTH));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.SOUTH));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.EAST));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.WEST));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.NORTH));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.SOUTH));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.DOWN).relative(Direction.EAST));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.DOWN).relative(Direction.WEST));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.DOWN).relative(Direction.NORTH));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.DOWN).relative(Direction.SOUTH));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.UP).relative(Direction.EAST));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.UP).relative(Direction.WEST));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.UP).relative(Direction.NORTH));
		offsets.add(new BlockPos(0, 0, 0).relative(Direction.UP).relative(Direction.SOUTH));
	}

	public enum ConveyorType {
		Horizontal,
		SlopedUp,
		SlopedDown,
		Vertical;
	}
}
