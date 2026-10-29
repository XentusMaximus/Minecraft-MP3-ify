package de.vibecoded.mp3player.network;

import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStop;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStop;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Relays 3D speaker MP3 frame chunks on IntegratedServer (Singleplayer / Essential World Host / LAN)
 * as well as Dedicated Fabric Servers.
 */
public final class SpeakerRelayServer {
	public static final double HEARING_RADIUS = 56.0;
	private static final double HEARING_RADIUS_SQR = HEARING_RADIUS * HEARING_RADIUS;
	private static final double DISCONNECT_RADIUS_SQR = (HEARING_RADIUS + 8.0) * (HEARING_RADIUS + 8.0);
	private static final int MAX_BYTES_PER_SECOND = 160 * 1024; // generous ceiling for 320kbps + initial prebuffer

	private static final Map<UUID, ActiveSession> SESSIONS = new ConcurrentHashMap<>();
	private static int tickCounter = 0;

	private SpeakerRelayServer() {
	}

	private static final class ActiveSession {
		final UUID speakerUuid;
		final String speakerName;
		final UUID streamId;
		final String trackTitle;
		volatile float volume;
		volatile boolean paused;
		volatile long lastChunkTimeMs;
		long rateWindowStartMs;
		int bytesInWindow;
		final Set<UUID> activeListeners = ConcurrentHashMap.newKeySet();

		ActiveSession(UUID speakerUuid, String speakerName, UUID streamId, String trackTitle, float volume) {
			this.speakerUuid = speakerUuid;
			this.speakerName = speakerName;
			this.streamId = streamId;
			this.trackTitle = trackTitle;
			this.volume = volume;
			this.paused = false;
			long now = System.currentTimeMillis();
			this.lastChunkTimeMs = now;
			this.rateWindowStartMs = now;
			this.bytesInWindow = 0;
		}

		synchronized boolean allowBytes(int byteCount) {
			long now = System.currentTimeMillis();
			if (now - rateWindowStartMs >= 1000L) {
				rateWindowStartMs = now;
				bytesInWindow = 0;
			}
			if (bytesInWindow + byteCount > MAX_BYTES_PER_SECOND) {
				return false;
			}
			bytesInWindow += byteCount;
			lastChunkTimeMs = now;
			return true;
		}
	}

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(C2SStreamStart.TYPE, (payload, context) -> {
			ServerPlayer speaker = context.player();
			context.server().execute(() -> handleStart(context.server(), speaker, payload));
		});

		ServerPlayNetworking.registerGlobalReceiver(C2SStreamChunk.TYPE, (payload, context) -> {
			ServerPlayer speaker = context.player();
			byte[] data = payload.mp3Frames();
			if (data == null || data.length == 0 || data.length > Mp3Payloads.MAX_CHUNK_BYTES) {
				return;
			}
			context.server().execute(() -> handleChunk(context.server(), speaker, payload));
		});

		ServerPlayNetworking.registerGlobalReceiver(C2SStreamState.TYPE, (payload, context) -> {
			ServerPlayer speaker = context.player();
			context.server().execute(() -> handleState(context.server(), speaker, payload));
		});

		ServerPlayNetworking.registerGlobalReceiver(C2SStreamStop.TYPE, (payload, context) -> {
			ServerPlayer speaker = context.player();
			context.server().execute(() -> stopSessionFor(context.server(), speaker.getUUID(), payload.streamId()));
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID leaving = handler.getPlayer().getUUID();
			server.execute(() -> {
				stopSessionFor(server, leaving, null);
				for (ActiveSession session : SESSIONS.values()) {
					session.activeListeners.remove(leaving);
				}
			});
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> SESSIONS.clear());

		ServerTickEvents.END_SERVER_TICK.register(SpeakerRelayServer::onServerTick);
	}

