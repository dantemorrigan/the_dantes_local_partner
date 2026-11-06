package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerFx;
import com.dantemorrigan.partner.PartnerManager;
import com.dantemorrigan.partner.PartnerOrder;
import com.dantemorrigan.partner.compat.InfinityCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides what the partner does each tick, in this order:
 * <ol>
 *   <li>Survive: get out of lava/fire/void (teleport to the owner), retreat and eat when nearly dead.</li>
 *   <li>Stay together: follow across distance, teleport back when stuck, too far or in another dimension.</li>
 *   <li>Protect: whoever hurts the owner or the partner, whatever the owner attacks, hostiles closing in.</li>
 *   <li>Care: hand food to a hungry owner, a golden apple to a dying one.</li>
 *   <li>The current order (follow, guard, patrol, stay, wood, ore, food, auto, give).</li>
 * </ol>
 */
public final class PartnerBrain {
	public static final int FOLLOW_TELEPORT = 32, WORK_LEASH = 56, FAR_TELEPORT = 112;

	public final PartnerEntity p;
	public final BlockBreaker breaker = new BlockBreaker();
	public final Smelter smelter = new Smelter();
	private final Map<Integer, Integer> ignoredTargets = new HashMap<>();
	public final Set<BlockPos> badBlocks = new HashSet<>();
	/** Free-mode steps that failed recently (step key → tick when it may be tried again). */
	public final Map<String, Integer> autoBlocked = new HashMap<>();

	private @Nullable Job job;
	private @Nullable LivingEntity target;
	private int targetTicks, unreachableTicks, attackCooldown, retreat, repath;
	private int stuckTicks, teleportCooldown, giveFoodCooldown, careCooldown, chatterCooldown;
	private Vec3 lastProgressPos = Vec3.ZERO;
	private boolean gearDirty = true;

	public PartnerBrain(PartnerEntity p) {
		this.p = p;
	}

	// ------------------------------------------------------------------ events from the body

	public void onOrder(PartnerOrder order, ServerPlayer by) {
		stopJob((ServerLevel) p.level());
		target = null;
		switch (order) {
			case WOOD -> job = new ChopJob(12);
			case ORE -> job = new MineJob(WorldScan.OreKind.ANY, 12);
			case GIVE -> job = new GiveJob(false);
			case FREE -> job = new AutoJob();
			default -> {}
		}
		if (order == PartnerOrder.PATROL && p.anchor() != null) PartnerFx.patrolMark(p, p.anchor());
		PartnerFx.accepted(p);
		p.speak("order_" + order.id);
	}

	public void onHurt(DamageSource source) {
		if (source.getEntity() instanceof LivingEntity attacker && validTarget(attacker, true)) {
			target = attacker;
			targetTicks = 0;
		}
	}

	public void onPickedUp() {
		gearDirty = true;
	}

	public void reset() {
		if (p.level() instanceof ServerLevel level) stopJob(level);
		target = null;
		stuckTicks = 0;
	}

	public boolean wantsItem(ItemStack stack) {
		return p.backpack().canAddItem(stack);
	}

	/** What the partner keeps when handing loot over: its tools, armor, a table, a furnace, torches and some food. */
	public boolean keepsForItself(ItemStack s, int slot) {
		if (Gear.isTool(s) || Gear.isArmor(s) || s.is(Items.CRAFTING_TABLE) || s.is(Items.FURNACE)
				|| s.is(Items.TORCH) || s.is(Items.SHIELD)) return true;
		if (Gear.isFood(s)) {
			int before = 0;
			for (int i = 0; i < slot; i++) if (Gear.isFood(p.backpack().getItem(i))) before += p.backpack().getItem(i).getCount();
			return before < 6;
		}
		return false;
	}

	// ------------------------------------------------------------------ main loop

