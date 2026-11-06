package com.dantemorrigan.partner.ai;

import net.minecraft.server.level.ServerLevel;

/** A piece of work the partner does when nobody needs it: chop, mine, hunt, hand over, play on its own. */
public interface Job {
	enum Status { RUNNING, DONE, FAILED }

	/** Activity key for the HUD and the status report ({@code partner.local_partner.activity.<key>}). */
	String activity();

	Status tick(PartnerBrain brain, ServerLevel level);

	/** Called when the job is interrupted or replaced (clean up cracks, stop navigation). */
	default void stop(PartnerBrain brain, ServerLevel level) {}
}
