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
	public static final int FOLLOW_TELEPORT = 32, WORK_LEASH = 56, FAR_TELEPORT = 112, ROAM_RADIUS = 500, ROAM_TIRED = 20 * 60 * 12;
	private int roamStart = -1, restUntil;

	public final PartnerEntity p;
	public final BlockBreaker breaker = new BlockBreaker();
	public final Smelter smelter = new Smelter();
	public final Parkour parkour = new Parkour();
	public final Initiative initiative = new Initiative(this);
	private final Map<Integer, Integer> ignoredTargets = new HashMap<>();
	public final Set<BlockPos> badBlocks = new HashSet<>();
	/** Free-mode steps that failed recently (step key → tick when it may be tried again). */
	public final Map<String, Integer> autoBlocked = new HashMap<>();

	/** The owner's own things picked up after their death: kept apart from the partner's gear, all handed back. */
	public final net.minecraft.world.SimpleContainer ownerLoot = new net.minecraft.world.SimpleContainer(54);
	private @Nullable BlockPos deathPos;
	private int recoverTicks;

	private @Nullable Job job;
	/** Lines to say a bit later (appraisal of several gifts, one after another). */
	private final java.util.ArrayList<Object[]> later = new java.util.ArrayList<>();
	/** The backpack screen is open: what was inside when it opened, to see what the owner put in. */
	private @Nullable Map<net.minecraft.world.item.Item, Integer> watchBaseline;
	/** Short progress detail for the HUD ("7/16", "Y 23 → 16"...), set by the running job. */
	public String detail = "";
	private final Map<String, Integer> reportAt = new HashMap<>();
	private int lastResult = Integer.MIN_VALUE, lastResultTick, era = -1;
	private boolean sleeping;
	/** The current job was started by the partner itself (self-development while following), not by an order. */
	private boolean selfJob;
	private @Nullable Vec3 selfAnchor;
	private Vec3 calmAnchor = Vec3.ZERO;
	private int calmTicks;
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
			case WOOD -> job = new ChopJob(16);
			case ORE -> job = new MineJob(WorldScan.OreKind.ANY, 12);
			case GIVE -> job = new GiveJob(false);
			case FREE -> {
				job = new AutoJob();
				roamStart = p.tickCount;
				restUntil = 0;
			}
			case EXPEDITION -> {
				job = new ExpeditionJob();
				roamStart = p.tickCount;
				restUntil = 0;
			}
			case BUILD -> job = new BuildJob();
			case FARM -> job = new FarmJob();
			default -> {}
		}
		// Called back from far away (roaming): come at once.
		if ((order == PartnerOrder.FOLLOW || order == PartnerOrder.GUARD) && p.distanceTo(by) > 48 && p.level() == by.level()) {
			teleportHome(by, "came_on_call");
		}
		if ((order == PartnerOrder.PATROL || order == PartnerOrder.BUILD || order == PartnerOrder.FARM) && p.anchor() != null) PartnerFx.patrolMark(p, p.anchor());
		PartnerFx.accepted(p);
		p.speak("order_" + order.id);
	}

	public void onHurt(DamageSource source) {
		if (source.getEntity() instanceof LivingEntity attacker && validTarget(attacker, true)) {
			target = attacker;
			targetTicks = 0;
		}
	}

	public void onOwnerDied(BlockPos where) {
		stopJob((ServerLevel) p.level());
		target = null;
		deathPos = where.immutable();
		recoverTicks = 20 * 60;
		say("recovering");
	}

	/** While collecting the owner's things, pickups go to {@link #ownerLoot}. */
	public boolean recovering() {
		return deathPos != null && recoverTicks > 0;
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
		return recovering() ? ownerLoot.canAddItem(stack) : p.backpack().canAddItem(stack);
	}

	/** What the partner keeps when handing loot over: its tools, armor, a table, a furnace, a bed, torches and some food. */
	public boolean keepsForItself(ItemStack s, int slot) {
		if (Gear.isTool(s) || Gear.isArmor(s) || s.is(Items.CRAFTING_TABLE) || s.is(Items.FURNACE)
				|| s.is(Items.TORCH) || s.is(Items.SHIELD) || s.is(Items.BOW) || s.is(Items.ARROW)) return true;
		if (s.is(ItemTags.BEDS)) {
			for (int i = 0; i < slot; i++) if (p.backpack().getItem(i).is(ItemTags.BEDS)) return false;
			return true;
		}
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
		if (recovering() && recover(level, owner)) return; // even if the owner already respawned elsewhere
		if (owner.level() != p.level()) {
			if (!InfinityCompat.partnerWaitsOutside(owner.level().dimension())) PartnerManager.travel(owner);
			return;
		}
		if (survive(level, owner)) return;
		runLater();
		watchBackpack(owner);
		if (parkour.tickAir(p)) return; // mid-jump: let it land
		if (sleep(level, owner)) return;
		if (p.tickCount % 40 == 0) checkEra();
		if (recover(level, owner)) return;
		trackCalm(owner);
		if (stayTogether(level, owner)) return;
		if (protect(level, owner)) return;
		if (care(level, owner)) return;
		lightTheWay(level, owner);
		if (p.tickCount % 20 == 0) {
			Job own = initiative.think(level, owner, job != null && !selfJob);
			if (p.strictFollow()) own = null; // told to follow: no side trips (the question or warning was still sent)
			if (own != null && (job == null || selfJob)) {
				stopJob(level);
				job = own;
				selfJob = true;
				selfAnchor = owner.position();
			}
		}
		doOrder(level, owner);
	}

	/** The owner has been staying around one spot (building, mining, crafting) for a while. */
	private void trackCalm(ServerPlayer owner) {
		if (p.tickCount % 20 != 0) return;
		if (owner.position().distanceToSqr(calmAnchor) > 8 * 8) {
			calmAnchor = owner.position();
			calmTicks = 0;
		} else calmTicks += 20;
	}

	/** Off on its own in free/expedition mode, far from the owner: fights only for itself, does not run back to help. */
	public boolean roaming(ServerPlayer owner) {
		return (p.order() == PartnerOrder.FREE || p.order() == PartnerOrder.EXPEDITION) && horizontalDistSqr(p.position(), owner.position()) > 48 * 48;
	}

	public void ignoredEntity(int id, int durationTicks) {
		ignoredTargets.put(id, p.tickCount + durationTicks);
	}

	public boolean isIgnoredEntity(int id) {
		Integer until = ignoredTargets.get(id);
		return until != null && until > p.tickCount;
	}

	/** Free mode: time to come back (long trip, full backpack, hurt with nothing to eat). */
	private boolean tired() {
		int free = 0;
		for (int i = 0; i < p.backpack().getContainerSize(); i++) if (p.backpack().getItem(i).isEmpty()) free++;
		boolean longTrip = roamStart >= 0 && p.tickCount - roamStart > ROAM_TIRED;
		boolean hurt = p.getHealth() < 8 && Gear.foodPoints(p.backpack()) == 0 && !p.isEating();
		return longTrip || free <= 2 || hurt;
	}

	public boolean inFight() {
		return target != null && target.isAlive();
	}

	/** Everything the free-mode plan could do is done (best gear it can make, food stocked). */
	public boolean nothingToImprove() {
		return AutoJob.nothingToDo(this);
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
		if (p.getAirSupply() < 80) {
			p.getJumpControl().jump();
			if (p.getAirSupply() <= 30 && teleportCooldown == 0) {
				teleportHome(owner, "escaped_water");
				p.setAirSupply(p.getMaxAirSupply());
				return true;
			}
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

	// ------------------------------------------------------------------ 1b. the owner died: save their things

	private boolean recover(ServerLevel level, ServerPlayer owner) {
		if (recovering()) {
			recoverTicks--;
			raiseShield(false);
			Vec3 center = Vec3.atCenterOf(deathPos);
			boolean more = collectDrops(level, center, 10);
			if (!more && p.position().distanceToSqr(center) > 9) {
				if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(center.x, center.y, center.z, PartnerEntity.CATCH_UP);
				if (p.position().distanceToSqr(center) > 64 * 64 && teleportCooldown == 0) {
					p.snapTo(center.x, center.y, center.z, p.getYRot(), p.getXRot());
					teleportCooldown = 40;
				}
				more = true;
			}
			// Done when nothing is left around the spot (or time is up) and the owner is back.
			if (!more && owner.isAlive()) recoverTicks = 0;
			if (recoverTicks > 0 || !owner.isAlive()) {
				p.setActivity("recover");
				return true;
			}
			deathPos = null;
		}
		if (!owner.isAlive()) {
			p.setActivity("waiting");
			p.getNavigation().stop();
			return true;
		}
		if (ownerLoot.isEmpty()) return false;
		// Bring everything back.
		p.setActivity("returning");
		if (p.distanceTo(owner) > FOLLOW_TELEPORT && teleportCooldown == 0) teleportHome(owner, null);
		if (p.distanceTo(owner) > 2.5) {
			walkTo(owner, PartnerEntity.CATCH_UP);
			trackStuck(owner, 2.5);
			return true;
		}
		for (int i = 0; i < ownerLoot.getContainerSize(); i++) {
			ItemStack s = ownerLoot.removeItemNoUpdate(i);
			if (!s.isEmpty()) p.giveTo(owner, s);
		}
		ownerLoot.setChanged();
		say("recovered");
		return true;
	}

	// ------------------------------------------------------------------ 2. stay together

	private boolean stayTogether(ServerLevel level, ServerPlayer owner) {
		PartnerOrder order = p.order();
		double horizontal = Math.sqrt(horizontalDistSqr(p.position(), owner.position()));
		int maxRange = order == PartnerOrder.EXPEDITION ? ExpeditionJob.EXPEDITION_RADIUS
				: order == PartnerOrder.FREE ? ROAM_RADIUS : FAR_TELEPORT;
		if (horizontal > maxRange) {
			// Beyond loaded-chunk comfort for every order: come back, anchored orders end.
			if (order.anchored()) {
				p.setOrderQuiet(PartnerOrder.FOLLOW);
				say("too_far_anchor");
			}
			stopJob(level);
			teleportHome(owner, null);
			return true;
		}
		boolean working = job != null && !(job instanceof GiveJob)
				&& order != PartnerOrder.FREE && order != PartnerOrder.EXPEDITION
				&& order != PartnerOrder.BUILD && order != PartnerOrder.FARM;
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
		if (moved == null) {
			teleportCooldown = 1; // no safe spot yet (chunks loading): try again next tick
			return;
		}
		PartnerFx.recall(moved);
		PartnerFx.toast(moved, "recall");
		stuckTicks = 0;
		teleportCooldown = 40;
		lastProgressPos = moved.position();
		if (line != null) say(line);
	}

	// ------------------------------------------------------------------ 3. protect

	private boolean protect(ServerLevel level, ServerPlayer owner) {
		if (p.tickCount % 5 == 0 || target == null || !target.isAlive()) pickTarget(level, owner);
		if (target == null) {
			raiseShield(false);
			return false;
		}
		breaker.cancel(level, p);
		fight(level, owner, target);
		return true;
	}

	private void pickTarget(ServerLevel level, ServerPlayer owner) {
		boolean away = roaming(owner);
		if (target != null && (!target.isAlive() || target.level() != p.level() || target.distanceTo(p) > 24
				|| !away && target.distanceTo(owner) > 32 || ignoredTargets.containsKey(target.getId()))) target = null;
		if (target != null && targetTicks < 200) return;
		LivingEntity best = null;
		int now = owner.tickCount;
		// Whoever attacked the owner or the partner recently.
		LivingEntity owners = away ? null : owner.getLastHurtByMob();
		if (owners != null && now - owner.getLastHurtByMobTimestamp() < 100 && validTarget(owners, true)) best = owners;
		LivingEntity mine = p.getLastHurtByMob();
		if (best == null && mine != null && p.tickCount - p.getLastHurtByMobTimestamp() < 100 && validTarget(mine, true)) best = mine;
		// Whatever the owner is fighting.
		LivingEntity attacked = away ? null : owner.getLastHurtMob();
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
		p.getLookControl().setLookAt(t, 30f, 30f);
		if (t instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited())) {
			// Back off from a hissing creeper (shield up if there is one) instead of eating the explosion.
			Vec3 away = p.position().subtract(c.position()).normalize().scale(6).add(p.position());
			p.getNavigation().moveTo(away.x, away.y, away.z, PartnerEntity.CATCH_UP);
			raiseShield(true);
			return;
		}
		double reachSqr = 3.0 * 3.0;
		double distSqr = t.getBoundingBox().distanceToSqr(p.getEyePosition());
		boolean flying = t.isNoGravity() || t instanceof net.minecraft.world.entity.monster.Phantom
				|| t instanceof net.minecraft.world.entity.monster.Blaze || t.getY() - p.getY() > 3 && !t.onGround();
		boolean canShoot = Gear.arrows(p) > 0 && Gear.has(p, Gear::isBow) && distSqr < 26 * 26 && p.hasLineOfSight(t);
		if (canShoot && (flying || unreachableTicks > 40)) {
			shoot(level, t, distSqr);
			return;
		}
		if (bowCharge > 0 && p.isUsingItem() && Gear.isBow(p.getUseItem())) p.stopUsingItem();
		bowCharge = 0;
		if (p.tickCount % 10 == 0 || p.getMainHandItem().isEmpty() || Gear.isBow(p.getMainHandItem())) Gear.holdWeapon(p);
		if (distSqr > reachSqr * 0.6 || --repath <= 0) {
			if (repath <= 0) repath = 10;
			boolean moving = p.getNavigation().moveTo(t, PartnerEntity.SPRINT);
			if (!moving) unreachableTicks += 10;
		}
		if (p.getNavigation().isDone() && distSqr > reachSqr) unreachableTicks++;
		else if (distSqr <= reachSqr) unreachableTicks = 0;
		if (unreachableTicks > 100 && !canShoot) {
			ignoredTargets.put(t.getId(), p.tickCount + 300);
			target = null;
			raiseShield(false);
			return;
		}
		boolean swingNow = distSqr <= reachSqr && attackCooldown <= 0 && p.hasLineOfSight(t);
		// Shield up between swings while something can hit us (melee in reach, or a shooter looking at us).
		boolean shooter = t instanceof net.minecraft.world.entity.monster.RangedAttackMob;
		raiseShield(!swingNow && (distSqr <= 16 || shooter && distSqr < 400 && t.hasLineOfSight(p)));
		if (swingNow) {
			p.swing(InteractionHand.MAIN_HAND);
			p.doHurtTarget(level, t);
			p.causeExhaustion(0.1f);
			attackCooldown = (int) Math.ceil(20.0 / Math.max(0.5, p.getAttributeValue(Attributes.ATTACK_SPEED)));
			ItemStack weapon = p.getMainHandItem();
			if (Gear.isTool(weapon) && !Gear.isSword(weapon) && !Gear.isAxe(weapon)) weapon.hurtAndBreak(1, p, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
		}
	}

	private int bowCharge;

	/** Bow for what the sword cannot reach: flyers, mobs on ledges, across water. Arrows are real and can be picked up. */
	private void shoot(ServerLevel level, LivingEntity t, double distSqr) {
		raiseShield(false);
		if (!Gear.isBow(p.getMainHandItem())) Gear.hold(p, s -> Gear.isBow(s) ? 10 : 0);
		if (!p.isUsingItem() && Gear.isBow(p.getMainHandItem())) p.startUsingItem(InteractionHand.MAIN_HAND);
		if (distSqr > 18 * 18) {
			if (p.tickCount % 10 == 0) p.getNavigation().moveTo(t, PartnerEntity.WALK);
		} else {
			p.getNavigation().stop();
			p.getMoveControl().strafe(distSqr < 8 * 8 ? -0.35f : 0f, (p.tickCount / 40) % 2 == 0 ? 0.35f : -0.35f);
		}
		if (++bowCharge < 20 || !p.hasLineOfSight(t)) return;
		bowCharge = 0;
		if (p.isUsingItem()) p.stopUsingItem();
		ItemStack bow = p.getMainHandItem();
		if (Gear.take(p.backpack(), s -> s.is(Items.ARROW), 1) == 0) return;
		var arrow = net.minecraft.world.entity.projectile.ProjectileUtil.getMobArrow(p, new ItemStack(Items.ARROW), 1.0f, bow);
		arrow.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.ALLOWED;
		double dx = t.getX() - p.getX(), dy = t.getY(0.3333) - arrow.getY(), dz = t.getZ() - p.getZ();
		double flat = Math.sqrt(dx * dx + dz * dz);
		net.minecraft.world.entity.projectile.Projectile.spawnProjectileUsingShoot(arrow, level, new ItemStack(Items.ARROW), dx, dy + flat * 0.2, dz, 1.6f, 2f);
		p.playSound(net.minecraft.sounds.SoundEvents.ARROW_SHOOT, 1f, 1f / (p.getRandom().nextFloat() * 0.4f + 0.8f));
		bow.hurtAndBreak(1, p, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
	}

	private void raiseShield(boolean up) {
		boolean has = Gear.isShield(p.getOffhandItem());
		if (up && has && !p.isUsingItem()) p.startUsingItem(InteractionHand.OFF_HAND);
		else if ((!up || !has) && p.isUsingItem() && Gear.isShield(p.getUseItem())) p.stopUsingItem();
	}

	// ------------------------------------------------------------------ 4. care

	private boolean care(ServerLevel level, ServerPlayer owner) {
		if (careCooldown > 0 || owner.isCreative() || roaming(owner)) return false;
		boolean dying = owner.getHealth() <= 6;
		// Hungry and nothing to eat on them (a player with food in the pocket can feed themselves).
		int ownerFood = 0;
		for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
			ItemStack s = owner.getInventory().getItem(i);
			if (Gear.isFood(s)) ownerFood += Gear.nutrition(s) * s.getCount();
		}
		boolean hungry = owner.getFoodData().getFoodLevel() <= 12 && ownerFood < 6;
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
		Component name = given.getHoverName();
		int count = given.getCount();
		p.giveTo(owner, given);
		PartnerFx.assist(p, owner);
		PartnerFx.toast(p, "assist");
		p.speak("gave_food", name, count);
		giveFoodCooldown = 20 * 40;
		careCooldown = 40;
		return true;
	}

	private int torchCooldown;

	/** Underground with the owner and it is dark: put a torch on the floor next to the partner (like a teammate would). */
	private void lightTheWay(ServerLevel level, ServerPlayer owner) {
		if (torchCooldown > 0) {
			torchCooldown--;
			return;
		}
		torchCooldown = 20;
		if (p.distanceTo(owner) > 6 || level.canSeeSky(owner.blockPosition()) || level.getMaxLocalRawBrightness(owner.blockPosition()) > 3) return;
		if (Gear.count(p.backpack(), Items.TORCH) == 0 && !Crafting.craft(p, Items.TORCH)) return;
		BlockPos at = p.blockPosition();
		var torch = net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState();
		if (!level.getBlockState(at).isAir() || !torch.canSurvive(level, at) || !level.mayInteract(p, at)) return;
		if (Gear.take(p.backpack(), s -> s.is(Items.TORCH), 1) == 0) return;
		level.setBlockAndUpdate(at, torch);
		p.swing(InteractionHand.MAIN_HAND);
		torchCooldown = 20 * 6;
	}

	private int slotOf(java.util.function.Predicate<ItemStack> pred) {
		for (int i = 0; i < p.backpack().getContainerSize(); i++) if (pred.test(p.backpack().getItem(i))) return i;
		return -1;
	}

	// ------------------------------------------------------------------ 5. orders

	private void doOrder(ServerLevel level, ServerPlayer owner) {
		PartnerOrder order = p.order();
		// Self-development while following: drop it as soon as the owner moves on.
		if (job != null && selfJob && order != PartnerOrder.FREE) {
			boolean ownerLeft = selfAnchor != null && owner.position().distanceToSqr(selfAnchor) > 24 * 24;
			boolean tooFar = horizontalDistSqr(p.position(), owner.position()) > 36 * 36;
			if (ownerLeft || tooFar || order != PartnerOrder.FOLLOW && !(job instanceof HuntJob)) {
				stopJob(level);
				say("coming");
			}
		}
		// Free / Expedition mode: tired → head home (or teleport to the owner), hand over the haul, rest, then off again.
		if ((order == PartnerOrder.FREE || order == PartnerOrder.EXPEDITION) && !(job instanceof ReturnJob) && !(job instanceof GiveJob) && !(job instanceof StoreJob) && p.tickCount % 20 == 0 && tired()) {
			stopJob(level);
			say("tired");
			job = new ReturnJob();
		}
		if (job != null) {
			Job.Status status = job.tick(this, level);
			if (status == Job.Status.RUNNING && stalled(level)) status = Job.Status.FAILED;
			if (status == Job.Status.RUNNING) {
				p.setActivity(job.activity());
				return;
			}
			Job finished = job;
			boolean wasSelf = selfJob;
			stopJob(level);
			if (wasSelf && order == PartnerOrder.FOLLOW) return;
			if (order.oneShot() || order == PartnerOrder.WOOD || order == PartnerOrder.ORE) {
				if (status == Job.Status.DONE && !(finished instanceof GiveJob) && !(finished instanceof StoreJob)) {
					job = new GiveJob(true); // bring the haul back
					return;
				}
				p.setOrderQuiet(PartnerOrder.FOLLOW);
			} else if (order == PartnerOrder.FREE || order == PartnerOrder.EXPEDITION) {
				if (finished instanceof ReturnJob) {
					job = StoreJob.findChest(level, p.blockPosition(), 20) != null ? new StoreJob(true) : new GiveJob(true);
					restUntil = p.tickCount + 20 * 120;
				} else if ((finished instanceof GiveJob || finished instanceof StoreJob) && restUntil > p.tickCount) {
					say("resting");
				} else {
					job = order == PartnerOrder.EXPEDITION ? new ExpeditionJob() : new AutoJob();
				}
			} else if (order == PartnerOrder.BUILD) {
				p.setOrderQuiet(PartnerOrder.FOLLOW);
			} else if (order == PartnerOrder.FARM) {
				job = new FarmJob();
			}
			return;
		}
		switch (order) {
			case PATROL -> patrol(level, owner);
			case GUARD -> follow(owner, 1.5, 3.0, "guard");
			case BUILD -> job = new BuildJob();
			case FARM -> job = new FarmJob();
			case FREE, EXPEDITION -> {
				if (restUntil > p.tickCount) {
					follow(owner, 2.5, 5.0, "resting");
				} else {
					if (restUntil != 0) {
						restUntil = 0;
						roamStart = p.tickCount;
						say("rested");
					}
					if (roamStart < 0) roamStart = p.tickCount;
					job = order == PartnerOrder.EXPEDITION ? new ExpeditionJob() : new AutoJob();
				}
			}
			case WOOD, ORE, GIVE -> p.setOrderQuiet(PartnerOrder.FOLLOW);
			default -> {
				// Following and the owner is busy in one place: use the time to grow (gear, food, ore).
				if (!p.strictFollow() && calmTicks >= 20 * 4 && !inFight() && p.tickCount % 20 == 0 && !AutoJob.nothingToDo(this)) {
					job = new AutoJob();
					selfJob = true;
					selfAnchor = owner.position();
					return;
				}
				follow(owner, 2.5, 5.0, "follow");
			}
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
		jumpIfBlocked(e.position());
	}

	/** When the path ends short of the target (a gap), try a running jump across like a player. */
	public void jumpIfBlocked(Vec3 target) {
		var nav = p.getNavigation();
		var path = nav.getPath();
		boolean blocked = path == null ? nav.isDone() : !path.canReach() && (path.isDone() || nav.isDone());
		if (blocked && p.tickCount % 5 == 0) parkour.tryJump(p, (ServerLevel) p.level(), target);
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
		detail = "";
		lastResult = Integer.MIN_VALUE;
		selfJob = false;
		parkour.cancel();
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
		jumpIfBlocked(Vec3.atBottomCenterOf(pos));
		return false;
	}

	/** For tests and debugging: what the brain is doing. */
	public String debug() {
		return "job=" + job + " target=" + target + " logs=" + logs() + " stuck=" + stuckTicks;
	}

	/**
	 * Progress watchdog: a job that has not produced a result (its own measure: ore, logs, food, depth, crafts) for
	 * a while is dropped. Free mode only drops the current step and puts that topic aside.
	 */
	private boolean stalled(ServerLevel level) {
		if (job == null) return false;
		int result = job.result(this);
		int limit = job.stallTicks();
		if (result == Integer.MIN_VALUE || limit <= 0) return false;
		if (result != lastResult) {
			lastResult = result;
			lastResultTick = p.tickCount;
			return false;
		}
		if (p.tickCount - lastResultTick < limit) return false;
		lastResultTick = p.tickCount;
		if (job.onStall(this, level)) return false; // handled inside (free mode switched topics)
		report("stalled", Component.translatable("partner.local_partner.activity." + job.activity()), limit / 20);
		return true;
	}

	/** Progress lines: each kind at most every 12 s, any at most every 3 s (fights and alerts use {@link #say}). */
	public void report(String key, Object... args) {
		Integer at = reportAt.get(key);
		Integer any = reportAt.get("*");
		if (at != null && p.tickCount - at < 240 || any != null && p.tickCount - any < 60) return;
		reportAt.put(key, p.tickCount);
		reportAt.put("*", p.tickCount);
		p.speak(key, args);
	}

	/** Eras of Minecraft progress, from the best pickaxe and armor it owns. */
	public int era() {
		int tier = pickTier();
		boolean iron = Gear.armorValue(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)) >= 12;
		if (tier >= 5) return 5;
		if (tier >= 4) return 4;
		if (tier >= 3 || iron) return 3;
		if (tier >= 2) return 2;
		return tier >= 1 ? 1 : 0;
	}

	private void checkEra() {
		int now = era();
		if (era < 0) {
			era = now;
			return;
		}
		if (now > era) {
			era = now;
			p.speak("era_up", Component.translatable("partner.local_partner.era." + now));
			p.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 0.6f, 1.2f);
		}
	}

	// ------------------------------------------------------------------ sleep with the owner

	private int bedSearch;
	private @Nullable BlockPos bed;
	private @Nullable BlockPos ownBed;
	private ItemStack ownBedItem = ItemStack.EMPTY;

	/** Stores non-gear resources into a nearby chest (or tells the owner if there is none). */
	public void storeInChest() {
		if (!(p.level() instanceof ServerLevel level)) return;
		stopJob(level);
		job = new StoreJob(false);
	}

	/** Places a lit campfire next to the owner or partner for the night. */
	public void makeCampfire(ServerPlayer owner) {
		if (!(p.level() instanceof ServerLevel level)) return;
		for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-6, -2, -6), p.blockPosition().offset(6, 2, 6))) {
			if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CAMPFIRE)) {
				p.speak("campfire_already");
				return;
			}
		}
		if (Gear.count(p.backpack(), Items.CAMPFIRE) == 0 && !Crafting.craft(p, Items.CAMPFIRE)) {
			// Try making one on the spot if it has coal/charcoal and logs/planks, else place a basic campfire from wood.
			if (Gear.take(p.backpack(), s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS), 2) == 0) {
				p.speak("campfire_need_wood");
				return;
			}
		} else {
			Gear.take(p.backpack(), s -> s.is(Items.CAMPFIRE), 1);
		}
		BlockPos center = p.distanceTo(owner) < 8 ? owner.blockPosition() : p.blockPosition();
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			for (int dist = 2; dist <= 3; dist++) {
				for (int dy : new int[]{0, -1, 1}) {
					BlockPos pos = center.relative(d, dist).above(dy);
					if (!level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()) continue;
					if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) continue;
					level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState());
					level.playSound(null, pos, net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1.0f);
					p.swing(InteractionHand.MAIN_HAND);
					p.speak("campfire_placed");
					return;
				}
			}
		}
	}

	/** The owner went to bed: find a free bed nearby, place the partner's own bed from its backpack, or lie down next to them. */
	private boolean sleep(ServerLevel level, ServerPlayer owner) {
		boolean ownerSleeps = owner.isSleeping() && p.distanceTo(owner) < 40 && !inFight();
		if (!ownerSleeps) {
			if (sleeping || ownBed != null) wakeUp();
			return false;
		}
		p.setActivity("sleep");
		detail = "";
		if (sleeping) return true;
		stopJob(level);
		if (bed == null && bedSearch-- <= 0) {
			bedSearch = 40;
			bed = findBed(level);
			if (bed == null) bed = placeOwnBed(level, owner);
		}
		if (bed != null) {
			if (!level.getBlockState(bed).is(net.minecraft.tags.BlockTags.BEDS) || level.getBlockState(bed).getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)) {
				bed = null;
				return true;
			}
			if (p.position().distanceToSqr(Vec3.atCenterOf(bed)) > 2.5 * 2.5) {
				if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, PartnerEntity.WALK);
				return true;
			}
			p.getNavigation().stop();
			p.startSleeping(bed);
		} else {
			// No free bed: lie down on the ground right next to the owner.
			if (p.distanceTo(owner) > 2.5) {
				walkTo(owner, PartnerEntity.WALK);
				return true;
			}
			p.getNavigation().stop();
			p.setPose(net.minecraft.world.entity.Pose.SLEEPING);
		}
		sleeping = true;
		raiseShield(false);
		p.speak("good_night");
		return true;
	}

	private @Nullable BlockPos placeOwnBed(ServerLevel level, ServerPlayer owner) {
		int bedSlot = slotOf(s -> s.is(ItemTags.BEDS) && s.getItem() instanceof net.minecraft.world.item.BlockItem);
		if (bedSlot < 0 && Crafting.canMake(p, Items.WHITE_BED) && Crafting.tableNearby(p)) {
			Crafting.craft(p, Items.WHITE_BED);
			bedSlot = slotOf(s -> s.is(ItemTags.BEDS) && s.getItem() instanceof net.minecraft.world.item.BlockItem);
		}
		if (bedSlot < 0) return null;
		BlockPos center = p.distanceTo(owner) < 12 ? owner.blockPosition() : p.blockPosition();
		for (int r = 1; r <= 4; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					for (int dy : new int[]{0, -1, 1}) {
						BlockPos foot = center.offset(dx, dy, dz);
						for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
							BlockPos head = foot.relative(dir);
							if (!canPlaceBedAt(level, foot) || !canPlaceBedAt(level, head)) continue;
							ItemStack stack = p.backpack().removeItem(bedSlot, 1);
							net.minecraft.world.level.block.Block bedBlock = ((net.minecraft.world.item.BlockItem) stack.getItem()).getBlock();
							var footState = bedBlock.defaultBlockState()
									.setValue(net.minecraft.world.level.block.BedBlock.FACING, dir)
									.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT);
							var headState = footState.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD);
							level.setBlock(foot, footState, 3);
							level.setBlock(head, headState, 3);
							level.playSound(null, head, net.minecraft.sounds.SoundEvents.WOOD_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1.0f);
							ownBed = head.immutable();
							ownBedItem = stack;
							return ownBed;
						}
					}
				}
			}
		}
		return null;
	}

	private boolean canPlaceBedAt(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).canBeReplaced() && level.getFluidState(pos).isEmpty()
				&& level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
				&& level.getEntities(null, new AABB(pos)).isEmpty() && level.mayInteract(p, pos);
	}

	private void packOwnBed(ServerLevel level) {
		if (ownBed == null) return;
		var st = level.getBlockState(ownBed);
		if (st.is(net.minecraft.tags.BlockTags.BEDS)) {
			net.minecraft.core.Direction facing = st.getValue(net.minecraft.world.level.block.BedBlock.FACING);
			BlockPos foot = ownBed.relative(facing.getOpposite());
			if (level.getBlockState(foot).is(net.minecraft.tags.BlockTags.BEDS)) {
				level.setBlock(foot, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 35);
			}
			level.setBlock(ownBed, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 35);
		}
		if (!ownBedItem.isEmpty()) {
			ItemStack rest = p.backpack().addItem(ownBedItem);
			if (!rest.isEmpty()) p.spawnAtLocation(level, rest);
			ownBedItem = ItemStack.EMPTY;
		}
		ownBed = null;
	}

	private void wakeUp() {
		sleeping = false;
		bed = null;
		if (p.isSleeping()) p.stopSleeping();
		if (p.level() instanceof ServerLevel level) packOwnBed(level);
		p.setPose(net.minecraft.world.entity.Pose.STANDING);
		p.speak("good_morning");
	}

	private @Nullable BlockPos findBed(ServerLevel level) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(p.blockPosition().offset(-16, -4, -16), p.blockPosition().offset(16, 4, 16))) {
			var st = level.getBlockState(pos);
			if (!st.is(net.minecraft.tags.BlockTags.BEDS) || st.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)) continue;
			if (st.getValue(net.minecraft.world.level.block.BedBlock.PART) != net.minecraft.world.level.block.state.properties.BedPart.HEAD) continue;
			double d = pos.distSqr(p.blockPosition());
			if (d < bestD) {
				bestD = d;
				best = pos.immutable();
			}
		}
		return best;
	}

	public boolean sleeping() {
		return sleeping;
	}

	/** Winds up the current job cleanly (before the partner is saved or moved): cracks cleared, a placed table or bed taken back. */
	public void suspend() {
		if (p.level() instanceof ServerLevel level) {
			stopJob(level);
			if (sleeping || ownBed != null) {
				sleeping = false;
				bed = null;
				if (p.isSleeping()) p.stopSleeping();
				packOwnBed(level);
			}
		}
		parkour.cancel();
	}

	/** Runs {@code r} after {@code ticks} ticks. */
	public void later(Runnable r, int ticks) {
		later.add(new Object[]{p.tickCount + ticks, r});
	}

	private void runLater() {
		for (int i = 0; i < later.size(); i++) {
			Object[] e = later.get(i);
			if ((int) e[0] <= p.tickCount) {
				later.remove(i--);
				((Runnable) e[1]).run();
			}
		}
	}

	/** The owner opened the partner's backpack: remember what is in it. */
	public void backpackOpened() {
		java.util.HashMap<net.minecraft.world.item.Item, Integer> m = new java.util.HashMap<>();
		for (int i = 0; i < p.backpack().getContainerSize(); i++) {
			ItemStack s = p.backpack().getItem(i);
			if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		watchBaseline = m;
	}

	/** When the backpack screen closes: whatever was added is a gift (or refused if it is not for the partner). */
	private void watchBackpack(ServerPlayer owner) {
		if (watchBaseline == null || owner.containerMenu != owner.inventoryMenu) return;
		var before = watchBaseline;
		watchBaseline = null;
		java.util.List<ItemStack> gifts = new java.util.ArrayList<>();
		java.util.List<ItemStack> refused = new java.util.ArrayList<>();
		var bag = p.backpack();
		java.util.HashMap<net.minecraft.world.item.Item, Integer> now = new java.util.HashMap<>();
		for (int i = 0; i < bag.getContainerSize(); i++) {
			ItemStack s = bag.getItem(i);
			if (!s.isEmpty()) now.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		for (var e : now.entrySet()) {
			int added = e.getValue() - before.getOrDefault(e.getKey(), 0);
			if (added <= 0) continue;
			ItemStack sample = new ItemStack(e.getKey(), added);
			for (int i = 0; i < bag.getContainerSize(); i++) {
				if (bag.getItem(i).is(e.getKey())) {
					sample = bag.getItem(i).copyWithCount(added);
					break;
				}
			}
			if (Appraisal.refuses(sample)) {
				Gear.take(bag, s -> s.is(e.getKey()), added);
				refused.add(sample);
			} else gifts.add(sample);
		}
		for (ItemStack r : refused) {
			p.giveTo(owner, r);
			p.speakComponent(com.dantemorrigan.partner.PartnerChat.Tone.ALERT,
					Component.translatable("partner.local_partner.appraise.refuse", r.getHoverName()));
		}
		if (!gifts.isEmpty()) Appraisal.received(this, owner, gifts);
	}

	public void say(String key, Object... args) {
		if (chatterCooldown > 0 && !key.startsWith("done")) return;
		chatterCooldown = 60;
		p.speak(key, args);
	}

	private final java.util.ArrayList<String> pois = new java.util.ArrayList<>();
	private int lastEmergencyTick = -9999;

	/** Records a discovered landmark (dungeon, village/trader, biome) for the live tactical map and reports it. */
	public void recordPoi(String kind, BlockPos pos, String label) {
		char code = switch (kind) {
			case "dungeon" -> 'D';
			case "village" -> 'V';
			default -> 'B';
		};
		String entry = code + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
		for (String existing : pois) {
			if (existing.equals(entry)) return;
		}
		if (pois.size() >= 10) pois.removeFirst();
		pois.add(entry);
		if (kind.equals("dungeon")) {
			report("poi_dungeon", pos.getX(), pos.getY(), pos.getZ());
		} else if (kind.equals("village")) {
			report("poi_village", pos.getX(), pos.getY(), pos.getZ());
		} else if (kind.equals("biome")) {
			report("poi_biome", label, pos.getX(), pos.getZ());
		}
	}

	public String poisSummary(ServerPlayer owner) {
		StringBuilder sb = new StringBuilder();
		var resp = owner.getRespawnConfig();
		if (resp != null && resp.respawnData().dimension().equals(owner.level().dimension())) {
			BlockPos h = resp.respawnData().pos();
			sb.append("H:").append(h.getX()).append(",").append(h.getY()).append(",").append(h.getZ());
		}
		for (String e : pois) {
			if (!sb.isEmpty()) sb.append(";");
			sb.append(e);
		}
		return sb.toString();
	}

	/** Makes the partner head back to the home base (bed) or owner and store its haul. */
	public void returnToBase(ServerPlayer owner) {
		if (!(p.level() instanceof ServerLevel level)) return;
		stopJob(level);
		p.setOrderQuiet(PartnerOrder.FREE);
		restUntil = p.tickCount + 20 * 120;
		job = new ReturnJob();
		say("returning_base");
	}

	/**
	 * Extreme unstuck / SOS handler: called when active self-rescue (pillaring, digging, bridging) could not free the partner.
	 * Tries a surface escape first; if still trapped or hurt, sends an SOS with clickable action buttons and evacuates to base or owner.
	 */
	public void emergencyUnstuck(ServerLevel level) {
		ServerPlayer owner = p.getOwnerPlayer();
		if (owner == null) return;
		boolean repeated = p.tickCount - lastEmergencyTick < 20 * 60;
		lastEmergencyTick = p.tickCount;

		// 1. Try escaping to a clean surface block nearby if not critically hurt and not a repeated trap.
		if (!repeated && p.getHealth() > 8) {
			for (int tries = 0; tries < 8; tries++) {
				int dx = p.getRandom().nextInt(17) - 8;
				int dz = p.getRandom().nextInt(17) - 8;
				if (Math.abs(dx) < 4 && Math.abs(dz) < 4) continue;
				BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.blockPosition().offset(dx, 0, dz));
				if (level.getFluidState(top).isEmpty() && level.getFluidState(top.below()).isEmpty() && Parkour.passable(level, top)) {
					p.snapTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, p.getYRot(), p.getXRot());
					p.getNavigation().stop();
					p.resetFallDistance();
					report("self_unstuck_surface", top.getX(), top.getY(), top.getZ());
					return;
				}
			}
		}

		// 2. Extreme situation: send SOS with coordinates and clickable buttons ([Ко мне] / [На базу]), and evacuate!
		BlockPos pos = p.blockPosition();
		com.dantemorrigan.partner.PartnerChat.ask(owner, p,
				Component.translatable("partner.local_partner.ask_sos", pos.getX(), pos.getY(), pos.getZ()),
				java.util.List.of(
						com.dantemorrigan.partner.PartnerChat.button(
								Component.translatable("partner.local_partner.btn.sos_tp"),
								"/partner order follow",
								Component.translatable("partner.local_partner.btn.sos_tp.hover"),
								com.dantemorrigan.partner.PartnerChat.FOLLOW),
						com.dantemorrigan.partner.PartnerChat.button(
								Component.translatable("partner.local_partner.btn.sos_base"),
								"/partner home",
								Component.translatable("partner.local_partner.btn.sos_base.hover"),
								com.dantemorrigan.partner.PartnerChat.FREE)
				));
		if (p.getHealth() <= 8 || repeated) {
			stopJob(level);
			teleportHome(owner, "sos_evacuated");
		} else {
			returnToBase(owner);
		}
	}

	public void reportStatus(ServerPlayer player) {
		Component order = Component.translatable("partner.local_partner.order." + p.order().id);
		Component activity = Component.translatable("partner.local_partner.activity." + (p.activity().isEmpty() ? "follow" : p.activity()));
		player.sendSystemMessage(PartnerEntity.chat(p, Component.translatable("partner.local_partner.status",
				order, activity, (int) Math.ceil(p.getHealth()), p.foodLevel())));
		player.sendSystemMessage(PartnerEntity.chat(p, Component.translatable("partner.local_partner.status2",
				Component.translatable("partner.local_partner.era." + era()), detail.isEmpty() ? "—" : detail)));
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
		out.putInt("Era", Math.max(era, 0));
		if (!ownerLoot.isEmpty()) ownerLoot.storeAsItemList(out.list("OwnerLoot", ItemStack.CODEC));
	}

	public void load(ValueInput in) {
		smelter.load(in);
		era = in.getIntOr("Era", -1);
		in.list("OwnerLoot", ItemStack.CODEC).ifPresent(ownerLoot::fromItemList);
	}
}
