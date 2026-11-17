package com.dantemorrigan.kingdoms.world;

import com.dantemorrigan.kingdoms.sim.Realm;
import net.minecraft.server.level.ServerLevel;

/** Roads, bridges and railways in the world (filled in below). */
public final class RoadWorks {
	public static void clear() {}

	public static void tick(ServerLevel level, Realm realm) {}

	public static void flush(ServerLevel level, Realm realm, int x, int z, int radius) {}

	/** True where a road, bridge or rail line runs (walls leave a gap there). */
	public static boolean isRoad(int x, int z) {
		return false;
	}

	private RoadWorks() {}
}
