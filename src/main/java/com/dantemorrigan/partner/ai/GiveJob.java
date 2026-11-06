package com.dantemorrigan.partner.ai;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerOrder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Walks back to the owner and hands over everything that is not its own gear. */
public final class GiveJob implements Job {
	private final boolean afterWork;
	private int ticks;

	public GiveJob(boolean afterWork) {
		this.afterWork = afterWork;
	}

	@Override
	public String activity() {
		return "returning";
	}

	@Override
	public Status tick(PartnerBrain brain, ServerLevel level) {
		PartnerEntity p = brain.p;
		ServerPlayer owner = p.getOwnerPlayer();
		if (owner == null) return Status.FAILED;
		if (++ticks > 20 * 20 || p.distanceTo(owner) > PartnerBrain.FOLLOW_TELEPORT) {
			brain.teleportHome(owner, null);
			ticks = 0;
		}
		if (p.distanceTo(owner) > 2.5) {
			if (p.tickCount % 10 == 0 || p.getNavigation().isDone()) p.getNavigation().moveTo(owner, PartnerEntity.CATCH_UP);
			return Status.RUNNING;
		}
		p.getNavigation().stop();
		p.getLookControl().setLookAt(owner);
		p.handEverythingTo(owner, false);
		p.speak(afterWork ? "brought" : "gave_all");
		p.setOrderQuiet(PartnerOrder.FOLLOW);
		return Status.DONE;
	}
}