	private static void handleStart(MinecraftServer server, ServerPlayer speaker, C2SStreamStart payload) {
		stopSessionFor(server, speaker.getUUID(), null);

		ActiveSession session = new ActiveSession(
				speaker.getUUID(),
				speaker.getScoreboardName(),
				payload.streamId(),
				payload.trackTitle(),
				payload.volume()
		);
		SESSIONS.put(speaker.getUUID(), session);

		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other.getUUID().equals(speaker.getUUID())) {
				continue;
			}
			if (other.level() == speaker.level() && other.distanceToSqr(speaker) <= HEARING_RADIUS_SQR) {
				if (ServerPlayNetworking.canSend(other, S2CStreamStart.TYPE)) {
					session.activeListeners.add(other.getUUID());
					ServerPlayNetworking.send(other, new S2CStreamStart(
							session.speakerUuid,
							session.speakerName,
							session.streamId,
							session.trackTitle,
							session.volume
					));
				}
			}
		}
	}

	private static void handleChunk(MinecraftServer server, ServerPlayer speaker, C2SStreamChunk payload) {
		ActiveSession session = SESSIONS.get(speaker.getUUID());
		if (session == null || !session.streamId.equals(payload.streamId())) {
			return;
		}
		if (!session.allowBytes(payload.mp3Frames().length)) {
			return;
		}

		S2CStreamChunk outChunk = new S2CStreamChunk(
				speaker.getUUID(),
				session.streamId,
				payload.sequence(),
				payload.mp3Frames()
		);

		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other.getUUID().equals(speaker.getUUID())) {
				continue;
			}
			boolean sameLevel = other.level() == speaker.level();
			double distSqr = sameLevel ? other.distanceToSqr(speaker) : Double.MAX_VALUE;

			if (sameLevel && distSqr <= HEARING_RADIUS_SQR) {
				if (!ServerPlayNetworking.canSend(other, S2CStreamChunk.TYPE)) {
					continue;
				}
				// Late-joining or newly-approaching players automatically receive StreamStart first!
				if (session.activeListeners.add(other.getUUID())) {
					ServerPlayNetworking.send(other, new S2CStreamStart(
							session.speakerUuid,
							session.speakerName,
							session.streamId,
							session.trackTitle,
							session.volume
					));
					if (session.paused) {
						ServerPlayNetworking.send(other, new S2CStreamState(
								session.speakerUuid,
								session.streamId,
								true,
								session.volume
						));
					}
				}
				ServerPlayNetworking.send(other, outChunk);
			} else if (distSqr > DISCONNECT_RADIUS_SQR && session.activeListeners.remove(other.getUUID())) {
				if (ServerPlayNetworking.canSend(other, S2CStreamStop.TYPE)) {
					ServerPlayNetworking.send(other, new S2CStreamStop(session.speakerUuid, session.streamId));
				}
			}
		}
	}

	private static void handleState(MinecraftServer server, ServerPlayer speaker, C2SStreamState payload) {
		ActiveSession session = SESSIONS.get(speaker.getUUID());
		if (session == null || !session.streamId.equals(payload.streamId())) {
			return;
		}
		session.paused = payload.paused();
		session.volume = payload.volume();
		session.lastChunkTimeMs = System.currentTimeMillis();

		S2CStreamState msg = new S2CStreamState(
				session.speakerUuid,
				session.streamId,
				session.paused,
				session.volume
		);
		for (UUID listenerId : session.activeListeners) {
			ServerPlayer listener = server.getPlayerList().getPlayer(listenerId);
			if (listener != null && ServerPlayNetworking.canSend(listener, S2CStreamState.TYPE)) {
				ServerPlayNetworking.send(listener, msg);
			}
		}
	}

	private static void stopSessionFor(MinecraftServer server, UUID speakerUuid, UUID expectedStreamId) {
		ActiveSession existing = SESSIONS.get(speakerUuid);
		if (existing == null) {
			return;
		}
		if (expectedStreamId != null && !existing.streamId.equals(expectedStreamId)) {
			return;
		}
		SESSIONS.remove(speakerUuid, existing);

		S2CStreamStop stopMsg = new S2CStreamStop(existing.speakerUuid, existing.streamId);
		for (UUID listenerId : existing.activeListeners) {
			ServerPlayer listener = server.getPlayerList().getPlayer(listenerId);
			if (listener != null && ServerPlayNetworking.canSend(listener, S2CStreamStop.TYPE)) {
				ServerPlayNetworking.send(listener, stopMsg);
			}
		}
	}

	private static void onServerTick(MinecraftServer server) {
		tickCounter++;
		if (SESSIONS.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean spawnNotes = (tickCounter % 14) == 0;

		Iterator<Map.Entry<UUID, ActiveSession>> it = SESSIONS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, ActiveSession> entry = it.next();
			ActiveSession session = entry.getValue();
			ServerPlayer speaker = server.getPlayerList().getPlayer(session.speakerUuid);

			if (speaker == null || (!session.paused && now - session.lastChunkTimeMs > 12_000L)) {
				it.remove();
				S2CStreamStop stopMsg = new S2CStreamStop(session.speakerUuid, session.streamId);
				for (UUID listenerId : session.activeListeners) {
					ServerPlayer listener = server.getPlayerList().getPlayer(listenerId);
					if (listener != null && ServerPlayNetworking.canSend(listener, S2CStreamStop.TYPE)) {
						ServerPlayNetworking.send(listener, stopMsg);
					}
				}
				continue;
			}

			if (spawnNotes && !session.paused && session.volume > 0.01f && speaker.level() instanceof ServerLevel serverLevel) {
				double noteHue = ((tickCounter / 14) % 24) / 24.0;
				serverLevel.sendParticles(
						ParticleTypes.NOTE,
						speaker.getX(),
						speaker.getY() + 2.15,
						speaker.getZ(),
						1,
						0.25,
						0.10,
						0.25,
						noteHue
				);
			}
		}
	}
}
