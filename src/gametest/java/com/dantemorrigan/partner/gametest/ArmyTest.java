package com.dantemorrigan.partner.gametest;

import com.dantemorrigan.kingdoms.GodPowers;
import com.dantemorrigan.kingdoms.KingdomsMod;
import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.dantemorrigan.kingdoms.sim.Settlement;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.ArrayList;
import java.util.List;

/** Short check of just the army scenario: two armies meet by a capital and fight in the world. */
public class ArmyTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> s.setSeed("2026")).create()) {
			sp.getClientLevel().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set noon");
			server.runCommand("difficulty peaceful");
			for (int i = 0; i < 20 * 120 && !server.computeOnServer(s -> KingdomsMod.realm() != null && KingdomsMod.realm().seeded); i += 20) ctx.waitTicks(20);
			server.runOnServer(s -> KingdomsMod.realm().speed = 20);
			ctx.waitTicks(20 * 6);
			List<Integer> ids = server.computeOnServer(s -> {
				List<Integer> l = new ArrayList<>();
				for (Kingdom k : KingdomsMod.realm().kingdoms.values()) if (k.alive) l.add(k.id);
				return l;
			});
			// Two armies at war meet next to the first kingdom's capital: a real battle.
			int[] ar = ids.size() < 2 ? null : server.computeOnServer(s -> {
				Realm r = KingdomsMod.realm();
				Settlement c = null;
				int first = 0, second = 0;
				for (int i = 0; i < ids.size() && c == null; i++) {
					Kingdom kk = r.kingdoms.get(ids.get(i));
					if (kk != null && kk.alive && r.capitalOf(kk) != null) {
						c = r.capitalOf(kk);
						first = kk.id;
						second = ids.get((i + 1) % ids.size());
					}
				}
				if (c == null || first == second) return null;
				var p = s.getPlayerList().getPlayers().getFirst();
				GodPowers.act(p, "war", first, second, 0, 0);
				GodPowers.act(p, "army", first, second, c.x + 24, c.z + 24);
				GodPowers.act(p, "army", second, first, c.x + 40, c.z + 24);
				r.speed = 2;
				return new int[]{c.x + 30, c.z + 24};
			});
			if (ar != null) {
				ctx.setScreen(() -> null);
				final int[] fa = ar;
				server.runCommand("gamemode spectator @p");
				server.runCommand("tp @p " + (fa[0] - 18) + " 140 " + (fa[1] - 18) + " -45 30");
				ctx.waitTicks(40);
				int gy = server.computeOnServer(s -> s.overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, fa[0], fa[1]));
				server.runCommand("tp @p " + (fa[0] - 18) + " " + (gy + 14) + " " + (fa[1] - 18) + " -45 30");
				ctx.waitTicks(20 * 10);
				int[] soldiers = server.computeOnServer(s -> {
					int w = 0, g = 0;
					for (var e : s.overworld().getAllEntities()) if (e instanceof com.dantemorrigan.kingdoms.world.CitizenEntity c && c.role() == 2) {
						if (c.armyId != 0) w++;
						else g++;
					}
					return new int[]{w, g};
				});
				System.out.println("KINGDOMS soldiers in the world: army=" + soldiers[0] + " garrison=" + soldiers[1]);
				ctx.takeScreenshot("k6_army");
				server.runCommand("gamemode creative @p");
			} else System.out.println("KINGDOMS no army to look at");

		}
	}
}
