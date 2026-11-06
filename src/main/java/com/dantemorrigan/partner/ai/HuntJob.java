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
	private @Nullable Animal prey;
	private int cooldown, ticks, collect, searches;

	public HuntJob(int wantedPoints) {
		this.wantedPoints = wantedPoints;
	}

	@Override
	public String activity() {
		return "hunt";
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		if (Gear.foodPoints(p.backpack()) + rawPoints(p) >= wantedPoints) return Status.DONE;
		if (++ticks > 20 * 60 * 3) return Status.FAILED;
		if (cooldown > 0) cooldown--;
		if (collect > 0) {
			collect--;
			if (brain.collectDrops(level, p.position(), 6)) return Status.RUNNING;
			collect = 0;
		}
		if (prey == null || !prey.isAlive()) {
			if (prey != null) {
				collect = 40;
				prey = null;
				return Status.RUNNING;
			}
			if (ticks % 40 != 1) return Status.RUNNING;
			prey = findPrey(level, p);
			if (prey == null && ++searches > 4) return Status.FAILED;
			return Status.RUNNING;
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

	private static @Nullable Animal findPrey(ServerLevel level, PartnerEntity p) {
		Animal best = null;
		double bestD = Double.MAX_VALUE;
		var box = new AABB(p.blockPosition()).inflate(24, 8, 24);
		for (Animal a : level.getEntitiesOfClass(Animal.class, box, HuntJob::isPrey)) {
			long sameKind = level.getEntitiesOfClass(Animal.class, box, o -> o.getType() == a.getType() && isPrey(o)).size();
			if (sameKind <= 2) continue;
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
