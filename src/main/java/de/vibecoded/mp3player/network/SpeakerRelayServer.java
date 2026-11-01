package de.vibecoded.mp3player.network;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.block.BoomboxBlock;
import de.vibecoded.mp3player.block.SpeakerBlock;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStop;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStop;
import de.vibecoded.mp3player.network.Mp3Payloads.SpeakerTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * Relays 3D speaker, placed Boombox, and linked Speaker network Opus audio chunks on IntegratedServer
 * (Singleplayer / Essential World Host / LAN) as well as Dedicated Fabric Servers.
 */
public final class SpeakerRelayServer {
	public static final double HEARING_RADIUS = 56.0;
	private static final double HEARING_RADIUS_SQR = HEARING_RADIUS * HEARING_RADIUS;
	private static final double DISCONNECT_RADIUS_SQR = (HEARING_RADIUS + 8.0) * (HEARING_RADIUS + 8.0);
	private static final double MAX_BOOMBOX_CONTROL_SQR = 256.0 * 256.0;
	private static final double MAX_SPEAKER_CONTROL_SQR = 512.0 * 512.0;
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
		final ResourceKey<Level> dimension;
		volatile BlockPos boomboxPos;
		volatile boolean emitFromPlayer;
		volatile List<SpeakerTarget> speakers;
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
				ResourceKey<Level> dimension,
				BlockPos boomboxPos,
				boolean emitFromPlayer,
				List<SpeakerTarget> speakers
		) {
			this.speakerUuid = speakerUuid;
			this.speakerName = speakerName;
			this.streamId = streamId;
			this.trackTitle = trackTitle;
			this.volume = volume;
			this.dimension = dimension;
			this.boomboxPos = boomboxPos != null ? boomboxPos.immutable() : null;
			this.emitFromPlayer = emitFromPlayer;
			this.speakers = speakers != null ? List.copyOf(speakers) : Collections.emptyList();
			this.paused = false;
			long now = System.currentTimeMillis();
			this.lastChunkTimeMs = now;
			this.rateWindowStartMs = now;
			this.bytesInWindow = 0;
		}

		Optional<BlockPos> optionalBoomboxPos() {
			return Optional.ofNullable(boomboxPos);
		}

		boolean hasAnyAudioSource() {
			return boomboxPos != null || emitFromPlayer || !speakers.isEmpty();
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
				updateBoomboxBlockPlaying(server, session.dimension, session.boomboxPos, false);
				for (SpeakerTarget st : session.speakers) {
					updateSpeakerBlockPlaying(server, session.dimension, st.pos(), false);
				}
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
			if (pos.equals(session.boomboxPos) && level.dimension().equals(session.dimension)) {
				toStop.add(session);
			}
		}
		for (ActiveSession session : toStop) {
			stopSessionFor(server, session.speakerUuid, session.streamId, true);
		}
	}

	/**
	 * Called immediately by SpeakerBlock when a placed Speaker block is broken or removed.
	 */
	public static void onSpeakerRemoved(MinecraftServer server, ServerLevel level, BlockPos pos) {
		if (server == null || level == null || pos == null || SESSIONS.isEmpty()) {
			return;
		}
		List<ActiveSession> snapshot = new ArrayList<>(SESSIONS.values());
		for (ActiveSession session : snapshot) {
			if (!level.dimension().equals(session.dimension)) {
				continue;
			}
			List<SpeakerTarget> current = session.speakers;
			boolean found = false;
			for (SpeakerTarget st : current) {
				if (pos.equals(st.pos())) {
					found = true;
					break;
				}
			}
			if (!found) {
				continue;
			}
			List<SpeakerTarget> remaining = new ArrayList<>(current.size());
			for (SpeakerTarget st : current) {
				if (!pos.equals(st.pos())) {
					remaining.add(st);
				}
			}
			session.speakers = Collections.unmodifiableList(remaining);

			if (!session.hasAnyAudioSource()) {
				stopSessionFor(server, session.speakerUuid, session.streamId, true);
			} else {
				broadcastStateUpdate(server, session, true);
			}
		}
	}

	private static void handleStart(MinecraftServer server, ServerPlayer speaker, C2SStreamStart payload) {
		stopSessionFor(server, speaker.getUUID(), null, false);

		if (!(speaker.level() instanceof ServerLevel sl)) {
			return;
		}
		ResourceKey<Level> dimension = sl.dimension();
		BlockPos boomboxPos = null;

		if (payload.boomboxPos() != null && payload.boomboxPos().isPresent()) {
			BlockPos candidate = payload.boomboxPos().get().immutable();
			if (!sl.isLoaded(candidate)
					|| !sl.getBlockState(candidate).is(Mp3PlayerMod.BOOMBOX_BLOCK)
					|| candidate.distToCenterSqr(speaker.position()) > MAX_BOOMBOX_CONTROL_SQR) {
				// Placed boombox no longer exists or is out of range; notify sender cleanly if no speakers
				boomboxPos = null;
			} else {
				boomboxPos = candidate;
				// If another player was already playing on this exact Boombox, stop their session cleanly
				List<ActiveSession> displaced = new ArrayList<>();
				for (ActiveSession other : SESSIONS.values()) {
					if (candidate.equals(other.boomboxPos) && dimension.equals(other.dimension)) {
						displaced.add(other);
					}
				}
				for (ActiveSession other : displaced) {
					stopSessionFor(server, other.speakerUuid, other.streamId, true);
				}
			}
		}

		List<SpeakerTarget> validSpeakers = validateSpeakers(sl, speaker, payload.speakers());
		boolean emitFromPlayer = payload.emitFromPlayer() && boomboxPos == null;

		if (boomboxPos == null && !emitFromPlayer && validSpeakers.isEmpty()) {
			if (ServerPlayNetworking.canSend(speaker, S2CStreamStop.TYPE)) {
				ServerPlayNetworking.send(speaker, new S2CStreamStop(speaker.getUUID(), payload.streamId()));
			}
			return;
		}

		ActiveSession session = new ActiveSession(
				speaker.getUUID(),
				speaker.getScoreboardName(),
				payload.streamId(),
				payload.trackTitle(),
				payload.volume(),
				dimension,
				boomboxPos,
				emitFromPlayer,
				validSpeakers
		);
		SESSIONS.put(speaker.getUUID(), session);
		syncAllBlocksPlaying(server, session, true);

		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other.getUUID().equals(speaker.getUUID())) {
				continue;
			}
			if (computeMinDistanceSqr(session, sl, speaker, other) <= HEARING_RADIUS_SQR) {
				if (ServerPlayNetworking.canSend(other, S2CStreamStart.TYPE)) {
					session.activeListeners.add(other.getUUID());
					ServerPlayNetworking.send(other, new S2CStreamStart(
							session.speakerUuid,
							session.speakerName,
							session.streamId,
							session.trackTitle,
							session.volume,
							session.optionalBoomboxPos(),
							session.emitFromPlayer,
							session.speakers
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

		ServerLevel sourceLevel = server.getLevel(session.dimension);
		if (sourceLevel == null) {
			stopSessionFor(server, session.speakerUuid, session.streamId, true);
			return;
		}

		if (session.boomboxPos != null
				&& sourceLevel.isLoaded(session.boomboxPos)
				&& !sourceLevel.getBlockState(session.boomboxPos).is(Mp3PlayerMod.BOOMBOX_BLOCK)) {
			stopSessionFor(server, session.speakerUuid, session.streamId, true);
			return;
		}

		// Prune any linked speaker blocks that were broken or replaced
		pruneInvalidSpeakers(server, sourceLevel, session);
		if (!session.hasAnyAudioSource()) {
			stopSessionFor(server, session.speakerUuid, session.streamId, true);
			return;
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
			double distSqr = computeMinDistanceSqr(session, sourceLevel, speaker, other);

			if (distSqr <= HEARING_RADIUS_SQR) {
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
							session.optionalBoomboxPos(),
							session.emitFromPlayer,
							session.speakers
					));
					if (session.paused) {
						ServerPlayNetworking.send(other, new S2CStreamState(
								session.speakerUuid,
								session.streamId,
								true,
								session.volume,
								session.optionalBoomboxPos(),
								session.emitFromPlayer,
								session.speakers
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
		ServerLevel sourceLevel = server.getLevel(session.dimension);
		if (sourceLevel == null) {
			return;
		}

		List<SpeakerTarget> oldSpeakers = session.speakers;
		List<SpeakerTarget> newSpeakers = validateSpeakers(sourceLevel, speaker, payload.speakers());

		session.paused = payload.paused();
		session.volume = payload.volume();
		session.emitFromPlayer = payload.emitFromPlayer() && session.boomboxPos == null;
		session.speakers = newSpeakers;
		session.lastChunkTimeMs = System.currentTimeMillis();

		// Turn off any speakers that were unlinked/removed from this session
		for (SpeakerTarget oldTarget : oldSpeakers) {
			boolean stillPresent = false;
			for (SpeakerTarget newTarget : newSpeakers) {
				if (oldTarget.pos().equals(newTarget.pos())) {
					stillPresent = true;
					break;
				}
			}
			if (!stillPresent && !isAnyOtherSessionUsingSpeaker(session, session.dimension, oldTarget.pos())) {
				updateSpeakerBlockPlaying(server, session.dimension, oldTarget.pos(), false);
			}
		}

		syncAllBlocksPlaying(server, session, !session.paused);
		broadcastStateUpdate(server, session, false);
	}

	private static void broadcastStateUpdate(MinecraftServer server, ActiveSession session, boolean notifyOwner) {
		S2CStreamState msg = new S2CStreamState(
				session.speakerUuid,
				session.streamId,
				session.paused,
				session.volume,
				session.optionalBoomboxPos(),
				session.emitFromPlayer,
				session.speakers
		);
		for (UUID listenerId : session.activeListeners) {
			ServerPlayer listener = server.getPlayerList().getPlayer(listenerId);
			if (listener != null && ServerPlayNetworking.canSend(listener, S2CStreamState.TYPE)) {
				ServerPlayNetworking.send(listener, msg);
			}
		}
		if (notifyOwner) {
			ServerPlayer owner = server.getPlayerList().getPlayer(session.speakerUuid);
			if (owner != null && ServerPlayNetworking.canSend(owner, S2CStreamState.TYPE)) {
				ServerPlayNetworking.send(owner, msg);
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
			updateBoomboxBlockPlaying(server, existing.dimension, existing.boomboxPos, false);
		}
		for (SpeakerTarget st : existing.speakers) {
			if (!isAnyOtherSessionUsingSpeaker(existing, existing.dimension, st.pos())) {
				updateSpeakerBlockPlaying(server, existing.dimension, st.pos(), false);
			}
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

	private static List<SpeakerTarget> validateSpeakers(
			ServerLevel level,
			ServerPlayer player,
			List<SpeakerTarget> rawSpeakers
	) {
		if (rawSpeakers == null || rawSpeakers.isEmpty() || level == null || player == null) {
			return Collections.emptyList();
		}
		Map<BlockPos, SpeakerTarget> unique = new LinkedHashMap<>();
		Vec3 playerPos = player.position();
		for (SpeakerTarget st : rawSpeakers) {
			if (st == null || st.pos() == null) {
				continue;
			}
			BlockPos pos = st.pos().immutable();
			if (pos.distToCenterSqr(playerPos) > MAX_SPEAKER_CONTROL_SQR) {
				continue;
			}
			if (level.isLoaded(pos) && !level.getBlockState(pos).is(Mp3PlayerMod.SPEAKER_BLOCK)) {
				continue;
			}
			unique.put(pos, new SpeakerTarget(pos, st.volume()));
			if (unique.size() >= Mp3Payloads.MAX_LINKED_SPEAKERS) {
				break;
			}
		}
		return unique.isEmpty() ? Collections.emptyList() : List.copyOf(unique.values());
	}

	private static void pruneInvalidSpeakers(MinecraftServer server, ServerLevel level, ActiveSession session) {
		List<SpeakerTarget> current = session.speakers;
		if (current.isEmpty()) {
			return;
		}
		List<SpeakerTarget> valid = null;
		for (int i = 0; i < current.size(); i++) {
			SpeakerTarget st = current.get(i);
			boolean broken = level.isLoaded(st.pos()) && !level.getBlockState(st.pos()).is(Mp3PlayerMod.SPEAKER_BLOCK);
			if (broken) {
				if (valid == null) {
					valid = new ArrayList<>(current.subList(0, i));
				}
			} else if (valid != null) {
				valid.add(st);
			}
		}
		if (valid != null) {
			session.speakers = Collections.unmodifiableList(valid);
			broadcastStateUpdate(server, session, true);
		}
	}

	private static double computeMinDistanceSqr(
			ActiveSession session,
			ServerLevel sourceLevel,
			ServerPlayer speaker,
			ServerPlayer listener
	) {
		if (listener.level() != sourceLevel) {
			return Double.MAX_VALUE;
		}
		Vec3 listenerPos = listener.position();
		double minDistSqr = Double.MAX_VALUE;

		if (session.boomboxPos != null) {
			minDistSqr = Math.min(minDistSqr, listenerPos.distanceToSqr(Vec3.atCenterOf(session.boomboxPos)));
		} else if (session.emitFromPlayer && speaker.level() == sourceLevel) {
			minDistSqr = Math.min(minDistSqr, listenerPos.distanceToSqr(speaker.position()));
		}

		for (SpeakerTarget st : session.speakers) {
			if (st.volume() > 0.001f) {
				double d = listenerPos.distanceToSqr(Vec3.atCenterOf(st.pos()));
				if (d < minDistSqr) {
					minDistSqr = d;
				}
			}
		}
		return minDistSqr;
	}

	private static boolean isAnyOtherSessionUsingBoombox(ActiveSession ending) {
		if (ending.boomboxPos == null || ending.dimension == null) {
			return false;
		}
		for (ActiveSession other : SESSIONS.values()) {
			if (other != ending
					&& ending.boomboxPos.equals(other.boomboxPos)
					&& ending.dimension.equals(other.dimension)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isAnyOtherSessionUsingSpeaker(
			ActiveSession excluding,
			ResourceKey<Level> dimension,
			BlockPos speakerPos
	) {
		if (speakerPos == null || dimension == null) {
			return false;
		}
		for (ActiveSession other : SESSIONS.values()) {
			if (other == excluding || !dimension.equals(other.dimension) || other.paused) {
				continue;
			}
			for (SpeakerTarget st : other.speakers) {
				if (speakerPos.equals(st.pos()) && st.volume() > 0.001f) {
					return true;
				}
			}
		}
		return false;
	}

	private static void syncAllBlocksPlaying(MinecraftServer server, ActiveSession session, boolean active) {
		boolean effectivelyPlaying = active && !session.paused && session.volume > 0.001f;
		if (session.boomboxPos != null) {
			updateBoomboxBlockPlaying(server, session.dimension, session.boomboxPos, effectivelyPlaying);
		}
		for (SpeakerTarget st : session.speakers) {
			boolean spPlaying = effectivelyPlaying && st.volume() > 0.001f;
			updateSpeakerBlockPlaying(server, session.dimension, st.pos(), spPlaying);
		}
	}

	private static void updateBoomboxBlockPlaying(
			MinecraftServer server,
			ResourceKey<Level> dimension,
			BlockPos pos,
			boolean playing
	) {
		if (server == null || pos == null || dimension == null) {
			return;
		}
		ServerLevel level = server.getLevel(dimension);
		if (level != null && level.isLoaded(pos)) {
			BlockState state = level.getBlockState(pos);
			if (state.is(Mp3PlayerMod.BOOMBOX_BLOCK) && state.getValue(BoomboxBlock.PLAYING) != playing) {
				level.setBlock(pos, state.setValue(BoomboxBlock.PLAYING, playing), Block.UPDATE_CLIENTS);
			}
		}
	}

	private static void updateSpeakerBlockPlaying(
			MinecraftServer server,
			ResourceKey<Level> dimension,
			BlockPos pos,
			boolean playing
	) {
		if (server == null || pos == null || dimension == null) {
			return;
		}
		ServerLevel level = server.getLevel(dimension);
		if (level != null && level.isLoaded(pos)) {
			BlockState state = level.getBlockState(pos);
			if (state.is(Mp3PlayerMod.SPEAKER_BLOCK) && state.getValue(SpeakerBlock.PLAYING) != playing) {
				level.setBlock(pos, state.setValue(SpeakerBlock.PLAYING, playing), Block.UPDATE_CLIENTS);
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

			ServerLevel sessionLevel = server.getLevel(session.dimension);
			if (sessionLevel == null) {
				stopSessionFor(server, session.speakerUuid, session.streamId, true);
				continue;
			}

			pruneInvalidSpeakers(server, sessionLevel, session);

			if (session.boomboxPos != null) {
				if (sessionLevel.isLoaded(session.boomboxPos)
						&& !sessionLevel.getBlockState(session.boomboxPos).is(Mp3PlayerMod.BOOMBOX_BLOCK)) {
					stopSessionFor(server, session.speakerUuid, session.streamId, true);
					continue;
				}
				if (spawnNotes && !session.paused && session.volume > 0.01f && sessionLevel.isLoaded(session.boomboxPos)) {
					double noteHue = ((tickCounter / 14) % 24) / 24.0;
					sessionLevel.sendParticles(
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
			} else if (session.emitFromPlayer
					&& spawnNotes
					&& !session.paused
					&& session.volume > 0.01f
					&& speaker.level() instanceof ServerLevel serverLevel) {
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
			// Note: Linked SpeakerBlock instances intentionally emit NO particles during playback!
		}
	}
}
