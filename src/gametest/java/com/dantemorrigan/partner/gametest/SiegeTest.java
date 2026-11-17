package com.dantemorrigan.partner.gametest;

import com.dantemorrigan.kingdoms.GodPowers;
import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.sim.BuildingType;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import com.dantemorrigan.kingdoms.world.CitizenEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Targeted: gate guards, the barracks yard, and a siege with an encircling army, sappers, dynamite and a breach. */
public class SiegeTest implements FabricClientGameTest {
	private static String stats(net.minecraft.server.MinecraftServer s) {
		int soldiers = 0, sappers = 0, commanders = 0, guards = 0, mustered = 0, peasants = 0, tnt = 0;
		for (var e : s.overworld().getAllEntities()) {
			if (e instanceof CitizenEntity c) {
				if (c.role() == CitizenEntity.PEASANT) peasants++;
				if (c.role() == CitizenEntity.SOLDIER) {
					soldiers++;
					if (c.sapper) sappers++;
					if (c.commander) commanders++;
					if (c.hasPost && c.armyId == 0) guards++;
					if (c.muster) mustered++;
				}
			} else if (e instanceof net.minecraft.world.entity.item.PrimedTnt) tnt++;
		}
		return "soldiers " + soldiers + " (sappers " + sappers + ", commanders " + commanders + ", posted " + guards + ", in ranks " + mustered + ") peasants " + peasants + " tnt " + tnt;
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("2026")).create()) {
			sp.getClientLevel().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set noon");
			server.runCommand("difficulty peaceful");
			for (int i = 0; i < 20 * 120 && !server.computeOnServer(s -> KingdomsMod.realm() != null && KingdomsMod.realm().seeded); i += 20) ctx.waitTicks(20);
			server.runOnServer(s -> Realm.TIME_SCALE = 600);
			ctx.waitTicks(20 * 10);
			server.runOnServer(s -> Realm.TIME_SCALE = 1);

			// a walled capital with a gate and a barracks: where is it?
			int[] town = server.computeOnServer(s -> {
				Realm r = KingdomsMod.realm();
				for (Kingdom k : r.kingdoms.values()) {
					Settlement c = k.alive ? r.capitalOf(k) : null;
					if (c == null || c.wall == 0 || c.wallProgress < 1f) continue;
					for (Settlement.Building b : c.buildings) if (b.type == BuildingType.BARRACKS && b.done()) return new int[]{c.id, k.id, c.x, c.z, c.wallR, b.px, b.pz};
				}
				return null;
			});
			if (town == null) {
				System.out.println("SIEGE no walled capital with a barracks yet");
				return;
			}
			System.out.println("SIEGE town " + town[0] + " wallR " + town[4]);
			// the gate on the north side, from outside
			server.runCommand("gamemode creative @p");
			server.runOnServer(s -> GodPowers.act(s.getPlayerList().getPlayers().getFirst(), "observe", 0, 0, town[2] + town[4] - 2, town[3]));
			ctx.waitTicks(60);
			int gy = server.computeOnServer(s -> s.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, town[2], town[3] - town[4]));
			server.runCommand("tp @p " + (town[2] + 12) + " " + (gy + 16) + " " + (town[3] - town[4] - 26) + " facing " + town[2] + " " + (gy + 4) + " " + (town[3] - town[4]));
			ctx.waitTicks(80);
			System.out.println("SIEGE at the gate: " + server.computeOnServer(SiegeTest::stats));
			ctx.takeScreenshot("s1_gate");
			// the barracks yard
			server.runCommand("tp @p " + (town[5] + 6) + " " + (gy + 22) + " " + (town[6] - 12) + " facing " + (town[5] + 6) + " " + gy + " " + (town[6] + 8));
			ctx.waitTicks(80);
			ctx.takeScreenshot("s2_barracks");

			// a siege: an army of another kingdom outside the wall
			server.runOnServer(s -> {
				Realm r = KingdomsMod.realm();
				Kingdom defender = r.kingdoms.get(town[1]);
				Kingdom attacker = null;
				for (Kingdom k : r.kingdoms.values()) if (k.alive && k.id != defender.id) attacker = k;
				var p = s.getPlayerList().getPlayers().getFirst();
				GodPowers.act(p, "war", attacker.id, defender.id, 0, 0);
				GodPowers.act(p, "army", attacker.id, defender.id, town[2], town[3] - town[4] - 50);
				Realm.TIME_SCALE = 30;
			});
			server.runOnServer(s -> GodPowers.act(s.getPlayerList().getPlayers().getFirst(), "observe", 0, 0, town[2], town[3] - town[4] - 50));
			for (int step = 0; step < 24; step++) {
				ctx.waitTicks(20 * 4);
				String d = server.computeOnServer(s2 -> com.dantemorrigan.kingdoms.world.WarZone.debug());
				System.out.println("SIEGE t+" + (step + 1) * 4 + "s: " + server.computeOnServer(SiegeTest::stats) + d);
				// the camera follows the first commander, seen from behind and above
				double[] c = server.computeOnServer(s2 -> {
					for (var e : s2.overworld().getAllEntities()) if (e instanceof CitizenEntity ce && ce.commander) return new double[]{ce.getX(), ce.getY(), ce.getZ()};
					return null;
				});
				if (c != null && step % 3 == 2) {
					server.runCommand("tp @p " + (c[0] - 16) + " " + (c[1] + 14) + " " + (c[2] - 16) + " facing " + c[0] + " " + c[1] + " " + c[2]);
					ctx.waitTicks(10);
					ctx.takeScreenshot("s3_siege_" + step);
				}
			}
			System.out.println("SIEGE end");
		}
	}
}
