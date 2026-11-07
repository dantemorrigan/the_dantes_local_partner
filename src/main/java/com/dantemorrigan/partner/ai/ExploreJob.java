package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Free mode: walk off to a new area (50-100 blocks away, within the roaming radius around the owner) to find
 * trees, stone, ore and animals that are not here anymore.
 */
public final class ExploreJob implements Job {
	private final Vec3 goal;
	private final Travel travel = new Travel();
	private double startDist = -1;
	private int ticks;

	public ExploreJob(Vec3 goal) {
		this.goal = goal;
	}

	/** A logical new area in an unvisited sector inside {@code radius} around the owner, never walking in circles. */
	public static ExploreJob newArea(PartnerBrain brain, ServerPlayer owner, int radius) {
		PartnerEntity p = brain.p;
		Vec3 goal = brain.pickNextExplorationWaypoint((ServerLevel) p.level(), owner.position(), 55, 105, Math.max(60, radius - 20));
		return new ExploreJob(goal);
	}

	@Override
	public String activity() {
		return "explore";
	}

	@Override
	public int result(PartnerBrain brain) {
		return (int) (startDist - Math.sqrt(PartnerBrain.horizontalDistSqr(brain.p.position(), goal)));
	}

	@Override
	public int stallTicks() {
		return 20 * 30;
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		double dist = Math.sqrt(PartnerBrain.horizontalDistSqr(brain.p.position(), goal));
		if (startDist < 0) {
			startDist = dist;
			brain.report("explore", (int) dist);
		}
		brain.detail = (int) dist + " m";
		if (dist < 6 || ++ticks > 20 * 120) return Status.DONE;
		return travel.tick(brain, level, goal, PartnerEntity.SPRINT) ? Status.RUNNING : Status.DONE;
	}
}
