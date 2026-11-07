package com.dantemorrigan.partner;

import com.dantemorrigan.partner.ai.Appraisal;
import com.dantemorrigan.partner.ai.Gear;
import com.dantemorrigan.partner.ai.PartnerBrain;
import com.dantemorrigan.partner.compat.InfinityCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The partner's body: player-like stats (20 health, 20 food, fist damage 1, walking and sprinting at player speed),
 * a 27-slot backpack plus armor and hands, hunger and natural regeneration, and the rules that keep it friendly.
 * All decisions live in {@link PartnerBrain}.
 */
public class PartnerEntity extends PathfinderMob implements OwnableEntity {
	private static final EntityDataAccessor<Integer> DATA_SKIN = SynchedEntityData.defineId(PartnerEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> DATA_ACTIVITY = SynchedEntityData.defineId(PartnerEntity.class, EntityDataSerializers.STRING);
	public static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};

	/** Speeds as navigation modifiers on top of {@link #BASE_SPEED}: mob movement scales with speed squared. */
	public static final double BASE_SPEED = 0.32, WALK = 1.0, SPRINT = 1.13, CATCH_UP = 1.25;

	private final SimpleContainer backpack = new SimpleContainer(27);
	private final PartnerBrain brain = new PartnerBrain(this);
	private @Nullable UUID owner;
	private PartnerOrder order = PartnerOrder.FOLLOW;
	private @Nullable BlockPos anchor;
	private int food = 20;
	private float saturation = 5f, exhaustion;
	private int foodTick, eating;
	private float lastMoveDist;
	private ItemStack eatingStack = ItemStack.EMPTY;
	private boolean squadMember;
	private String squadRole = "";
	private String squadMission = "";

