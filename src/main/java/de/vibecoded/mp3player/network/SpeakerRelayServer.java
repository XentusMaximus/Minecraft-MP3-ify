package de.vibecoded.mp3player.network;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.block.BoomboxBlock;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStop;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStop;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Relays 3D speaker and placed Boombox Opus audio chunks on IntegratedServer
 * (Singleplayer / Essential World Host / LAN) as well as Dedicated Fabric Servers.
 */
public final class SpeakerRelayServer {
	public static final double HEARING_RADIUS = 56.0;
	private static final double HEARING_RADIUS_SQR = HEARING_RADIUS * HEARING_RADIUS;
	private static final double DISCONNECT_RADIUS_SQR = (HEARING_RADIUS + 8.0) * (HEARING_RADIUS + 8.0);
	private static final double MAX_BOOMBOX_CONTROL_SQR = 256.0 * 256.0;
	private static final int MAX_BYTES_PER_SECOND = 160 * 1024;

	private static final Map<UUID, ActiveSession> SESSIONS = new ConcurrentHashMap<>();
	private static int tickCounter = 0;

	private SpeakerRelayServer() {
	}

	private static final class ActiveSession {
		final UUID speakerUuid;
		final String speakerName;
		final UUID streamId;
		final String trackTitle;
		final BlockPos boomboxPos;
		final ResourceKey<Level> boomboxDimension;
		volatile float volume;
		volatile boolean paused;
		volatile long lastChunkTimeMs;
		long rateWindowStartMs;
		int bytesInWindow;
		final Set<UUID> activeListeners = ConcurrentHashMap.newKeySet();

		ActiveSession(
				UUID speakerUuid,
				String speakerName,
				UUID streamId,
				String trackTitle,
				float volume,
				BlockPos boomboxPos,
				ResourceKey<Level> boomboxDimension
		) {
			this.speakerUuid = speakerUuid;
			this.speakerName = speakerName;
			this.streamId = streamId;
			this.trackTitle = trackTitle;
			this.volume = volume;
			this.boomboxPos = boomboxPos != null ? boomboxPos.immutable() : null;
			this.boomboxDimension = boomboxDimension;
			this.paused = false;
			long now = System.currentTimeMillis();
			this.lastChunkTimeMs = now;
			this.rateWindowStartMs = now;
			this.bytesInWindow = 0;
		}

		Optional<BlockPos> optionalBoomboxPos() {
			return Optional.ofNullable(boomboxPos);
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
			context.server().execute(() -> stopSessionFor(context.server(), speaker.getUUID(), payload.streamId(), false));
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID leaving = handler.getPlayer().getUUID();
			server.execute(() -> {
				stopSessionFor(server, leaving, null, false);
				for (ActiveSession session : SESSIONS.values()) {
					session.activeListeners.remove(leaving);
				}
			});
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (ActiveSession session : SESSIONS.values()) {
				updateBoomboxBlockPlaying(server, session, false);
			}
			SESSIONS.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(SpeakerRelayServer::onServerTick);
	}

	/**
	 * Called immediately by BoomboxBlock when a placed Boombox block is broken or removed.
	 */
	public static void onBoomboxRemoved(MinecraftServer server, ServerLevel level, BlockPos pos) {
		if (server == null || level == null || pos == null || SESSIONS.isEmpty()) {
			return;
		}
		List<ActiveSession> toStop = new ArrayList<>();
		for (ActiveSession session : SESSIONS.values()) {
			if (pos.equals(session.boomboxPos) && level.dimension().equals(session.boomboxDimension)) {
				toStop.add(session);
			}
		}
		for (ActiveSession session : toStop) {
			stopSessionFor(server, session.speakerUuid, session.streamId, true);
		}
	}

