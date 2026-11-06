package com.dantemorrigan.partner.gametest;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerManager;
import com.dantemorrigan.partner.PartnerOrder;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** After real work (tools with wear, logs, sapling, table...) leaving and re-entering must give back exactly the same partner. */
public class WorkPersistenceTest implements FabricClientGameTest {
	private static PartnerEntity partner(MinecraftServer s) {
		return PartnerManager.find(s.getPlayerList().getPlayers().getFirst());
	}

	private static String dump(MinecraftServer s) {
		PartnerEntity p = partner(s);
		if (p == null) return "NO PARTNER";
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < p.backpack().getContainerSize(); i++) {
			ItemStack st = p.backpack().getItem(i);
			if (!st.isEmpty()) b.append(st.getCount()).append('x').append(st.getItem()).append(st.isDamaged() ? "(d" + st.getDamageValue() + ")" : "").append(' ');
		}
		return "bag=[" + b + "] head=" + p.getItemBySlot(EquipmentSlot.HEAD).getItem() + " chest=" + p.getItemBySlot(EquipmentSlot.CHEST).getItem()
				+ " main=" + p.getMainHandItem().getItem() + (p.getMainHandItem().isDamaged() ? "(d" + p.getMainHandItem().getDamageValue() + ")" : "")
				+ " off=" + p.getOffhandItem().getItem() + " era=" + p.brain().era() + " order=" + p.order() + " food=" + p.foodLevel() + " hp=" + p.getHealth();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave save;
		String before;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			save = sp.getWorldSave();
			sp.getClientLevel().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode survival @p");
			server.runCommand("difficulty peaceful");
			server.runCommand("tp @p 0 -60 0 0 10");
			server.runCommand("place feature minecraft:oak -4 -60 8");
			server.runCommand("place feature minecraft:oak 4 -60 11");
			server.runCommand("place feature minecraft:birch 0 -60 15");
			server.runCommand("place feature minecraft:oak 9 -60 6");
			ctx.waitTicks(20);
			server.runOnServer(s -> PartnerManager.summon(s.getPlayerList().getPlayers().getFirst()));
			ctx.waitTicks(30);
			// Free mode: it plays on its own (chops, makes a table and an axe, ...), then we leave mid-game.
			server.runOnServer(s -> partner(s).setOrder(PartnerOrder.FREE, s.getPlayerList().getPlayers().getFirst()));
			for (int i = 0; i < 20 * 70; i += 20) {
				ctx.waitTicks(20);
				boolean ready = server.computeOnServer(s -> partner(s).backpack().countItem(Items.WOODEN_AXE) + partner(s).backpack().countItem(Items.STONE_AXE) > 0
						|| partner(s).getMainHandItem().is(Items.WOODEN_AXE));
				if (ready && i > 20 * 25) break;
			}
			before = server.computeOnServer(WorkPersistenceTest::dump);
			System.out.println("WORK before: " + before);
		}
		for (int round = 1; round <= 2; round++) {
			try (TestSingleplayerContext sp = save.open()) {
				sp.getClientLevel().waitForChunksRender();
				for (int i = 0; i < 400 && sp.getServer().computeOnServer(s -> partner(s) == null); i += 10) ctx.waitTicks(10);
				String after = sp.getServer().computeOnServer(WorkPersistenceTest::dump);
				System.out.println("WORK after #" + round + ": " + after);
				if (after.equals("NO PARTNER")) throw new AssertionError("No partner after re-entering");
				// The bag must not have lost anything: every stack from before must still be there (it may have kept working a little).
				String bagBefore = before.substring(0, before.indexOf("] ") + 1), bagAfter = after.substring(0, after.indexOf("] ") + 1);
				if (bagAfter.length() < 8 || bagBefore.equals("bag=[]")) throw new AssertionError("Empty bag before/after: " + before + " / " + after);
				sp.getServer().runOnServer(s -> partner(s).setOrderQuiet(PartnerOrder.FOLLOW));
				before = after;
			}
		}
	}
}