	public void tick(ServerLevel level) {
		ServerPlayer owner = p.getOwnerPlayer();
		smelter.tick(p, level);
		if (teleportCooldown > 0) teleportCooldown--;
		if (giveFoodCooldown > 0) giveFoodCooldown--;
		if (careCooldown > 0) careCooldown--;
		if (chatterCooldown > 0) chatterCooldown--;
		if (attackCooldown > 0) attackCooldown--;
		if (p.tickCount % 200 == 0) {
			ignoredTargets.entrySet().removeIf(e -> e.getValue() < p.tickCount);
			if (badBlocks.size() > 256) badBlocks.clear();
		}
		if (gearDirty || p.tickCount % 40 == 0) {
			Gear.equipArmor(p);
			gearDirty = false;
		}
		if (owner == null || owner.isSpectator()) {
			p.getNavigation().stop();
			p.setActivity("waiting");
			return;
		}
		if (owner.level() != p.level()) {
			if (!InfinityCompat.partnerWaitsOutside(owner.level().dimension())) p.followAcrossLevels(owner);
			return;
		}
		if (survive(level, owner)) return;
		if (stayTogether(level, owner)) return;
		if (protect(level, owner)) return;
		if (care(level, owner)) return;
		doOrder(level, owner);
	}

	// ------------------------------------------------------------------ 1. survive

	private boolean survive(ServerLevel level, ServerPlayer owner) {
		boolean burning = p.isInLava() || p.isOnFire() && p.getHealth() < 10;
		boolean falling = p.getY() < level.getMinY() + 2;
		if ((burning || falling) && teleportCooldown == 0) {
			teleportHome(owner, burning ? "escaped_fire" : "escaped_void");
			p.clearFire();
			return true;
		}
		// Nearly dead and the owner is not the one in trouble: back off to the owner and eat.
		if (p.getHealth() <= 6 && owner.getHealth() > 8 && target != null && retreat == 0) {
			retreat = 80;
			say("retreat");
		}
		if (retreat > 0) {
			retreat--;
			breaker.cancel(level, p);
			p.setActivity("retreat");
			if (p.distanceTo(owner) > 3) p.getNavigation().moveTo(owner, PartnerEntity.CATCH_UP);
			else p.getNavigation().stop();
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ 2. stay together

	private boolean stayTogether(ServerLevel level, ServerPlayer owner) {
		PartnerOrder order = p.order();
		double horizontal = Math.sqrt(horizontalDistSqr(p.position(), owner.position()));
		if (horizontal > FAR_TELEPORT) {
			// Beyond loaded-chunk comfort for every order: come back, anchored orders end.
			if (order.anchored()) {
				p.setOrderQuiet(PartnerOrder.FOLLOW);
				say("too_far_anchor");
			}
			stopJob(level);
			teleportHome(owner, null);
			return true;
		}
		boolean working = job != null && !(job instanceof GiveJob);
		if (working && horizontal > WORK_LEASH) {
			// Too far from the owner to keep working: walk back (teleport if that does not work).
			p.setActivity("returning");
			breaker.cancel(level, p);
			walkTo(owner, PartnerEntity.CATCH_UP);
			trackStuck(owner, 4);
			return true;
		}
		return false;
	}

	private void trackStuck(ServerPlayer owner, double closeEnough) {
		if (p.distanceTo(owner) <= closeEnough) {
			stuckTicks = 0;
			lastProgressPos = p.position();
			return;
		}
		if (p.tickCount % 20 == 0) {
			if (p.position().distanceToSqr(lastProgressPos) < 1.0) stuckTicks += 20;
			else stuckTicks = Math.max(0, stuckTicks - 10);
			lastProgressPos = p.position();
		}
		if (stuckTicks >= 80 && teleportCooldown == 0) {
			teleportHome(owner, "unstuck");
		}
	}

	public void teleportHome(ServerPlayer owner, @Nullable String line) {
		breaker.cancel((ServerLevel) p.level(), p);
		PartnerEntity moved = PartnerManager.teleportToOwner(p, owner);
		if (moved != null) {
			PartnerFx.recall(moved);
			PartnerFx.toast(moved, "recall");
		}
		stuckTicks = 0;
		teleportCooldown = 40;
		lastProgressPos = p.position();
		if (line != null) say(line);
	}

	// ------------------------------------------------------------------ 3. protect

	private boolean protect(ServerLevel level, ServerPlayer owner) {
		if (p.tickCount % 5 == 0 || target == null || !target.isAlive()) pickTarget(level, owner);
		if (target == null) return false;
		breaker.cancel(level, p);
		fight(level, owner, target);
		return true;
	}

	private void pickTarget(ServerLevel level, ServerPlayer owner) {
		if (target != null && (!target.isAlive() || target.level() != p.level() || target.distanceTo(p) > 24
				|| target.distanceTo(owner) > 32 || ignoredTargets.containsKey(target.getId()))) target = null;
		if (target != null && targetTicks < 200) return;
		LivingEntity best = null;
		int now = owner.tickCount;
		// Whoever attacked the owner or the partner recently.
		LivingEntity owners = owner.getLastHurtByMob();
		if (owners != null && now - owner.getLastHurtByMobTimestamp() < 100 && validTarget(owners, true)) best = owners;
		LivingEntity mine = p.getLastHurtByMob();
		if (best == null && mine != null && p.tickCount - p.getLastHurtByMobTimestamp() < 100 && validTarget(mine, true)) best = mine;
		// Whatever the owner is fighting.
		LivingEntity attacked = owner.getLastHurtMob();
		if (best == null && attacked != null && now - owner.getLastHurtMobTimestamp() < 100 && validTarget(attacked, true)) best = attacked;
		// Hostiles closing in on us.
		if (best == null) {
			PartnerOrder order = p.order();
			double radius = switch (order) {
				case GUARD -> 14;
				case PATROL -> 18;
				default -> 10;
			};
			Vec3 center = order == PartnerOrder.PATROL && p.anchor() != null ? Vec3.atBottomCenterOf(p.anchor()) : owner.position();
			double bestD = Double.MAX_VALUE;
			for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(center, center).inflate(radius, 6, radius), m -> m instanceof Enemy && m.isAlive())) {
				boolean hunting = m.getTarget() == owner || m.getTarget() == p;
				boolean proactive = order == PartnerOrder.GUARD || order == PartnerOrder.PATROL || m.distanceTo(owner) < 7;
				if (!hunting && !(proactive && hostileOnSight(m))) continue;
				if (!hunting && m instanceof Creeper && order != PartnerOrder.GUARD && order != PartnerOrder.PATROL) continue;
				if (!validTarget(m, hunting) || !p.hasLineOfSight(m) && !hunting) continue;
				double d = m.distanceToSqr(p) + (InfinityCompat.isModHostile(m) ? -200 : 0);
				if (d < bestD) {
					bestD = d;
					best = m;
				}
			}
		}
		if (best != target) {
			targetTicks = 0;
			unreachableTicks = 0;
			if (best != null && target == null) {
				PartnerFx.guard(p);
				PartnerFx.toast(p, "alert");
			}
			if (best != null && chatterCooldown == 0) say(InfinityCompat.isBoss(best) ? "fight_boss" : "fight");
		}
		target = best;
	}

