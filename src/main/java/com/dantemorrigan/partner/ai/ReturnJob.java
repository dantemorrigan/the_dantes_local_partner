package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.Territory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Tired after roaming (or the backpack is full, or it is hurt): head home to the owner's bed if it is within
 * walking distance, otherwise teleport to the owner. At home it waits for the owner a while; then the brain hands
 * the haul over.
 */
public final class ReturnJob implements Job {
	private static final int WALK_HOME = 220;
	private final Travel travel = new Travel();
	private @Nullable BlockPos home;
	private boolean decided, arrived;
	private int ticks, waited;

	@Override
	public String activity() {
		return arrived ? "home_wait" : "going_home";
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		ServerPlayer owner = p.getOwnerPlayer();
		if (owner == null) return Status.FAILED;
		if (!decided) {
			decided = true;
			home = Territory.home(owner, level);
			if (home == null || p.blockPosition().distSqr(home) > WALK_HOME * WALK_HOME) {
				brain.teleportHome(owner, "tired_teleport");
				return Status.DONE;
			}
			brain.report("going_home", (int) Math.sqrt(p.blockPosition().distSqr(home)));
		}
		if (p.distanceTo(owner) < 16) return Status.DONE; // met the owner on the way or at home
		Vec3 goal = Vec3.atBottomCenterOf(home);
		double dist = Math.sqrt(PartnerBrain.horizontalDistSqr(p.position(), goal));
		brain.detail = arrived ? "" : (int) dist + " m";
		if (!arrived) {
			if (dist < 4) {
				arrived = true;
				brain.report("home_arrived");
				p.getNavigation().stop();
			} else if (!travel.tick(brain, level, goal, PartnerEntity.WALK) || ++ticks > 20 * 180) {
				brain.teleportHome(owner, "tired_teleport");
				return Status.DONE;
			}
			return Status.RUNNING;
		}
		// Waiting at home; after 3 minutes it goes to the owner instead.
		if (++waited > 20 * 180) {
			brain.teleportHome(owner, "tired_teleport");
			return Status.DONE;
		}
		return Status.RUNNING;
	}
}
