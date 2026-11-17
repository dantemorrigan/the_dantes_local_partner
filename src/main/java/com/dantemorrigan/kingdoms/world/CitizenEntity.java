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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * A subject of a kingdom, alive in the world while someone is near its town: peasants stroll and tend, builders
 * work at construction sites, soldiers in the kingdom's colors guard the town and fight its enemies, and the king
 * holds court in the keep. Their number follows the simulated population; they are not saved with the world
 * (the town spawns them again when someone comes back).
 */
public class CitizenEntity extends PathfinderMob {
	public static final int PEASANT = 0, BUILDER = 1, SOLDIER = 2, KING = 3;
	public static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};

	private static final EntityDataAccessor<Integer> DATA_ROLE = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_DYE = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_SKIN = SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

	public int kingdom;
	public int settlement;

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
		this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.15, true) {
			@Override
			public boolean canUse() {
				return (role() == SOLDIER || role() == KING) && super.canUse();
			}
		});
		this.goalSelector.addGoal(3, new BuildGoal(this));
		this.goalSelector.addGoal(4, new MoveTowardsRestrictionGoal(this, 0.9));
		this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.7));
		this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8f));
		this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers(CitizenEntity.class));
		this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
				(target, level) -> (role() == SOLDIER) && target instanceof Enemy));
		this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, CitizenEntity.class, 10, true, false,
				(target, level) -> role() == SOLDIER && target instanceof CitizenEntity c && hostile(c)));
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
		this.setHomeTo(new BlockPos(s.x, (int) this.getY(), s.z), role == KING ? 6 : 34);
		this.setCustomName(Component.translatable("kingdoms.citizen." + role, k.title()));
		this.setCustomNameVisible(role == KING);
		if (role == KING) this.setCustomName(Component.translatable("kingdoms.citizen.king_named", k.kingTitle(), k.title()));
	}

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

	/** Builders walk to a site that is still under construction and work there. */
	static final class BuildGoal extends Goal {
		private final CitizenEntity mob;
		private BlockPos site;
		private int time;

		BuildGoal(CitizenEntity mob) {
			this.mob = mob;
			this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			if (mob.role() != BUILDER || mob.random.nextInt(40) != 0) return false;
			Realm r = KingdomsMod.realm();
			Settlement s = r == null ? null : r.settlements.get(mob.settlement);
			if (s == null) return false;
			for (Settlement.Building b : s.buildings) {
				if (!b.done() && !b.ruined) {
					int x = b.px + 6, z = b.pz + 6;
					site = new BlockPos(x, mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean canContinueToUse() {
			return site != null && time < 400;
		}

		@Override
		public void start() {
			time = 0;
			mob.getNavigation().moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.85);
		}

		@Override
		public void tick() {
			time++;
			mob.getLookControl().setLookAt(site.getX() + 0.5, site.getY() + 1, site.getZ() + 0.5);
			if (mob.distanceToSqr(site.getX() + 0.5, site.getY(), site.getZ() + 0.5) < 36) {
				mob.getNavigation().stop();
				if (time % 8 == 0) mob.swing(InteractionHand.MAIN_HAND);
			} else if (time % 40 == 0) {
				mob.getNavigation().moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.85);
			}
		}

		@Override
		public void stop() {
			site = null;
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