	/** Hostiles that are hostile on sight; neutral mobs (endermen, piglins...) only once they are angry. */
	private static boolean hostileOnSight(Mob m) {
		if (m instanceof NeutralMob || m instanceof AbstractPiglin) return m.getTarget() != null;
		return true;
	}

	private boolean validTarget(LivingEntity e, boolean retaliation) {
		if (e == p || !e.isAlive() || e instanceof Player || e instanceof PartnerEntity) return false;
		if (ignoredTargets.containsKey(e.getId())) return false;
		if (e instanceof OwnableEntity o && o.getOwnerReference() != null) return false; // nobody's pet
		if (e instanceof AbstractVillager || e instanceof IronGolem) return false;
		if (e.hasCustomName() && !(e instanceof Enemy)) return false;
		return retaliation || e instanceof Enemy;
	}

	private void fight(ServerLevel level, ServerPlayer owner, LivingEntity t) {
		targetTicks++;
		p.setActivity(InfinityCompat.isBoss(t) ? "fight_boss" : "fight");
		if (t instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited())) {
			// Back off from a hissing creeper instead of eating the explosion.
			Vec3 away = p.position().subtract(c.position()).normalize().scale(6).add(p.position());
			p.getNavigation().moveTo(away.x, away.y, away.z, PartnerEntity.CATCH_UP);
			return;
		}
		if (p.tickCount % 10 == 0 || p.getMainHandItem().isEmpty()) Gear.holdWeapon(p);
		p.getLookControl().setLookAt(t, 30f, 30f);
		double reachSqr = 3.0 * 3.0;
		double distSqr = t.getBoundingBox().distanceToSqr(p.getEyePosition());
		if (distSqr > reachSqr * 0.6 || --repath <= 0) {
			if (repath <= 0) repath = 10;
			boolean moving = p.getNavigation().moveTo(t, PartnerEntity.SPRINT);
			if (!moving) unreachableTicks += 10;
		}
		if (p.getNavigation().isDone() && distSqr > reachSqr) unreachableTicks++;
		else if (distSqr <= reachSqr) unreachableTicks = 0;
		if (unreachableTicks > 100) {
			ignoredTargets.put(t.getId(), p.tickCount + 300);
			target = null;
			return;
		}
		if (distSqr <= reachSqr && attackCooldown <= 0 && p.hasLineOfSight(t)) {
			p.swing(InteractionHand.MAIN_HAND);
			p.doHurtTarget(level, t);
			p.causeExhaustion(0.1f);
			attackCooldown = (int) Math.ceil(20.0 / Math.max(0.5, p.getAttributeValue(Attributes.ATTACK_SPEED)));
			ItemStack weapon = p.getMainHandItem();
			if (Gear.isTool(weapon) && !Gear.isSword(weapon) && !Gear.isAxe(weapon)) weapon.hurtAndBreak(1, p, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
		}
	}

