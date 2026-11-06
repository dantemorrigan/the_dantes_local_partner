package com.dantemorrigan.partner.ai;

import net.minecraft.server.level.ServerLevel;

/** A piece of work the partner does when nobody needs it: chop, mine, hunt, hand over, play on its own. */
public interface Job {
	enum Status { RUNNING, DONE, FAILED }

	/** Activity key for the HUD and the status report ({@code partner.local_partner.activity.<key>}). */
	String activity();

	Status tick(PartnerBrain brain, ServerLevel level);

	/**
	 * The job's own measure of results (ore mined, logs gathered, depth reached...). It must grow now and then,
	 * or the brain's watchdog drops the job after {@link #stallTicks()}. {@code Integer.MIN_VALUE}: not watched.
	 */
	default int result(PartnerBrain brain) {
		return Integer.MIN_VALUE;
	}

	default int stallTicks() {
		return 20 * 60;
	}

	/** The watchdog fired. Return true when the job handled it itself (e.g. switched to another step). */
	default boolean onStall(PartnerBrain brain, ServerLevel level) {
		return false;
	}

	/** Called when the job is interrupted or replaced (clean up cracks, stop navigation). */
	default void stop(PartnerBrain brain, ServerLevel level) {}
}
