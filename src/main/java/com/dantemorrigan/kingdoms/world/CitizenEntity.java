package com.dantemorrigan.kingdoms.world;

import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;
import java.util.List;

/**
 * A subject of a kingdom, alive in the world while someone is near its town: peasants stroll and tend, builders
 * work at construction sites, soldiers in the kingdom's colors guard the town and fight its enemies, and the king
 * holds court in the keep. Their number follows the simulated population; they are not saved with the world
 * (the town spawns them again when someone comes back).
 */
public class CitizenEntity extends PathfinderMob implements RangedAttackMob, Merchant {
	public static final int PEASANT = 0, BUILDER = 1, SOLDIER = 2, KING = 3, MERCHANT = 4;
	public static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};

	private static final EntityDataAccessor<Integer> DATA_ROLE = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_DYE = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_SKIN = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

	public int kingdom;
	public int settlement;
	/** Field army this soldier belongs to (0: town garrison). */
	public int armyId;
	/** How many simulated soldiers this one stands for. */
	public float weight = 1f;
	/** Caravan this merchant walks with (0: a market vendor). */
	public int caravanId;
	/** The officer who leads a squad, and the sapper who carries the dynamite. */
	public boolean commander, sapper;
	/** A work site given directly (a road front): builders go there instead of a building. */
	public boolean hasSite;
	public int siteX, siteZ;
	/** A fixed post (a tower top): the soldier stands here and shoots. */
	public boolean hasPost;
	public double postX, postY, postZ;
	public float postYaw;
	public boolean postFacing;
	private Player tradingPlayer;
	private MerchantOffers offers;

	public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
		super(type, level);
		if (this.getNavigation() instanceof GroundPathNavigation nav) {
			nav.setCanOpenDoors(true);
			nav.setCanFloat(true);
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) this.setDropChance(slot, 0f);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.3)
				.add(Attributes.ATTACK_DAMAGE, 2.0)
				.add(Attributes.FOLLOW_RANGE, 24.0)
				.add(Attributes.STEP_HEIGHT, 0.6);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder data) {
		super.defineSynchedData(data);
		data.define(DATA_ROLE, PEASANT);
		data.define(DATA_DYE, 14);
		data.define(DATA_SKIN, 0);
	}

	public int role() {
		return this.entityData.get(DATA_ROLE);
	}

	public int dye() {
		return this.entityData.get(DATA_DYE);
	}

	public int skin() {
		return Math.floorMod(this.entityData.get(DATA_SKIN), SKINS.length);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
		this.goalSelector.addGoal(2, new ArcherGoal(this));
		this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.25, true) {
			@Override
			public boolean canUse() {
				return (role() == SOLDIER || role() == KING) && !isHolding(Items.BOW) && super.canUse();
			}
		});
		this.goalSelector.addGoal(3, new PostGoal(this));
		this.goalSelector.addGoal(3, new BuildGoal(this));
		this.goalSelector.addGoal(4, new PatrolGoal(this));
		this.goalSelector.addGoal(5, new MoveTowardsRestrictionGoal(this, 0.9));
		this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.7) {
			@Override
			public boolean canUse() {
				return !(role() == SOLDIER && (armyId != 0 || hasPost)) && super.canUse();
			}
		});
		this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8f));
		this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers(CitizenEntity.class));
		this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
				(target, level) -> (role() == SOLDIER) && target instanceof Enemy));
		this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, CitizenEntity.class, 4, false, false,
				(target, level) -> role() == SOLDIER && target instanceof CitizenEntity c && hostile(c)
						&& (c.role() == SOLDIER || c.role() == KING)));
	}

	boolean hostile(CitizenEntity other) {
		Realm r = KingdomsMod.realm();
		if (r == null) return false;
		Kingdom k = r.kingdoms.get(kingdom);
		return k != null && k.wars.contains(other.kingdom);
	}

	/** Dress the citizen for its role in its kingdom's colors. */
	public void setup(Kingdom k, Settlement s, int role) {
		this.kingdom = k.id;
		this.settlement = s.id;
		this.entityData.set(DATA_ROLE, role);
		this.entityData.set(DATA_DYE, k.dye);
		this.entityData.set(DATA_SKIN, this.random.nextInt(SKINS.length));
		int color = Kingdom.DYE_RGB[Math.floorMod(k.dye, 16)];
		int era = k.era;
		switch (role) {
			case SOLDIER -> {
				this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(era >= 3 ? Items.DIAMOND_SWORD : era >= 2 ? Items.IRON_SWORD : era >= 1 ? Items.STONE_SWORD : Items.WOODEN_SWORD));
				if (era >= 1) this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
				this.setItemSlot(EquipmentSlot.HEAD, era >= 2 ? new ItemStack(Items.IRON_HELMET) : dyed(Items.LEATHER_HELMET, color));
				this.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, color));
				this.setItemSlot(EquipmentSlot.LEGS, era >= 3 ? new ItemStack(Items.IRON_LEGGINGS) : dyed(Items.LEATHER_LEGGINGS, color));
				this.setItemSlot(EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, color));
				this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(24 + era * 4);
				this.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(2 + era);
				this.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(40);
			}
			case MERCHANT -> {
				this.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, 0x5A8F4E));
				this.setItemSlot(EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, 0xC9A24A));
				this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD));
			}
			case KING -> {
				this.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
				this.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, color));
				this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
				this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
			}
			case BUILDER -> {
				this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(era >= 2 ? Items.IRON_AXE : Items.STONE_AXE));
				this.setItemSlot(EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, 0x9A7B4F));
			}
			default -> {
				Item[] tools = {Items.WOODEN_HOE, Items.IRON_HOE, Items.WHEAT, Items.BREAD, Items.FISHING_ROD, Items.AIR};
				ItemStack tool = new ItemStack(tools[this.random.nextInt(tools.length)]);
				if (!tool.isEmpty()) this.setItemSlot(EquipmentSlot.MAINHAND, tool);
				if (this.random.nextInt(3) == 0) this.setItemSlot(EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, color));
			}
		}
		this.setHealth(this.getMaxHealth());
		if (role != MERCHANT) this.setHomeTo(new BlockPos(s.x, (int) this.getY(), s.z), role == KING ? 6 : 34);
		this.setCustomName(Component.translatable("kingdoms.citizen." + role, k.title()));
		this.setCustomNameVisible(role == KING);
		if (role == KING) this.setCustomName(Component.translatable("kingdoms.citizen.king_named", k.kingTitle(), k.title()));
	}

	/** Turns a soldier into a bowman. */
	public void makeArcher() {
		this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
		this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
	}

	/** A vendor stays by its market stall. */
	public void stayAt(BlockPos pos, int radius) {
		this.setHomeTo(pos, radius);
	}

	@Override
	public void performRangedAttack(LivingEntity target, float power) {
		if (!(this.level() instanceof ServerLevel level)) return;
		ItemStack bow = this.getMainHandItem();
		var arrow = net.minecraft.world.entity.projectile.ProjectileUtil.getMobArrow(this, new ItemStack(Items.ARROW), power, bow);
		double dx = target.getX() - this.getX(), dy = target.getY(0.3333) - arrow.getY(), dz = target.getZ() - this.getZ();
		double flat = Math.sqrt(dx * dx + dz * dz);
		net.minecraft.world.entity.projectile.Projectile.spawnProjectileUsingShoot(arrow, level, new ItemStack(Items.ARROW), dx, dy + flat * 0.2, dz, 1.6f, 6f);
		this.playSound(net.minecraft.sounds.SoundEvents.SKELETON_SHOOT, 1f, 1f / (this.getRandom().nextFloat() * 0.4f + 0.8f));
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (!this.level().isClientSide()) WarZone.onDeath(this);
	}

	// ---------------------------------------------------------------- trading

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (role() != MERCHANT || !this.isAlive() || this.tradingPlayer != null) return super.mobInteract(player, hand);
		Realm r = KingdomsMod.realm();
		Kingdom k = r == null ? null : r.kingdoms.get(kingdom);
		if (k == null || !k.alive || k.wars.contains(0)) return InteractionResult.PASS;
		if (!this.level().isClientSide()) {
			this.offers = Market.offers(k);
			this.setTradingPlayer(player);
			this.openTradingScreen(player, this.getDisplayName(), 1);
		}
		return InteractionResult.SUCCESS;
	}

	@Override public void setTradingPlayer(Player player) { this.tradingPlayer = player; }
	@Override public Player getTradingPlayer() { return this.tradingPlayer; }
	@Override public MerchantOffers getOffers() { return this.offers == null ? new MerchantOffers() : this.offers; }
	@Override public void overrideOffers(MerchantOffers offers) { this.offers = offers; }

	@Override
	public void notifyTrade(MerchantOffer offer) {
		offer.increaseUses();
		Realm r = KingdomsMod.realm();
		Kingdom k = r == null ? null : r.kingdoms.get(kingdom);
		if (k != null) Market.applied(k, offer);
		this.playSound(net.minecraft.sounds.SoundEvents.VILLAGER_YES, 1f, 1f);
	}

	@Override public void notifyTradeUpdated(ItemStack stack) {}
	@Override public int getVillagerXp() { return 0; }
	@Override public void overrideXp(int xp) {}
	@Override public boolean showProgressBar() { return false; }
	@Override public net.minecraft.sounds.SoundEvent getNotifyTradeSound() { return net.minecraft.sounds.SoundEvents.VILLAGER_YES; }
	@Override public boolean isClientSide() { return this.level().isClientSide(); }
	@Override public boolean stillValid(Player player) { return this.tradingPlayer == player && this.isAlive(); }

	static ItemStack dyed(Item item, int rgb) {
		ItemStack s = new ItemStack(item);
		s.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
		return s;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	/** Builders walk to a building that is going up, circle it and work: the blocks appear where they stand. */
	static final class BuildGoal extends Goal {
		private final CitizenEntity mob;
		private int cx, cz, y;
		private int time;

		BuildGoal(CitizenEntity mob) {
			this.mob = mob;
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		private boolean pick() {
			if (mob.hasSite) {
				cx = mob.siteX;
				cz = mob.siteZ;
				y = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
				return true;
			}
			Realm r = KingdomsMod.realm();
			Settlement s = r == null ? null : r.settlements.get(mob.settlement);
			if (s == null) return false;
			List<int[]> sites = new java.util.ArrayList<>();
			for (Settlement.Building b : s.buildings) if (!b.done() && !b.ruined) sites.add(new int[]{b.px + 6, b.pz + 6, b.y == Integer.MIN_VALUE ? Integer.MIN_VALUE : b.y + 1});
			if (s.wall > 0 && s.wallProgress < 1f) { // the front of the wall going up
				int[] pos = s.wallPos(Math.max(0, Math.min(s.wallLen() - 1, (int) (s.wallProgress * s.wallLen()))), 0);
				sites.add(new int[]{pos[0], pos[1], Integer.MIN_VALUE});
			}
			if (sites.isEmpty()) return false;
			int[] site = sites.get(Math.floorMod(mob.getId(), sites.size()));
			cx = site[0];
			cz = site[1];
			y = site[2] == Integer.MIN_VALUE ? mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) : site[2];
			return true;
		}

		@Override
		public boolean canUse() {
			return mob.role() == BUILDER && mob.random.nextInt(10) == 0 && pick();
		}

		@Override
		public boolean canContinueToUse() {
			return mob.role() == BUILDER && time < 600 && cx != 0;
		}

		private void walk() {
			double ang = mob.random.nextDouble() * Math.PI * 2;
			int r = 5 + mob.random.nextInt(3);
			int x = cx + (int) Math.round(Math.cos(ang) * r), z = cz + (int) Math.round(Math.sin(ang) * r);
			mob.getNavigation().moveTo(x + 0.5, mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5, 0.9);
		}

		@Override
		public void start() {
			time = 0;
			walk();
		}

		@Override
		public void tick() {
			time++;
			mob.getLookControl().setLookAt(cx + 0.5, y + 2, cz + 0.5);
			if (time % 7 == 0) mob.swing(InteractionHand.MAIN_HAND);
			if (time % 90 == 0 || mob.getNavigation().isDone() && time % 30 == 0) walk();
			if (time % 120 == 0 && !pick()) cx = 0; // the building is finished: look for the next one
		}

		@Override
		public void stop() {
			cx = 0;
		}
	}

	public void makeCommander(Kingdom k) {
		this.commander = true;
		this.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
		this.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Structures.WOOL[Math.floorMod(k.dye, 16)]));
		this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(this.getMaxHealth() + 12);
		this.setHealth(this.getMaxHealth());
		this.setCustomName(Component.translatable("kingdoms.citizen.commander", k.title()));
		this.setCustomNameVisible(true);
	}

	public void makeSapper() {
		this.sapper = true;
		this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TNT));
		this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		this.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TURTLE_HELMET));
	}

	public void setSite(int x, int z) {
		this.hasSite = true;
		this.siteX = x;
		this.siteZ = z;
	}

	public void setPost(double x, double y, double z, float yaw) {
		setPost(x, y, z);
		this.postYaw = yaw;
		this.postFacing = true;
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		// a sentry on his post stands facing the way he was posted
		if (postFacing && hasPost && getTarget() == null && tickCount % 10 == 0 && distanceToSqr(postX, postY, postZ) < 3) {
			this.setYRot(postYaw);
			this.setYHeadRot(postYaw);
			this.setYBodyRot(postYaw);
		}
	}

	public void setPost(double x, double y, double z) {
		this.hasPost = true;
		this.postX = x;
		this.postY = y;
		this.postZ = z;
		this.setPersistenceRequired();
	}

	/** Stays on its post (a tower top) unless something is in reach to shoot or fight. */
	static final class PostGoal extends Goal {
		private final CitizenEntity mob;

		PostGoal(CitizenEntity mob) {
			this.mob = mob;
			this.setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			return mob.hasPost && mob.distanceToSqr(mob.postX, mob.postY, mob.postZ) > 2.5;
		}

		@Override
		public boolean canContinueToUse() {
			return canUse() && mob.getTarget() == null;
		}

		@Override
		public void start() {
			if (mob.distanceToSqr(mob.postX, mob.postY, mob.postZ) > 30 * 30) mob.teleportTo(mob.postX, mob.postY, mob.postZ);
			else mob.getNavigation().moveTo(mob.postX, mob.postY, mob.postZ, 1.0);
		}
	}

	/** Bowmen keep their distance and loose arrows at the target. */
	static final class ArcherGoal extends Goal {
		private final CitizenEntity mob;
		private int cooldown;

		ArcherGoal(CitizenEntity mob) {
			this.mob = mob;
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			return mob.role() == SOLDIER && mob.isHolding(Items.BOW) && mob.getTarget() != null && mob.getTarget().isAlive();
		}

		@Override
		public boolean canContinueToUse() {
			return canUse();
		}

		@Override
		public void start() {
			cooldown = 15;
		}

		@Override
		public void tick() {
			LivingEntity t = mob.getTarget();
			if (t == null) return;
			double d = mob.distanceToSqr(t);
			mob.getLookControl().setLookAt(t, 30f, 30f);
			if (d > 20 * 20 || !mob.hasLineOfSight(t)) mob.getNavigation().moveTo(t, 1.0);
			else if (d < 7 * 7) mob.getMoveControl().strafe(-0.5f, 0f);
			else mob.getNavigation().stop();
			if (--cooldown <= 0 && d < 22 * 22 && mob.hasLineOfSight(t)) {
				mob.performRangedAttack(t, 1f);
				cooldown = 30 + mob.random.nextInt(15);
			}
		}
	}

	/** Garrison soldiers walk the streets around their town in a loop. */
	static final class PatrolGoal extends Goal {
		private final CitizenEntity mob;
		private int waypoint;
		private BlockPos goal;
		private int time;

		PatrolGoal(CitizenEntity mob) {
			this.mob = mob;
			this.setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			if (mob.role() != SOLDIER || mob.armyId != 0 || mob.hasPost || mob.getTarget() != null || mob.random.nextInt(30) != 0) return false;
			Realm r = KingdomsMod.realm();
			Settlement s = r == null ? null : r.settlements.get(mob.settlement);
			if (s == null) return false;
			double ang = (waypoint++ % 8) / 8.0 * Math.PI * 2 + (mob.getId() % 8) * 0.4;
			int rad = 16 + mob.getId() % 3 * 5;
			int x = s.x + (int) Math.round(Math.cos(ang) * rad), z = s.z + (int) Math.round(Math.sin(ang) * rad);
			if (!mob.level().hasChunkAt(new BlockPos(x, 64, z))) return false;
			goal = new BlockPos(x, mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
			return true;
		}

		@Override
		public boolean canContinueToUse() {
			return goal != null && mob.getTarget() == null && time < 300 && !mob.getNavigation().isDone();
		}

		@Override
		public void start() {
			time = 0;
			mob.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 0.8);
		}

		@Override
		public void tick() {
			time++;
		}
	}

	@Override
	public boolean doHurtTarget(ServerLevel level, net.minecraft.world.entity.Entity target) {
		this.swing(InteractionHand.MAIN_HAND);
		return super.doHurtTarget(level, target);
	}

	@Override
	public boolean canAttack(LivingEntity target) {
		if (target instanceof Player p && !p.isCreative() && !p.isSpectator()) return super.canAttack(target);
		return super.canAttack(target);
	}
}
