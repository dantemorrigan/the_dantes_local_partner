package com.dantemorrigan.partner.gametest;

import com.dantemorrigan.partner.PartnerEntity;
import com.dantemorrigan.partner.PartnerManager;
import com.dantemorrigan.partner.PartnerOrder;
import com.dantemorrigan.partner.client.CommandWheelScreen;
import com.dantemorrigan.partner.client.PartnerPanelScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.util.function.Function;

/**
 * End-to-end check in a real client: pause-menu entry and panel, summon, command wheel, wood, ore, combat,
 * food help, teleport when left behind, following into the Nether, Infinity's scripted dimensions, dismiss.
 */
public class PartnerTest implements FabricClientGameTest {
	private static ServerPlayer player(MinecraftServer s) {
		return s.getPlayerList().getPlayers().getFirst();
	}

	private static PartnerEntity partner(MinecraftServer s) {
		return PartnerManager.find(player(s));
	}

	private static <T> T on(TestSingleplayerContext sp, Function<MinecraftServer, T> f) {
		return sp.getServer().computeOnServer(f::apply);
	}

	private static void waitServer(ClientGameTestContext ctx, TestSingleplayerContext sp, String what, int ticks, Function<MinecraftServer, Boolean> cond) {
		for (int i = 0; i < ticks; i += 10) {
			if (on(sp, cond)) return;
			ctx.waitTicks(10);
		}
		ctx.takeScreenshot("FAIL_" + what);
		throw new AssertionError("Timed out: " + what + " / partner: " + on(sp, s -> {
			PartnerEntity p = partner(s);
			return p == null ? "none" : p.order() + " " + p.activity() + " at " + p.blockPosition() + " hp " + p.getHealth() + " " + p.brain().debug();
		}));
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			mc.getLanguageManager().setSelected("ru_ru");
			mc.options.languageCode = "ru_ru";
		});
		ctx.runOnClient(mc -> mc.reloadResourcePacks());
		ctx.waitTicks(40);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientLevel().waitForChunksRender();
			var server = sp.getServer();
			server.runCommand("gamemode survival @p");
			server.runCommand("difficulty easy");
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set 6000");
			server.runCommand("tp @p 0 -60 0 0 10");
			ctx.waitTicks(20);

			// 1. Esc → "Local Partner…" → panel (off) → call.
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			ctx.waitFor(mc -> mc.screen instanceof PauseScreen, 100);
			ctx.waitTicks(5);
			ctx.takeScreenshot("01_pause_entry");
			ctx.runOnClient(mc -> {
				for (var w : Screens.getWidgets(mc.screen)) {
					if (w instanceof net.minecraft.client.gui.components.AbstractButton b && b.getMessage().getString().startsWith("Local Partner")) {
						b.onPress(null);
						return;
					}
				}
				throw new AssertionError("No Local Partner entry in the pause menu");
			});
			ctx.waitFor(mc -> mc.screen instanceof PartnerPanelScreen, 100);
			ctx.waitTicks(5);
			ctx.takeScreenshot("02_panel_off");
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
			waitServer(ctx, sp, "summon", 100, s -> partner(s) != null);
			ctx.waitTicks(10);
			ctx.takeScreenshot("03_panel_on");
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); // panel → pause
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE); // pause → game
			ctx.waitFor(mc -> mc.screen == null, 100);
			ctx.waitTicks(4);
			ctx.takeScreenshot("04_partner_arrived_hud");

			// 2. Command wheel: C, arrows, Enter → "Gather wood".
			server.runCommand("place feature minecraft:oak -4 -60 8");
			server.runCommand("place feature minecraft:oak 4 -60 11");
			server.runCommand("place feature minecraft:birch 0 -60 15");
			ctx.getInput().pressKey(GLFW.GLFW_KEY_C);
			ctx.waitFor(mc -> mc.screen instanceof CommandWheelScreen, 100);
			ctx.waitTicks(3);
			ctx.takeScreenshot("05_wheel_neutral");
			ctx.getInput().pressKey(GLFW.GLFW_KEY_RIGHT);
			ctx.getInput().pressKey(GLFW.GLFW_KEY_RIGHT);
			ctx.waitTicks(2);
			ctx.takeScreenshot("06_wheel_wood_selected");
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
			ctx.waitFor(mc -> mc.screen == null, 100);
			waitServer(ctx, sp, "order wood", 40, s -> partner(s).order() == PartnerOrder.WOOD || partner(s).order() == PartnerOrder.GIVE);
			ctx.waitTicks(30);
			ctx.takeScreenshot("07_chopping");
			// It chops the trees (16 logs wanted, ~3 trees have about that), then brings the haul.
			waitServer(ctx, sp, "wood delivered", 20 * 180, s -> player(s).getInventory().countItem(Items.OAK_LOG) + player(s).getInventory().countItem(Items.BIRCH_LOG) >= 6
					&& partner(s).order() == PartnerOrder.FOLLOW);
			ctx.takeScreenshot("08_wood_delivered");

			// 3. Ore: it needs a pickaxe → crafts one from wood (gets wood itself if needed), mines the exposed coal.
			server.runOnServer(s -> {
				PartnerEntity p = partner(s);
				p.backpack().addItem(new ItemStack(Items.OAK_LOG, 3));
			});
			server.runCommand("setblock 4 -60 -4 minecraft:coal_ore");
			server.runCommand("setblock 5 -60 -4 minecraft:coal_ore");
			server.runCommand("setblock 4 -59 -4 minecraft:coal_ore");
			server.runOnServer(s -> partner(s).setOrder(PartnerOrder.ORE, player(s)));
			waitServer(ctx, sp, "coal delivered", 20 * 90, s -> player(s).getInventory().countItem(Items.COAL) >= 1 || partner(s).backpack().countItem(Items.COAL) >= 3);
			ctx.takeScreenshot("09_ore");
			server.runOnServer(s -> partner(s).setOrder(PartnerOrder.FOLLOW, player(s)));

			// 4. Combat: a zombie going for the player gets killed.
			server.runOnServer(s -> {
				PartnerEntity p = partner(s);
				p.backpack().addItem(new ItemStack(Items.IRON_SWORD));
				p.backpack().addItem(new ItemStack(Items.IRON_CHESTPLATE));
			});
			server.runCommand("summon minecraft:zombie 3 -60 6 {Tags:[\"partner_test\"]}");
			ctx.waitTicks(20);
			ctx.takeScreenshot("10_fight");
			waitServer(ctx, sp, "zombie killed", 20 * 30, s -> s.overworld().getEntities(EntityType.ZOMBIE, z -> z.entityTags().contains("partner_test")).isEmpty());
			if (!on(sp, s -> Items.IRON_CHESTPLATE == partner(s).getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem())) {
				throw new AssertionError("Partner did not put on the chestplate");
			}

			// 5. Care: hungry owner gets food.
			server.runOnServer(s -> {
				player(s).getFoodData().setFoodLevel(6);
				partner(s).backpack().addItem(new ItemStack(Items.BREAD, 8));
			});
			waitServer(ctx, sp, "food handed over", 20 * 20, s -> player(s).getInventory().countItem(Items.BREAD) > 0);
			ctx.waitTicks(4);
			ctx.takeScreenshot("11_assist");

			// 6. Left behind → teleports to the owner.
			server.runCommand("tp @p 150 -60 150");
			waitServer(ctx, sp, "teleport to owner", 20 * 15, s -> partner(s) != null && partner(s).distanceTo(player(s)) < 8);
			ctx.waitTicks(4);
			ctx.takeScreenshot("12_recall");

			// 7. Follows into the Nether and back.
			server.runCommand("execute in minecraft:the_nether run tp @p 0 70 0");
			waitServer(ctx, sp, "follow into nether", 20 * 10, s -> partner(s) != null && partner(s).level().dimension() == Level.NETHER);
			server.runCommand("execute in minecraft:overworld run tp @p 0 -60 0");
			waitServer(ctx, sp, "follow back", 20 * 10, s -> partner(s) != null && partner(s).level().dimension() == Level.OVERWORLD);

			// 8. The Dante's Infinity: waits outside the White Void, comes back after.
			if (FabricLoader.getInstance().isModLoaded("infinity_gauntlet")) {
				server.runCommand("execute in infinity_gauntlet:white_void run tp @p 0 101 0");
				waitServer(ctx, sp, "waits outside white void", 20 * 10, s -> partner(s) == null);
				server.runCommand("execute in minecraft:overworld run tp @p 0 -60 0");
				waitServer(ctx, sp, "back from white void", 20 * 10, s -> partner(s) != null);
			}

			// 9. Patrol order shows the patrol mark; then dismiss with confirmation.
			server.runOnServer(s -> partner(s).setOrder(PartnerOrder.PATROL, player(s)));
			ctx.waitTicks(3);
			ctx.takeScreenshot("13_patrol_mark");
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			ctx.waitFor(mc -> mc.screen instanceof PauseScreen, 100);
			ctx.runOnClient(mc -> mc.setScreen(new PartnerPanelScreen(mc.screen)));
			ctx.waitTicks(3);
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
			ctx.waitTicks(3);
			ctx.takeScreenshot("14_dismiss_confirm");
			int before = on(sp, s -> player(s).getInventory().countItem(Items.IRON_SWORD));
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
			waitServer(ctx, sp, "dismissed", 100, s -> partner(s) == null && player(s).getInventory().countItem(Items.IRON_SWORD) > before);
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			ctx.waitTicks(10);
			ctx.takeScreenshot("15_dismissed");
			if (on(sp, s -> !s.overworld().getEntities(com.dantemorrigan.partner.LocalPartner.PARTNER, e -> true).isEmpty())) {
				throw new AssertionError("Partner entity still in the world");
			}
			if (on(sp, s -> player(s).getInventory().countItem(Items.OAK_LOG) + player(s).getInventory().countItem(Items.BIRCH_LOG) < 6)) {
				throw new AssertionError("Lost wood");
			}
			unused(ItemTags.LOGS);
		}
	}

	private static void unused(Object o) {}
}
