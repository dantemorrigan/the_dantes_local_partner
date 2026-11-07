package com.dantemorrigan.partner.client;

import com.dantemorrigan.partner.PartnerNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.Util;

import java.util.List;

/** What the client knows about its own partner (from the server's status packets) and the current HUD toast. */
public final class ClientState {
	/** 0 none, 1 present, 2 waiting outside, 3 dead (respawning). */
	public static int state;
	public static String order = "";
	public static String activity = "";
	public static String detail = "";
	public static int era;
	public static float health;
	public static int food;
	public static int px, py, pz;
	public static String pois = "";
	public static boolean modOnServer;

	/** Toasts by priority (highest first), per the design pack; everything else shows the current task. */
	private static final List<String> PRIORITY = List.of("alert", "assist", "recall", "accepted", "summon", "dismiss", "unavailable");
	public static final long TOAST_MS = 2000, FADE_MS = 200;
	private static String toast = "";
	private static long toastUntil;

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(PartnerNetwork.Status.TYPE, (s, ctx) -> {
			modOnServer = true;
			state = s.state();
			order = s.order();
			activity = s.activity();
			detail = s.detail();
			era = s.era();
			health = s.health();
			food = s.food();
			px = s.px();
			py = s.py();
			pz = s.pz();
			pois = s.pois();
		});
		ClientPlayNetworking.registerGlobalReceiver(PartnerNetwork.Toast.TYPE, (t, ctx) -> toast(t.key()));
		ClientPlayConnectionEvents.JOIN.register((h, sender, mc) -> modOnServer = ClientPlayNetworking.canSend(PartnerNetwork.Toggle.TYPE));
		ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> {
			state = 0;
			order = activity = toast = pois = "";
			px = py = pz = 0;
			modOnServer = false;
		});
	}

	public static boolean present() {
		return state == 1;
	}

	public static void toast(String key) {
		long now = Util.getMillis();
		boolean active = now < toastUntil + FADE_MS;
		if (active && rank(key) > rank(toast)) return; // a more important toast is still showing
		toast = key;
		toastUntil = now + TOAST_MS;
	}

	private static int rank(String key) {
		int i = PRIORITY.indexOf(key);
		return i < 0 ? PRIORITY.size() : i;
	}

	/** The toast to show and its opacity, or null when the card shows the current task. */
	public static String toast() {
		return Util.getMillis() < toastUntil + FADE_MS ? toast : null;
	}

	public static float toastAlpha() {
		long left = toastUntil + FADE_MS - Util.getMillis();
		return left >= FADE_MS ? 1f : Math.max(0f, left / (float) FADE_MS);
	}

	private ClientState() {}
}
