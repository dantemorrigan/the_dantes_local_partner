package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Hunts farm animals for meat until the backpack holds {@code wantedPoints} hunger points of food.
 * Leaves babies, named and leashed animals alone, and never takes the last two of a kind nearby (they can breed).
 * Raw meat gets cooked by the camping furnace when the partner has one.
 */
public final class HuntJob implements Job {
	private final int wantedPoints;
	private final boolean forLeather;
	private @Nullable Animal prey;
	private int cooldown, ticks, collect;

	public HuntJob(int wantedPoints) {
		this(wantedPoints, false);
	}

	public HuntJob(int wantedPoints, boolean forLeather) {
		this.wantedPoints = wantedPoints;
		this.forLeather = forLeather;
	}

	@Override
	public int result(PartnerBrain brain) {
		if (forLeather) return Gear.count(brain.p.backpack(), net.minecraft.world.item.Items.LEATHER);
		return Gear.foodPoints(brain.p.backpack()) + rawPoints(brain.p);
	}

	@Override
	public String activity() {
		return "hunt";
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		if (forLeather) {
			int leather = Gear.count(p.backpack(), net.minecraft.world.item.Items.LEATHER);
			brain.detail = leather + "/" + wantedPoints;
			if (leather >= wantedPoints) {
				craftLeatherArmorForBoth(brain);
				return Status.DONE;
			}
		} else {
			int points = Gear.foodPoints(p.backpack()) + rawPoints(p);
			brain.detail = points + "/" + wantedPoints;
			if (points >= wantedPoints) {
				brain.report("hunt_done", points);
				return Status.DONE;
			}
		}
		if (++ticks > 20 * 60 * 3) return Status.FAILED;
		if (cooldown > 0) cooldown--;
		if (collect > 0) {
			collect--;
			if (brain.collectDrops(level, p.position(), 6)) return Status.RUNNING;
			collect = 0;
		}
		if (prey == null || !prey.isAlive()) {
			if (prey != null) {
				p.sweepDropsNow(level);
				if (forLeather) craftLeatherArmorForBoth(brain);
				collect = 40;
				prey = null;
				return Status.RUNNING;
			}
			prey = forLeather ? findLeatherPrey(level, p) : findPrey(level, p);
			if (prey != null) {
				brain.report("hunt_target", prey.getType().getDescription());
				return Status.RUNNING;
			}
			if (forLeather && Gear.count(p.backpack(), net.minecraft.world.item.Items.LEATHER) >= 4) {
				craftLeatherArmorForBoth(brain);
				return Status.DONE;
			}
			return Status.FAILED;
		}
		Gear.holdWeapon(p);
		p.getLookControl().setLookAt(prey, 30f, 30f);
		double distSqr = prey.getBoundingBox().distanceToSqr(p.getEyePosition());
		if (distSqr > 6 && (p.tickCount % 10 == 0 || p.getNavigation().isDone())) p.getNavigation().moveTo(prey, PartnerEntity.SPRINT);
		if (distSqr <= 9 && cooldown <= 0) {
			p.swing(InteractionHand.MAIN_HAND);
			p.doHurtTarget(level, prey);
			cooldown = (int) Math.ceil(20.0 / Math.max(0.5, p.getAttributeValue(Attributes.ATTACK_SPEED)));
		}
		if (prey.distanceTo(p) > 32) prey = null;
		return Status.RUNNING;
	}

	private static int rawPoints(PartnerEntity p) {
		int n = 0;
		for (int i = 0; i < p.backpack().getContainerSize(); i++) {
			var s = p.backpack().getItem(i);
			if (s.is(net.minecraft.world.item.Items.CHICKEN)) n += 2 * s.getCount();
		}
		return n;
	}

	public static boolean hasPreyNearby(ServerLevel level, PartnerEntity p) {
		return findPrey(level, p) != null;
	}

	public static boolean hasCowsNearby(ServerLevel level, PartnerEntity p) {
		return findLeatherPrey(level, p) != null;
	}

	public static void craftLeatherArmorForBoth(PartnerBrain brain) {
		PartnerEntity p = brain.p;
		var owner = p.getOwnerPlayer();
		if (p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty() && Crafting.craft(p, net.minecraft.world.item.Items.LEATHER_CHESTPLATE)) {
			Gear.equipArmor(p);
			brain.report("crafted", new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_CHESTPLATE).getHoverName());
		}
		if (owner != null && owner.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty()
				&& Crafting.craft(p, net.minecraft.world.item.Items.LEATHER_CHESTPLATE)) {
			int slot = -1;
			for (int i = 0; i < p.backpack().getContainerSize(); i++) {
				if (p.backpack().getItem(i).is(net.minecraft.world.item.Items.LEATHER_CHESTPLATE)) {
					slot = i;
					break;
				}
			}
			if (slot >= 0) {
				var tunic = p.backpack().removeItem(slot, 1);
				p.giveTo(owner, tunic);
				brain.say("gave_leather_tunic", tunic.getHoverName());
			}
		}
		for (var piece : new net.minecraft.world.item.Item[]{
				net.minecraft.world.item.Items.LEATHER_LEGGINGS,
				net.minecraft.world.item.Items.LEATHER_HELMET,
				net.minecraft.world.item.Items.LEATHER_BOOTS}) {
			if (Crafting.craft(p, piece)) {
				Gear.equipArmor(p);
			}
		}
	}

	private static @Nullable Animal findLeatherPrey(ServerLevel level, PartnerEntity p) {
		Animal best = null;
		double bestD = Double.MAX_VALUE;
		var box = new AABB(p.blockPosition()).inflate(28, 8, 28);
		for (Animal a : level.getEntitiesOfClass(Animal.class, box, o -> o instanceof AbstractCow && isPrey(o))) {
			if (com.dantemorrigan.partner.Territory.isProtected(level, a.blockPosition())) {
				long sameKind = level.getEntitiesOfClass(Animal.class, box, o -> o.getType() == a.getType() && isPrey(o)).size();
				if (sameKind <= 2) continue;
			}
			double d = a.distanceToSqr(p);
			if (d < bestD) {
				bestD = d;
				best = a;
			}
		}
		return best;
	}

	private static @Nullable Animal findPrey(ServerLevel level, PartnerEntity p) {
		Animal best = null;
		double bestD = Double.MAX_VALUE;
		var box = new AABB(p.blockPosition()).inflate(24, 8, 24);
		for (Animal a : level.getEntitiesOfClass(Animal.class, box, HuntJob::isPrey)) {
			if (com.dantemorrigan.partner.Territory.isProtected(level, a.blockPosition())) {
				long sameKind = level.getEntitiesOfClass(Animal.class, box, o -> o.getType() == a.getType() && isPrey(o)).size();
				if (sameKind <= 2) continue;
			}
			double d = a.distanceToSqr(p);
			if (d < bestD) {
				bestD = d;
				best = a;
			}
		}
		return best;
	}

	private static boolean isPrey(Animal a) {
		boolean meat = a instanceof AbstractCow || a instanceof Pig || a instanceof Sheep || a instanceof Chicken || a instanceof Rabbit;
		return meat && a.isAlive() && !a.isBaby() && !a.hasCustomName() && !a.isLeashed();
	}
}
