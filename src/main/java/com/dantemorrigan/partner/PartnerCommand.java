package com.dantemorrigan.partner;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /partner} for every player (no operator rights): the chat buttons use it, and it also works typed.
 * call | dismiss | order &lt;follow|wood|ore|guard|patrol|free&gt; | give | inventory | status | later | quiet
 */
public final class PartnerCommand {
	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
				Commands.literal("partner")
						.then(Commands.literal("call").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "call")))
						.then(Commands.literal("dismiss").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "dismiss")))
						.then(Commands.literal("give").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "give")))
						.then(Commands.literal("store").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "store")))
						.then(Commands.literal("home").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "home")))
						.then(Commands.literal("campfire").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "campfire")))
						.then(Commands.literal("portal").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "portal")))
						.then(Commands.literal("ping").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "ping")))
						.then(Commands.literal("skin").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "skin_next")))
						.then(Commands.literal("inventory").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "inventory")))
						.then(Commands.literal("status").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "status")))
						.then(Commands.literal("later").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "later")))
						.then(Commands.literal("quiet").executes(ctx -> run(ctx.getSource().getPlayerOrException(), "quiet")))
						.then(Commands.literal("order").then(Commands.argument("order", StringArgumentType.word())
								.suggests((ctx, b) -> {
									for (PartnerOrder o : PartnerOrder.values()) if (o.onWheel) b.suggest(o.id);
									return b.buildFuture();
								})
								.executes(ctx -> run(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "order")))))));
	}

	private static int run(ServerPlayer player, String what) {
		switch (what) {
			case "call" -> PartnerManager.summon(player);
			case "dismiss" -> PartnerManager.dismiss(player);
			default -> PartnerManager.command(player, what);
		}
		return 1;
	}

	private PartnerCommand() {}
}
