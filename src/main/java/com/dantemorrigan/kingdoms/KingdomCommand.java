package com.dantemorrigan.kingdoms;

import com.dantemorrigan.kingdoms.sim.Kingdom;
import com.dantemorrigan.kingdoms.sim.Realm;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /kingdoms}: list the kingdoms, and every divine act of the map by command (ids from the list).
 * list | god &lt;action&gt; [a] [b] | return
 */
public final class KingdomCommand {
	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
				Commands.literal("kingdoms")
						.executes(ctx -> list(ctx.getSource()))
						.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
						.then(Commands.literal("return").executes(ctx -> god(ctx.getSource(), "return", 0, 0)))
						.then(Commands.literal("god").then(Commands.argument("action", StringArgumentType.word())
								.suggests((ctx, b) -> {
									for (String s : new String[]{"found", "destroy", "bless", "plague", "smite", "meteor", "war", "peace", "ally",
											"gift", "era", "army", "rebel", "observe", "reset"}) b.suggest(s);
									return b.buildFuture();
								})
								.executes(ctx -> god(ctx.getSource(), StringArgumentType.getString(ctx, "action"), 0, 0))
								.then(Commands.argument("a", IntegerArgumentType.integer(0))
										.executes(ctx -> god(ctx.getSource(), StringArgumentType.getString(ctx, "action"), IntegerArgumentType.getInteger(ctx, "a"), 0))
										.then(Commands.argument("b", IntegerArgumentType.integer(0))
												.executes(ctx -> god(ctx.getSource(), StringArgumentType.getString(ctx, "action"),
														IntegerArgumentType.getInteger(ctx, "a"), IntegerArgumentType.getInteger(ctx, "b")))))))));
	}

	private static int list(CommandSourceStack src) {
		Realm r = KingdomsMod.realm();
		if (r == null) return 0;
		src.sendSystemMessage(Component.translatable("kingdoms.cmd.header", r.year(), Component.translatable("kingdoms.season." + r.season())));
		for (Kingdom k : r.kingdoms.values()) {
			if (!k.alive) continue;
			src.sendSystemMessage(Component.literal("#" + k.id + " ").append(k.title().withColor(k.color()))
					.append(Component.translatable("kingdoms.cmd.line", k.kingTitle(), Component.translatable(k.era().key()), (int) k.population, k.cells, (int) k.might())));
		}
		return 1;
	}

	private static int god(CommandSourceStack src, String action, int a, int b) {
		ServerPlayer p = src.getPlayer();
		if (p == null) return 0;
		GodPowers.act(p, action, a, b, p.getBlockX(), p.getBlockZ());
		return 1;
	}

	private KingdomCommand() {}
}
