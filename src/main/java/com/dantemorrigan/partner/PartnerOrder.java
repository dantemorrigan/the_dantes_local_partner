package com.dantemorrigan.partner;

/**
 * Orders. The first six are the sectors of the command wheel (C), in the wheel's clockwise order from the top.
 * Protecting the owner, eating, helping with food and teleporting back when stuck always come before the order.
 */
public enum PartnerOrder {
	/** Default: walk with the owner, help in fights, hand over food. */
	FOLLOW("follow", true),
	/** Chop natural trees nearby, then bring the logs. */
	WOOD("wood", true),
	/** Mine exposed ores, dig a staircase down when there are none, then bring the ore. */
	ORE("ore", true),
	/** Bodyguard: stay right next to the owner and attack every hostile around. */
	GUARD("guard", true),
	/** Walk around the spot where the order was given and clear it of hostiles. */
	PATROL("patrol", true),
	/** Free: play on its own (gather, craft, upgrade gear, hunt) while staying near the owner. */
	FREE("free", true),
	/** Expedition: long-range journey (up to 2500 blocks), map scouting, dungeons, trading, self-unstuck & SOS. */
	EXPEDITION("expedition", true),
	/** Build: analyze and complete the player's house, or build a cozy home from scratch. */
	BUILD("build", true),
	/** Farm: till soil, plant and harvest crops, lure/breed/shear livestock, tame wolves and cats. */
	FARM("farm", true),
	/** Speedrun: autonomously build Nether/End portals, gather Blaze Rods & Eyes of Ender, enter The End, and slay the Ender Dragon. */
	SPEEDRUN("speedrun", true),
	/** Internal: come and hand over the haul, then follow again. */
	GIVE("give", false);

	public final String id;
	public final boolean onWheel;

	PartnerOrder(String id, boolean onWheel) {
		this.id = id;
		this.onWheel = onWheel;
	}

	public boolean oneShot() {
		return this == GIVE;
	}

	/** Orders tied to a fixed spot instead of the owner. */
	public boolean anchored() {
		return this == PATROL || this == BUILD || this == FARM;
	}

	public static PartnerOrder byId(String id) {
		for (PartnerOrder o : values()) if (o.id.equals(id)) return o;
		return null;
	}
}