	private static void handleStart(MinecraftServer server, ServerPlayer speaker, C2SStreamStart payload) {
		stopSessionFor(server, speaker.getUUID(), null, false);

		BlockPos boomboxPos = null;
		ResourceKey<Level> boomboxDim = null;
		ServerLevel sourceLevel;
		Vec3 sourcePos;

		if (payload.boomboxPos() != null && payload.boomboxPos().isPresent()) {
			BlockPos candidate = payload.boomboxPos().get().immutable();
			if (!(speaker.level() instanceof ServerLevel sl)
					|| !sl.isLoaded(candidate)
					|| !sl.getBlockState(candidate).is(Mp3PlayerMod.BOOMBOX_BLOCK)
					|| candidate.distToCenterSqr(speaker.position()) > MAX_BOOMBOX_CONTROL_SQR) {
				// Placed boombox no longer exists or is out of range; notify sender cleanly
				if (ServerPlayNetworking.canSend(speaker, S2CStreamStop.TYPE)) {
					ServerPlayNetworking.send(speaker, new S2CStreamStop(speaker.getUUID(), payload.streamId()));
				}
				return;
			}
			boomboxPos = candidate;
			boomboxDim = sl.dimension();
			sourceLevel = sl;
			sourcePos = Vec3.atCenterOf(candidate);

			// If another player was already playing on this exact Boombox, stop their session cleanly
			List<ActiveSession> displaced = new ArrayList<>();
			for (ActiveSession other : SESSIONS.values()) {
				if (candidate.equals(other.boomboxPos) && boomboxDim.equals(other.boomboxDimension)) {
					displaced.add(other);
				}
			}
			for (ActiveSession other : displaced) {
				stopSessionFor(server, other.speakerUuid, other.streamId, true);
			}
		} else {
			if (!(speaker.level() instanceof ServerLevel sl)) {
				return;
			}
			sourceLevel = sl;
			sourcePos = speaker.position();
		}

		ActiveSession session = new ActiveSession(
				speaker.getUUID(),
				speaker.getScoreboardName(),
				payload.streamId(),
				payload.trackTitle(),
				payload.volume(),
				boomboxPos,
				boomboxDim
		);
		SESSIONS.put(speaker.getUUID(), session);
		updateBoomboxBlockPlaying(server, session, true);

		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other.getUUID().equals(speaker.getUUID())) {
				continue;
			}
			if (other.level() == sourceLevel && other.position().distanceToSqr(sourcePos) <= HEARING_RADIUS_SQR) {
				if (ServerPlayNetworking.canSend(other, S2CStreamStart.TYPE)) {
					session.activeListeners.add(other.getUUID());
					ServerPlayNetworking.send(other, new S2CStreamStart(
							session.speakerUuid,
							session.speakerName,
							session.streamId,
							session.trackTitle,
							session.volume,
							session.optionalBoomboxPos()
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

		ServerLevel sourceLevel;
		Vec3 sourcePos;
		if (session.boomboxPos != null) {
			sourceLevel = server.getLevel(session.boomboxDimension);
			if (sourceLevel == null
					|| (sourceLevel.isLoaded(session.boomboxPos)
					&& !sourceLevel.getBlockState(session.boomboxPos).is(Mp3PlayerMod.BOOMBOX_BLOCK))) {
				stopSessionFor(server, session.speakerUuid, session.streamId, true);
				return;
			}
			sourcePos = Vec3.atCenterOf(session.boomboxPos);
		} else {
			if (!(speaker.level() instanceof ServerLevel sl)) {
				return;
			}
			sourceLevel = sl;
			sourcePos = speaker.position();
		}

		S2CStreamChunk outChunk = new S2CStreamChunk(
				speaker.getUUID(),
				session.streamId,
				payload.sequence(),
				payload.mp3Frames(),
				session.optionalBoomboxPos()
		);

		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other.getUUID().equals(speaker.getUUID())) {
				continue;
			}
			boolean sameLevel = other.level() == sourceLevel;
			double distSqr = sameLevel ? other.position().distanceToSqr(sourcePos) : Double.MAX_VALUE;

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
							session.volume,
							session.optionalBoomboxPos()
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
		updateBoomboxBlockPlaying(server, session, !session.paused);

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

	private static void stopSessionFor(
			MinecraftServer server,
			UUID speakerUuid,
			UUID expectedStreamId,
			boolean notifyOwner
	) {
		ActiveSession existing = SESSIONS.get(speakerUuid);
		if (existing == null) {
			return;
		}
		if (expectedStreamId != null && !existing.streamId.equals(expectedStreamId)) {
			return;
		}
		SESSIONS.remove(speakerUuid, existing);

		if (existing.boomboxPos != null && !isAnyOtherSessionUsingBoombox(existing)) {
			updateBoomboxBlockPlaying(server, existing, false);
		}

		S2CStreamStop stopMsg = new S2CStreamStop(existing.speakerUuid, existing.streamId);
		for (UUID listenerId : existing.activeListeners) {
			ServerPlayer listener = server.getPlayerList().getPlayer(listenerId);
			if (listener != null && ServerPlayNetworking.canSend(listener, S2CStreamStop.TYPE)) {
				ServerPlayNetworking.send(listener, stopMsg);
			}
		}
		if (notifyOwner) {
			ServerPlayer owner = server.getPlayerList().getPlayer(existing.speakerUuid);
			if (owner != null && ServerPlayNetworking.canSend(owner, S2CStreamStop.TYPE)) {
				ServerPlayNetworking.send(owner, stopMsg);
			}
		}
	}

	private static boolean isAnyOtherSessionUsingBoombox(ActiveSession ending) {
		if (ending.boomboxPos == null || ending.boomboxDimension == null) {
			return false;
		}
		for (ActiveSession other : SESSIONS.values()) {
			if (other != ending
					&& ending.boomboxPos.equals(other.boomboxPos)
					&& ending.boomboxDimension.equals(other.boomboxDimension)) {
				return true;
			}
		}
		return false;
	}

	private static void updateBoomboxBlockPlaying(MinecraftServer server, ActiveSession session, boolean playing) {
		if (server == null || session.boomboxPos == null || session.boomboxDimension == null) {
			return;
		}
		ServerLevel level = server.getLevel(session.boomboxDimension);
		if (level != null && level.isLoaded(session.boomboxPos)) {
			BlockState state = level.getBlockState(session.boomboxPos);
			if (state.is(Mp3PlayerMod.BOOMBOX_BLOCK) && state.getValue(BoomboxBlock.PLAYING) != playing) {
				level.setBlock(session.boomboxPos, state.setValue(BoomboxBlock.PLAYING, playing), Block.UPDATE_CLIENTS);
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

		List<ActiveSession> snapshot = new ArrayList<>(SESSIONS.values());
		for (ActiveSession session : snapshot) {
			ServerPlayer speaker = server.getPlayerList().getPlayer(session.speakerUuid);

			if (speaker == null || (!session.paused && now - session.lastChunkTimeMs > 12_000L)) {
				stopSessionFor(server, session.speakerUuid, session.streamId, false);
				continue;
			}

			if (session.boomboxPos != null) {
				ServerLevel boomboxLevel = server.getLevel(session.boomboxDimension);
				if (boomboxLevel == null
						|| (boomboxLevel.isLoaded(session.boomboxPos)
						&& !boomboxLevel.getBlockState(session.boomboxPos).is(Mp3PlayerMod.BOOMBOX_BLOCK))) {
					stopSessionFor(server, session.speakerUuid, session.streamId, true);
					continue;
				}
				if (spawnNotes && !session.paused && session.volume > 0.01f && boomboxLevel.isLoaded(session.boomboxPos)) {
					double noteHue = ((tickCounter / 14) % 24) / 24.0;
					boomboxLevel.sendParticles(
							ParticleTypes.NOTE,
							session.boomboxPos.getX() + 0.5,
							session.boomboxPos.getY() + 0.95,
							session.boomboxPos.getZ() + 0.5,
							1,
							0.22,
							0.08,
							0.22,
							noteHue
					);
				}
			} else if (spawnNotes && !session.paused && session.volume > 0.01f && speaker.level() instanceof ServerLevel serverLevel) {
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