	public PartnerEntity(EntityType<? extends PartnerEntity> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
		this.setCanPickUpLoot(false); // we pick up into the backpack ourselves
		if (this.getNavigation() instanceof GroundPathNavigation nav) {
			nav.setCanOpenDoors(true);
			nav.setCanFloat(true);
		}
		this.setPathfindingMalus(PathType.WATER, 0.8f);
		this.setPathfindingMalus(PathType.FIRE_IN_NEIGHBOR, 16.0f);
		this.setPathfindingMalus(PathType.FIRE, -1.0f);
		this.setPathfindingMalus(PathType.LAVA, -1.0f);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, BASE_SPEED)
				.add(Attributes.WATER_MOVEMENT_EFFICIENCY, 0.9)
				.add(Attributes.ATTACK_DAMAGE, 1.0)
				.add(Attributes.ATTACK_SPEED, 4.0)
				.add(Attributes.FOLLOW_RANGE, 32.0)
				.add(Attributes.STEP_HEIGHT, 0.6)
				.add(Attributes.ENTITY_INTERACTION_RANGE, 3.0)
				.add(Attributes.BLOCK_INTERACTION_RANGE, 4.5);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder data) {
		super.defineSynchedData(data);
		data.define(DATA_SKIN, 0);
		data.define(DATA_ACTIVITY, "");
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
	}

	/** First summon: owner, name, a skin of its own and a few starting snacks like a fresh player has none. */
	public void setupNew(ServerPlayer player) {
		this.owner = player.getUUID();
		this.setCustomName(Component.translatable("entity.local_partner.partner.name"));
		this.setCustomNameVisible(true);
		this.entityData.set(DATA_SKIN, this.random.nextInt(SKINS.length));
		this.order = PartnerOrder.FOLLOW;
	}

	/** Configures this entity as an Expedition Squad buddy with a distinct role, skin, and mission. */
	public void setupSquadMember(ServerPlayer player, int skinIdx, String roleKey, String missionId) {
		this.owner = player.getUUID();
		this.squadMember = true;
		this.squadRole = roleKey;
		this.squadMission = missionId;
		this.setCustomName(Component.translatable("entity.local_partner.squad." + roleKey));
		this.setCustomNameVisible(true);
		this.entityData.set(DATA_SKIN, Math.floorMod(skinIdx, SKINS.length));
		this.order = PartnerOrder.FREE;
	}

	public boolean isSquadMember() {
		return squadMember;
	}

	public String squadRole() {
		return squadRole;
	}

	public String squadMission() {
		return squadMission;
	}

	public void setSquadMission(String missionId) {
		this.squadMission = missionId;
	}

	// ------------------------------------------------------------------ ownership

	@Override
	public @Nullable EntityReference<LivingEntity> getOwnerReference() {
		return owner == null ? null : EntityReference.of(owner);
	}

	public @Nullable UUID getOwnerUUID() {
		return owner;
	}

	public @Nullable ServerPlayer getOwnerPlayer() {
		if (owner == null || !(this.level() instanceof ServerLevel level)) return null;
		return level.getServer().getPlayerList().getPlayer(owner);
	}

	public boolean isOwner(Player player) {
		return owner != null && owner.equals(player.getUUID());
	}

	// ------------------------------------------------------------------ state

	public PartnerOrder order() {
		return order;
	}

	public @Nullable BlockPos anchor() {
		return anchor;
	}

	/**
	 * The owner said "follow me" on purpose: stay with them and do nothing else (still fights for both and brings
	 * food). Without it (the default after summoning, or after a finished task) the partner may develop itself nearby.
	 */
	private boolean strictFollow;

	public boolean strictFollow() {
		return strictFollow;
	}

	public void setOrder(PartnerOrder newOrder, ServerPlayer by) {
		this.strictFollow = newOrder == PartnerOrder.FOLLOW;
		this.order = newOrder;
		this.anchor = newOrder.anchored() ? by.blockPosition() : null;
		brain.onOrder(newOrder, by);
	}

	/** Brain-side order change (one-shots finishing, owner moving away from an anchor...). */
	public void setOrderQuiet(PartnerOrder newOrder) {
		this.order = newOrder;
		if (!newOrder.anchored()) this.anchor = null;
	}

	public String activity() {
		return this.entityData.get(DATA_ACTIVITY);
	}

	public void setActivity(String key) {
		if (!key.equals(activity())) this.entityData.set(DATA_ACTIVITY, key);
	}

	public int skin() {
		return Math.floorMod(this.entityData.get(DATA_SKIN), SKINS.length);
	}

	public void cycleSkin(int delta) {
		this.entityData.set(DATA_SKIN, Math.floorMod(skin() + delta, SKINS.length));
	}

	public SimpleContainer backpack() {
		return backpack;
	}

	public PartnerBrain brain() {
		return brain;
	}

	public int foodLevel() {
		return food;
	}

	public boolean isEating() {
		return eating > 0;
	}

	public void causeExhaustion(float amount) {
		exhaustion += amount;
	}

	// ------------------------------------------------------------------ ticking

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide()) return;
		if (this.tickCount % 20 == 5 && this.level() instanceof ServerLevel sl && sl.getNearestPlayer(this, 96) == null) {
			// Roaming far from everyone: keep its surroundings loaded, like a player would.
			sl.getChunkSource().addTicketWithRadius(LocalPartner.ROAM_TICKET, this.chunkPosition(), 2);
		}
		if (this.tickCount % 20 == 1 && !PartnerManager.isCurrent(this)) {
			// A stale copy (an older save that came back with a chunk): the real partner lives elsewhere.
			this.discard();
		}
	}

	/** Arm swings (hits, chopping, mining) must advance on both sides; vanilla does it only for Monsters. */
	@Override
	public void aiStep() {
		this.updateSwingTime();
		super.aiStep();
		if (!this.level().isClientSide() && this.isInWater()) {
			boolean moving = this.zza > 0.005f || !this.getNavigation().isDone();
			this.setSwimming(moving);
			if (moving) {
				this.setSprinting(true);
				Vec3 v = this.getDeltaMovement();
				double horiz = Math.hypot(v.x, v.z);
				if (horiz > 0.005 && horiz < 0.28) {
					double boost = 0.26 / horiz;
					this.setDeltaMovement(v.x * boost, Math.max(v.y, 0.03), v.z * boost);
				} else if (horiz <= 0.005) {
					double rad = Math.toRadians(this.getYRot());
					this.setDeltaMovement(-Math.sin(rad) * 0.22, Math.max(v.y, 0.03), Math.cos(rad) * 0.22);
				}
			}
		} else if (!this.level().isClientSide() && this.isSwimming()) {
			this.setSwimming(false);
		}
	}

	/** A drop deeper than this is never walked off on purpose (falls of up to 3 blocks do no damage). */
	private static final double SAFE_DROP = 3.4;

	/**
	 * Caution at edges, the way a sneaking player moves: walking (or being nudged) toward a drop that would hurt is
	 * cut short right at the edge. Jumps (moving up) are not touched, so parkour jumps still work.
	 */
	@Override
	protected Vec3 maybeBackOffFromEdge(Vec3 delta, net.minecraft.world.entity.MoverType type) {
		if (delta.y > 0 || type != net.minecraft.world.entity.MoverType.SELF || !this.onGround() || brain.parkour.airborne()) return delta;
		double dx = delta.x, dz = delta.z, step = 0.05;
		while (dx != 0 && canFallAtLeast(dx, 0, SAFE_DROP)) dx = Math.abs(dx) <= step ? 0 : dx - Math.signum(dx) * step;
		while (dz != 0 && canFallAtLeast(0, dz, SAFE_DROP)) dz = Math.abs(dz) <= step ? 0 : dz - Math.signum(dz) * step;
		while (dx != 0 && dz != 0 && canFallAtLeast(dx, dz, SAFE_DROP)) {
			dx = Math.abs(dx) <= step ? 0 : dx - Math.signum(dx) * step;
			dz = Math.abs(dz) <= step ? 0 : dz - Math.signum(dz) * step;
		}
		return new Vec3(dx, delta.y, dz);
	}

	private boolean canFallAtLeast(double dx, double dz, double height) {
		var box = this.getBoundingBox();
		var below = new net.minecraft.world.phys.AABB(box.minX + 1e-7 + dx, box.minY - height - 1e-7, box.minZ + 1e-7 + dz,
				box.maxX - 1e-7 + dx, box.minY, box.maxZ - 1e-7 + dz);
		if (!this.level().noCollision(this, below)) return false;
		return true;
	}

	/** Crouches while standing at a dangerous edge (a long drop or lava below), like a careful player. */
	private void updateCaution() {
		if (this.hasPose(net.minecraft.world.entity.Pose.SLEEPING)) return;
		boolean edge = false;
		if (this.onGround() && !this.isSprinting()) {
			double r = 0.45;
			edge = canFallAtLeast(r, 0, SAFE_DROP) || canFallAtLeast(-r, 0, SAFE_DROP) || canFallAtLeast(0, r, SAFE_DROP) || canFallAtLeast(0, -r, SAFE_DROP);
		}
		if (edge != this.isShiftKeyDown()) {
			this.setShiftKeyDown(edge);
			this.setPose(edge ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING);
		}
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		if (this.tickCount % 2 == 0) updateCaution();
		super.customServerAiStep(level);
		tickHunger(level);
		pickUpItems(level);
		brain.tick(level);
	}

	private void tickHunger(ServerLevel level) {
		if (eating > 0) {
			eating--;
			if (eating % 4 == 0) this.playSound(SoundEvents.GENERIC_EAT.value(), 0.5f, 0.9f + this.random.nextFloat() * 0.2f);
			if (eating == 0) finishEating();
		}
		// Hunger works like the player's: exhaustion from moving and fighting, then saturation, then food.
		if (this.moveDist != lastMoveDist) {
			exhaustion += 0.01f * Math.min(1f, this.moveDist - lastMoveDist);
			lastMoveDist = this.moveDist;
		}
		if (exhaustion >= 4f) {
			exhaustion -= 4f;
			if (saturation > 0) saturation = Math.max(0, saturation - 1);
			else food = Math.max(0, food - 1);
		}
		if (++foodTick >= 80) {
			foodTick = 0;
			if (food >= 18 && this.getHealth() < this.getMaxHealth()) {
				this.heal(1f);
				exhaustion += 3f;
			} else if (food <= 0 && this.getHealth() > 1f) {
				this.hurtServer(level, this.damageSources().starve(), 1f);
			}
		}
		if (eating == 0 && wantsToEat()) startEating();
	}

	private boolean wantsToEat() {
		return food <= 14 || (food < 20 && this.getHealth() < this.getMaxHealth() - 6);
	}

	private void startEating() {
		int slot = Gear.bestFoodSlot(backpack, 20 - food);
		if (slot < 0) return;
		ItemStack offhand = this.getOffhandItem();
		if (!offhand.isEmpty()) {
			// The shield goes back into the backpack while eating (re-equipped by the gear check afterwards).
			if (!backpack.canAddItem(offhand)) return;
			if (this.isUsingItem()) this.stopUsingItem();
			backpack.addItem(offhand);
		}
		eatingStack = backpack.removeItem(slot, 1);
		this.setItemSlot(EquipmentSlot.OFFHAND, eatingStack.copy());
		eating = 32;
	}

	private void finishEating() {
		FoodProperties props = eatingStack.get(DataComponents.FOOD);
		if (props != null) {
			food = Math.min(20, food + props.nutrition());
			saturation = Math.min(food, saturation + props.nutrition() * props.saturation() * 2f);
		}
		if (eatingStack.is(Items.GOLDEN_APPLE) || eatingStack.is(Items.ENCHANTED_GOLDEN_APPLE)) this.heal(4f);
		this.playSound(SoundEvents.PLAYER_BURP, 0.5f, 1f);
		eatingStack = ItemStack.EMPTY;
		this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		brain.onPickedUp(); // re-equip the shield
	}

	/** Items lying around get collected into the backpack, like a player walking over them. */
	private void pickUpItems(ServerLevel level) {
		if (this.tickCount % 4 != 0 || this.isDeadOrDying()) return;
		sweepDropsNow(level);
	}

	/** Immediately collects nearby item drops into the backpack (used both on tick and right after breaking a block). */
	public void sweepDropsNow(ServerLevel level) {
		if (this.isDeadOrDying()) return;
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(2.6, 1.8, 2.6))) {
			if (item.isRemoved() || item.getOwner() == this) continue;
			if (item.hasPickUpDelay() && item.getOwner() != null) continue;
			ItemStack stack = item.getItem();
			if (InfinityCompat.isProtectedItem(stack) || !brain.wantsItem(stack)) continue;
			int before = stack.getCount();
			ItemStack rest = (brain.recovering() ? brain.ownerLoot : backpack).addItem(stack.copy());
			int taken = before - rest.getCount();
			if (taken <= 0) continue;
			ItemStack gift = stack.copyWithCount(taken);
			this.take(item, taken);
			stack.shrink(taken);
			if (item.getOwner() instanceof ServerPlayer giver && giver.getUUID().equals(owner) && !brain.recovering()) {
				pendingGifts.add(gift); // thrown by the owner: appraised below
			}
			if (stack.isEmpty()) item.discard();
			else item.setItem(stack);
			brain.onPickedUp();
		}
		if (!pendingGifts.isEmpty()) {
			ServerPlayer o = getOwnerPlayer();
			if (o != null) Appraisal.received(brain, o, new java.util.ArrayList<>(pendingGifts));
			pendingGifts.clear();
		}
	}

	private final java.util.List<ItemStack> pendingGifts = new java.util.ArrayList<>();

	// ------------------------------------------------------------------ owner care, transfer

	/** Gives the stack to the player (into their inventory, the rest at their feet). */
	public void giveTo(Player player, ItemStack stack) {
		if (stack.isEmpty()) return;
		int count = stack.getCount();
		if (!player.getInventory().add(stack) || !stack.isEmpty()) {
			ItemEntity drop = player.drop(stack, false);
			if (drop != null) drop.setNoPickUpDelay();
		}
		this.swing(InteractionHand.MAIN_HAND);
		this.level().playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.3f, 1.4f);
		if (count > 0) causeExhaustion(0f);
	}

	/** Everything in the backpack (tools included); what it wears and holds stays on it. */
	public Component giveWholeBackpack(Player player) {
		java.util.LinkedHashMap<String, int[]> given = new java.util.LinkedHashMap<>();
		for (ItemStack s : brain.ownerLoot.removeAllItems()) {
			note(given, s);
			giveTo(player, s);
		}
		for (int i = 0; i < backpack.getContainerSize(); i++) {
			ItemStack s = backpack.removeItemNoUpdate(i);
			if (s.isEmpty()) continue;
			note(given, s);
			giveTo(player, s);
		}
		backpack.setChanged();
		return summary(given);
	}

	/** Hands over the backpack (and with {@code gearToo}, everything it wears and holds). */
	public Component handEverythingTo(Player player, boolean gearToo) {
		java.util.LinkedHashMap<String, int[]> given = new java.util.LinkedHashMap<>();
		for (ItemStack s : brain.ownerLoot.removeAllItems()) {
			note(given, s);
			giveTo(player, s);
		}
		for (int i = 0; i < backpack.getContainerSize(); i++) {
			ItemStack s = backpack.getItem(i);
			if (s.isEmpty()) continue;
			if (!gearToo && brain.keepsForItself(s, i)) continue;
			ItemStack taken = backpack.removeItemNoUpdate(i);
			note(given, taken);
			giveTo(player, taken);
		}
		if (gearToo) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				if (slot.getType() == EquipmentSlot.Type.ANIMAL_ARMOR || slot == EquipmentSlot.SADDLE) continue;
				ItemStack s = this.getItemBySlot(slot);
				if (s.isEmpty() || slot == EquipmentSlot.OFFHAND && eating > 0) continue;
				this.setItemSlot(slot, ItemStack.EMPTY);
				note(given, s);
				giveTo(player, s);
			}
		}
		backpack.setChanged();
		return summary(given);
	}

	private static void note(java.util.Map<String, int[]> given, ItemStack s) {
		if (s.isEmpty()) return;
		given.computeIfAbsent(s.getItem().getDescriptionId(), k -> new int[1])[0] += s.getCount();
	}

	/** "Coal ×12, Raw Iron ×5, Oak Log ×9 and 3 more", biggest stacks first. */
	public static Component summary(java.util.Map<String, int[]> given) {
		if (given.isEmpty()) return Component.translatable("partner.local_partner.nothing");
		var sorted = new java.util.ArrayList<>(given.entrySet());
		sorted.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
		net.minecraft.network.chat.MutableComponent out = Component.empty();
		for (int i = 0; i < Math.min(5, sorted.size()); i++) {
			if (i > 0) out.append(", ");
			out.append(Component.translatable(sorted.get(i).getKey())).append(" ×" + sorted.get(i).getValue()[0]);
		}
		if (sorted.size() > 5) out.append(Component.translatable("partner.local_partner.and_more", sorted.size() - 5));
		return out;
	}

	public void openInventory(ServerPlayer player) {
		player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.threeRows(id, inv, backpack),
				Component.translatable("local_partner.backpack.title", this.getDisplayName())));
		speak("inventory");
		brain.backpackOpened();
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
		if (!isOwner(player)) {
			sp.sendSystemMessage(chat(this, Component.translatable("partner.local_partner.not_yours")));
			return InteractionResult.SUCCESS;
		}
		ItemStack held = player.getItemInHand(hand);
		if (player.isShiftKeyDown()) {
			openInventory(sp);
		} else if (!held.isEmpty() && Appraisal.refuses(held)) {
			speakComponent(PartnerChat.Tone.ALERT, Component.translatable("partner.local_partner.appraise.refuse", held.getHoverName()));
		} else if (!held.isEmpty() && !held.is(Items.NAME_TAG)) {
			// Hand an item over: it goes into the backpack, and the partner looks it over (see Appraisal).
			ItemStack rest = backpack.addItem(held.copy());
			int given = held.getCount() - rest.getCount();
			if (given > 0) {
				ItemStack gift = held.copyWithCount(given);
				held.shrink(given);
				Appraisal.received(brain, sp, java.util.List.of(gift));
			} else speak("full");
		} else if (held.is(Items.NAME_TAG)) {
			return super.mobInteract(player, hand);
		} else {
			brain.reportStatus(sp);
		}
		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------ damage / death

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		// Co-op: players (and their tamed pets / partners) never hurt the partner.
		if (source.getEntity() instanceof Player || source.getEntity() instanceof PartnerEntity
				|| source.getEntity() instanceof OwnableEntity o && o.getOwnerReference() != null && owner != null
				&& owner.equals(o.getOwnerReference().getUUID())) return false;
		if (this.getHealth() - damage <= 4.0f && !this.getOffhandItem().is(Items.TOTEM_OF_UNDYING) && !this.isEating()) {
			for (int i = 0; i < backpack.getContainerSize(); i++) {
				if (backpack.getItem(i).is(Items.TOTEM_OF_UNDYING)) {
					ItemStack old = this.getOffhandItem();
					this.setItemSlot(EquipmentSlot.OFFHAND, backpack.removeItem(i, 1));
					if (!old.isEmpty()) backpack.addItem(old);
					break;
				}
			}
		}
		boolean hadTotem = this.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || this.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
		boolean hurt = super.hurtServer(level, source, damage);
		if (hadTotem && !this.getOffhandItem().is(Items.TOTEM_OF_UNDYING) && !this.getMainHandItem().is(Items.TOTEM_OF_UNDYING) && this.isAlive()) {
			speak("totem_saved");
			brain.onPickedUp();
		}
		if (hurt) {
			exhaustion += 0.1f;
			brain.onHurt(source);
		}
		return hurt;
	}

	@Override
	public boolean canBeLeashed() {
		return false;
	}

	@Override
	public void die(DamageSource source) {
		if (this.level() instanceof ServerLevel level && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)
				&& Gear.take(backpack, s -> s.is(Items.TOTEM_OF_UNDYING), 1) > 0) {
			this.setHealth(10.0f);
			this.removeAllEffects();
			this.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 900, 1));
			this.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.ABSORPTION, 100, 1));
			this.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 800, 0));
			level.broadcastEntityEvent(this, (byte) 35);
			speak("totem_saved");
			return;
		}
		boolean wasAlive = !this.isRemoved() && !this.dead;
		super.die(source);
		if (wasAlive && this.level() instanceof ServerLevel) PartnerManager.onPartnerDeath(this);
	}

	/**
	 * A companion never loses what it carries: when it is knocked out it keeps its backpack and gear and comes back
	 * with them (like keepInventory, whatever the world rule says). Only the owner's items it was gathering after
	 * THEIR death are dropped if the partner itself dies on the way, so those are never destroyed either.
	 */
	@Override
	protected void dropEquipment(ServerLevel level) {
		for (ItemStack s : brain.ownerLoot.removeAllItems()) this.spawnAtLocation(level, s);
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
		// Everything it had is dropped in dropEquipment.
	}

	/** Back after a death or after waiting outside: heal up, hunger reset after death. */
	public void revive(boolean afterDeath) {
		this.dead = false;
		this.deathTime = 0;
		this.hurtTime = 0;
		this.setHealth(afterDeath ? this.getMaxHealth() : Math.max(this.getHealth(), 1f));
		this.clearFire();
		this.removeAllEffects();
		this.resetFallDistance();
		this.setDeltaMovement(Vec3.ZERO);
		if (afterDeath) {
			food = 20;
			saturation = 5f;
			exhaustion = 0;
			if (order.anchored() || order.oneShot()) order = PartnerOrder.FOLLOW;
		}
		eating = 0;
		eatingStack = ItemStack.EMPTY;
	}

	public void onOwnerRespawned(ServerPlayer player) {
		if (brain.recovering()) return; // finishes collecting their things first, then comes over
		if (player.level() != this.level()) {
			PartnerManager.travel(player);
			return;
		}
		PartnerEntity moved = PartnerManager.teleportToOwner(this, player);
		if (moved != null) PartnerFx.recall(moved);
		speak("owner_respawned");
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean canUsePortal(boolean allowPassengers) {
		return false; // it follows the owner through the manager instead of walking into portals alone
	}

	// ------------------------------------------------------------------ chat & effects

	public static Component chat(PartnerEntity partner, Component message) {
		return PartnerChat.line(partner, PartnerChat.Tone.INFO, message);
	}

	/** A line with an explicit tone and an already built message. */
	public void speakComponent(PartnerChat.Tone tone, Component message) {
		ServerPlayer p = getOwnerPlayer();
		if (p != null) p.sendSystemMessage(PartnerChat.line(this, tone, message));
	}

	/** A short line in the owner's chat, styled by its kind (see {@link PartnerChat}). */
	public void speak(String key, Object... args) {
		if (squadMember && !key.startsWith("squad_") && !key.equals("totem_saved") && !key.equals("dragon_slain") && !key.equals("bye")) {
			return;
		}
		ServerPlayer p = getOwnerPlayer();
		if (p != null) p.sendSystemMessage(PartnerChat.line(this, PartnerChat.toneOf(key), Component.translatable("partner.local_partner.say." + key, args)));
	}

	/** Appearance next to the owner (summon / back after waiting or death). */
	public void arrivalFx() {
		PartnerFx.summon(this);
		this.level().playSound(null, this.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.8f, 1.3f);
	}

	public void leaveFx() {
		PartnerFx.dismiss(this);
		this.level().playSound(null, this.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.6f, 0.8f);
	}

	// ------------------------------------------------------------------ teleport spot

	/** A safe standing spot 2-4 blocks around {@code center}, or the center itself if nothing better. */
	public static Vec3 safeSpotNear(ServerLevel level, BlockPos center, PartnerEntity partner) {
		Vec3 spot = findSafeSpot(level, center, partner);
		return spot != null ? spot : Vec3.atBottomCenterOf(center);
	}

	/** A safe standing spot 2-4 blocks around {@code center}, or null (e.g. chunks not loaded yet). */
	public static @Nullable Vec3 findSafeSpot(ServerLevel level, BlockPos center, PartnerEntity partner) {
		BlockPos best = null;
		for (int r = 2; r <= 4 && best == null; r++) {
			for (int dx = -r; dx <= r && best == null; dx++) {
				for (int dz = -r; dz <= r && best == null; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					for (int dy = 2; dy >= -3; dy--) {
						BlockPos p = center.offset(dx, dy, dz);
						if (isSafeStand(level, p, partner)) {
							best = p;
							break;
						}
					}
				}
			}
		}
		return best == null ? null : new Vec3(best.getX() + 0.5, best.getY(), best.getZ() + 0.5);
	}

	public static boolean isSafeStand(ServerLevel level, BlockPos pos, PartnerEntity partner) {
		if (!level.getWorldBorder().isWithinBounds(pos) || !level.hasChunkAt(pos)) return false;
		var below = level.getBlockState(pos.below());
		if (!below.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) return false;
		if (!below.getFluidState().isEmpty() || below.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)) return false;
		if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) return false;
		var box = partner.getType().getDimensions().makeBoundingBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		return level.noCollision(partner, box);
	}

	// ------------------------------------------------------------------ save

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		if (owner != null) output.store("Owner", UUIDUtil.CODEC, owner);
		output.putString("Order", order.id);
		output.putBoolean("Strict", strictFollow);
		if (anchor != null) output.store("Anchor", BlockPos.CODEC, anchor);
		output.putInt("Skin", this.entityData.get(DATA_SKIN));
		output.putInt("Food", food);
		output.putFloat("Saturation", saturation);
		output.putFloat("Exhaustion", exhaustion);
		if (!eatingStack.isEmpty()) output.store("Eating", ItemStack.CODEC, eatingStack); // never lose a half-eaten snack
		output.putBoolean("SquadMember", squadMember);
		if (!squadRole.isEmpty()) output.putString("SquadRole", squadRole);
		if (!squadMission.isEmpty()) output.putString("SquadMission", squadMission);
		backpack.storeAsItemList(output.list("Backpack", ItemStack.CODEC));
		brain.save(output);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		strictFollow = input.getBooleanOr("Strict", false);
		squadMember = input.getBooleanOr("SquadMember", false);
		squadRole = input.getStringOr("SquadRole", "");
		squadMission = input.getStringOr("SquadMission", "");
		PartnerOrder o = PartnerOrder.byId(input.getStringOr("Order", "follow"));
		order = o == null || o.oneShot() ? PartnerOrder.FOLLOW : o;
		anchor = input.read("Anchor", BlockPos.CODEC).orElse(null);
		this.entityData.set(DATA_SKIN, input.getIntOr("Skin", 0));
		food = input.getIntOr("Food", 20);
		saturation = input.getFloatOr("Saturation", 5f);
		exhaustion = input.getFloatOr("Exhaustion", 0f);
		input.list("Backpack", ItemStack.CODEC).ifPresent(backpack::fromItemList);
		input.read("Eating", ItemStack.CODEC).ifPresent(backpack::addItem);
		brain.load(input);
	}
}