	// ------------------------------------------------------------------ 4. care

	private boolean care(ServerLevel level, ServerPlayer owner) {
		if (careCooldown > 0 || owner.isCreative()) return false;
		boolean dying = owner.getHealth() <= 6;
		boolean hungry = owner.getFoodData().getFoodLevel() <= 12;
		int foodSlot = -1;
		if (dying) foodSlot = slotOf(s -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE));
		if (foodSlot < 0 && (hungry || dying && owner.getFoodData().getFoodLevel() < 18) && giveFoodCooldown == 0) {
			foodSlot = Gear.bestFoodSlot(p.backpack(), 20 - owner.getFoodData().getFoodLevel());
			// Keep one snack for itself unless the owner is in real trouble.
			if (foodSlot >= 0 && Gear.foodPoints(p.backpack()) <= 4 && !dying) foodSlot = -1;
		}
		if (foodSlot < 0) return false;
		p.setActivity("help_food");
		if (p.distanceTo(owner) > 2.5) {
			walkTo(owner, PartnerEntity.SPRINT);
			trackStuck(owner, 2.5);
			return true;
		}
		ItemStack s = p.backpack().getItem(foodSlot);
		int amount = s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE) ? 1
				: Math.max(1, Math.min(s.getCount(), (20 - owner.getFoodData().getFoodLevel()) / Math.max(1, Gear.nutrition(s)) + 1));
		ItemStack given = p.backpack().removeItem(foodSlot, amount);
		p.getLookControl().setLookAt(owner);
		p.giveTo(owner, given);
		PartnerFx.assist(p, owner);
		PartnerFx.toast(p, "assist");
		p.speak("gave_food", given.getHoverName(), given.getCount());
		giveFoodCooldown = 20 * 40;
		careCooldown = 40;
		return true;
	}

	private int slotOf(java.util.function.Predicate<ItemStack> pred) {
		for (int i = 0; i < p.backpack().getContainerSize(); i++) if (pred.test(p.backpack().getItem(i))) return i;
		return -1;
	}

	// ------------------------------------------------------------------ 5. orders

	private void doOrder(ServerLevel level, ServerPlayer owner) {
		PartnerOrder order = p.order();
		if (job != null) {
			Job.Status status = job.tick(this, level);
			if (status == Job.Status.RUNNING) {
				p.setActivity(job.activity());
				return;
			}
			Job finished = job;
			stopJob(level);
			if (order.oneShot() || order == PartnerOrder.WOOD || order == PartnerOrder.ORE) {
				if (status == Job.Status.DONE && !(finished instanceof GiveJob)) {
					job = new GiveJob(true); // bring the haul back
					return;
				}
				p.setOrderQuiet(PartnerOrder.FOLLOW);
			} else if (order == PartnerOrder.FREE) {
				job = new AutoJob();
			}
			return;
		}
		switch (order) {
			case PATROL -> patrol(level, owner);
			case GUARD -> follow(owner, 1.5, 3.0, "guard");
			case FREE -> job = new AutoJob();
			case WOOD, ORE, GIVE -> p.setOrderQuiet(PartnerOrder.FOLLOW);
			default -> follow(owner, 2.5, 5.0, "follow");
		}
	}

	public void follow(ServerPlayer owner, double near, double far, String activity) {
		p.setActivity(p.isEating() ? "eat" : activity);
		double d = p.distanceTo(owner);
		if (d > FOLLOW_TELEPORT && teleportCooldown == 0) {
			teleportHome(owner, null);
			return;
		}
		if (d > far) {
			double speed = d > 10 ? PartnerEntity.CATCH_UP : owner.isSprinting() ? PartnerEntity.SPRINT : PartnerEntity.WALK;
			walkTo(owner, speed);
			trackStuck(owner, far);
		} else {
			stuckTicks = 0;
			lastProgressPos = p.position();
			if (d < near) p.getNavigation().stop();
			idleLook(owner);
		}
	}

	private void walkTo(Entity e, double speed) {
		if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(e, speed);
	}

	private void idleLook(ServerPlayer owner) {
		if (p.getNavigation().isDone() && p.getRandom().nextInt(40) == 0 || p.distanceTo(owner) < 6 && p.getRandom().nextInt(4) == 0) {
			p.getLookControl().setLookAt(owner, 10f, 10f);
		}
	}

	private void patrol(ServerLevel level, ServerPlayer owner) {
		p.setActivity("patrol");
		BlockPos a = p.anchor() != null ? p.anchor() : owner.blockPosition();
		if (p.getNavigation().isDone() && p.getRandom().nextInt(30) == 0) {
			for (int tries = 0; tries < 8; tries++) {
				BlockPos t = a.offset(p.getRandom().nextInt(25) - 12, 0, p.getRandom().nextInt(25) - 12);
				t = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, t);
				if (Math.abs(t.getY() - a.getY()) > 6) continue;
				if (p.getNavigation().moveTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5, PartnerEntity.WALK)) break;
			}
		}
	}

	private void stopJob(ServerLevel level) {
		if (job != null) job.stop(this, level);
		job = null;
		breaker.cancel(level, p);
		p.getNavigation().stop();
	}

	// ------------------------------------------------------------------ helpers for jobs

	/** Walks to the nearest drops around {@code center} so they get picked up; true while there is something to fetch. */
	public boolean collectDrops(ServerLevel level, Vec3 center, double radius) {
		ItemEntity nearest = null;
		double best = Double.MAX_VALUE;
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(radius, 3, radius))) {
			if (item.getOwner() == p || InfinityCompat.isProtectedItem(item.getItem()) || !wantsItem(item.getItem())) continue;
			double d = item.distanceToSqr(p);
			if (d < best) {
				best = d;
				nearest = item;
			}
		}
		if (nearest == null) return false;
		if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(nearest.getX(), nearest.getY(), nearest.getZ(), PartnerEntity.SPRINT);
		return true;
	}

	/** Navigates next to a block until it is within reach. Returns true once in reach. */
	public boolean approach(BlockPos pos) {
		if (BlockBreaker.inReach(p, pos) && p.getNavigation().isDone()) return true;
		if (BlockBreaker.inReach(p, pos) && p.position().distanceToSqr(Vec3.atCenterOf(pos)) < 9) {
			p.getNavigation().stop();
			return true;
		}
		if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) {
			var path = p.getNavigation().createPath(pos, 1);
			if (path != null) p.getNavigation().moveTo(path, PartnerEntity.SPRINT);
		}
		return false;
	}

	/** For tests and debugging: what the brain is doing. */
	public String debug() {
		return "job=" + job + " target=" + target + " logs=" + logs() + " stuck=" + stuckTicks;
	}

	public void say(String key, Object... args) {
		if (chatterCooldown > 0 && !key.startsWith("done")) return;
		chatterCooldown = 60;
		p.speak(key, args);
	}

	public void reportStatus(ServerPlayer player) {
		Component order = Component.translatable("partner.local_partner.order." + p.order().id);
		Component activity = Component.translatable("partner.local_partner.activity." + (p.activity().isEmpty() ? "follow" : p.activity()));
		player.sendSystemMessage(PartnerEntity.chat(p, Component.translatable("partner.local_partner.status",
				order, activity, (int) Math.ceil(p.getHealth()), p.foodLevel())));
	}

	public int pickTier() {
		return Gear.pickTierOf(p);
	}

	public int logs() {
		return Gear.count(p.backpack(), s -> s.is(ItemTags.LOGS));
	}

	public static double horizontalDistSqr(Vec3 a, Vec3 b) {
		double dx = a.x - b.x, dz = a.z - b.z;
		return dx * dx + dz * dz;
	}

	public void save(ValueOutput out) {
		smelter.save(out);
	}

	public void load(ValueInput in) {
		smelter.load(in);
	}
}
