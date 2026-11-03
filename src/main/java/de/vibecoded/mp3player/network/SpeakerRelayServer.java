package de.vibecoded.mp3player.network;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.block.BoomboxBlock;
import de.vibecoded.mp3player.block.SpeakerBlock;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SQueueAction;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SQueueAdd;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStop;
import de.vibecoded.mp3player.network.Mp3Payloads.QueueActionType;
import de.vibecoded.mp3player.network.Mp3Payloads.QueueEntryInfo;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CQueuePlayTrigger;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CSpeakerQueueSync;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStop;
import de.vibecoded.mp3player.network.Mp3Payloads.SpeakerTarget;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Also coordinates the shared Speaker Playback Queue so multiple players linked to speakers
 * play sequentially without overlapping streams.
 */
public final class SpeakerRelayServer {
	public static final double HEARING_RADIUS = 80.0;
	private static final double HEARING_RADIUS_SQR = HEARING_RADIUS * HEARING_RADIUS;
	private static final double DISCONNECT_RADIUS_SQR = (HEARING_RADIUS + 8.0) * (HEARING_RADIUS + 8.0);
	public static final double SPEAKER_NETWORK_RADIUS = 96.0;
	private static final double SPEAKER_NETWORK_RADIUS_SQR = SPEAKER_NETWORK_RADIUS * SPEAKER_NETWORK_RADIUS;
	private static final double MAX_BOOMBOX_CONTROL_SQR = 256.0 * 256.0;
	private static final double MAX_SPEAKER_CONTROL_SQR = 512.0 * 512.0;
	private static final int MAX_BYTES_PER_SECOND = 160 * 1024;

	private static final Map<UUID, ActiveSession> SESSIONS = new ConcurrentHashMap<>();
	private static final Map<UUID, SpeakerNetworkQueue> NETWORKS = new ConcurrentHashMap<>();
	private static final Map<UUID, UUID> PLAYER_NETWORK = new ConcurrentHashMap<>();
	private static int tickCounter = 0;

	private SpeakerRelayServer() {
	}

	private static final class ServerQueueEntry {
		final UUID entryId;
		final UUID ownerUuid;
		final String ownerName;
		final String trackId;
		volatile String trackTitle;
		volatile int durationSeconds;
		volatile boolean paused;
		volatile long triggeredAtMs;
		volatile boolean streamStarted;

		ServerQueueEntry(
				UUID entryId,
				UUID ownerUuid,
				String ownerName,
				String trackId,
				String trackTitle,
				int durationSeconds
		) {
			this.entryId = entryId;
			this.ownerUuid = ownerUuid;
			this.ownerName = ownerName;
			this.trackId = trackId;
			this.trackTitle = trackTitle;
			this.durationSeconds = Math.max(0, durationSeconds);
			this.paused = false;
			this.triggeredAtMs = System.currentTimeMillis();
			this.streamStarted = false;
		}

		QueueEntryInfo toInfo(boolean nowPlaying) {
			return new QueueEntryInfo(
					entryId,
					ownerUuid,
					ownerName,
					trackId,
					trackTitle,
					durationSeconds,
					nowPlaying,
					paused
			);
		}
	}

	/**
	 * Represents an independent local Speaker Network cluster (within the 96-block scan radius)
	 * and its own Playback Queue & Skip-Vote state. Multiple networks outside each other's scan
	 * radius (e.g., a "Home-HiFi" vs. a "Club") operate independently in parallel.
	 */
	private static final class SpeakerNetworkQueue {
		final UUID networkId = UUID.randomUUID();
		final ResourceKey<Level> dimension;
		ServerQueueEntry activeEntry;
		final Deque<ServerQueueEntry> waitingEntries = new ArrayDeque<>();
		final Map<UUID, List<SpeakerTarget>> playerLinkedSpeakers = new ConcurrentHashMap<>();
		final Set<UUID> skipVotes = ConcurrentHashMap.newKeySet();

		SpeakerNetworkQueue(ResourceKey<Level> dimension) {
			this.dimension = dimension;
		}

		boolean hasQueue() {
			return activeEntry != null || !waitingEntries.isEmpty();
		}

		boolean isEmptyNetwork() {
			return activeEntry == null && waitingEntries.isEmpty() && playerLinkedSpeakers.isEmpty();
		}

		boolean isSpeakerInNetworkRadius(BlockPos pos, UUID excludingPlayer) {
			if (pos == null) {
				return false;
			}
			for (Map.Entry<UUID, List<SpeakerTarget>> entry : playerLinkedSpeakers.entrySet()) {
				if (excludingPlayer != null && excludingPlayer.equals(entry.getKey())) {
					continue;
				}
				for (SpeakerTarget st : entry.getValue()) {
					if (st.pos().distSqr(pos) <= SPEAKER_NETWORK_RADIUS_SQR) {
						return true;
					}
				}
			}
			return false;
		}

		boolean isAnySpeakerInNetworkRadius(List<SpeakerTarget> speakers, UUID excludingPlayer) {
			if (speakers == null || speakers.isEmpty()) {
				return false;
			}
			for (SpeakerTarget st : speakers) {
				if (isSpeakerInNetworkRadius(st.pos(), excludingPlayer)) {
					return true;
				}
			}
			return false;
		}

		List<QueueEntryInfo> buildSnapshot() {
			if (activeEntry == null && waitingEntries.isEmpty()) {
				return Collections.emptyList();
			}
			List<QueueEntryInfo> list = new ArrayList<>(1 + waitingEntries.size());
			if (activeEntry != null) {
				list.add(activeEntry.toInfo(true));
			}
			for (ServerQueueEntry entry : waitingEntries) {
				if (list.size() >= Mp3Payloads.MAX_QUEUE_ENTRIES) {
					break;
				}
				list.add(entry.toInfo(false));
			}
			return list;
		}

		Set<UUID> getParticipants(MinecraftServer server) {
			Set<UUID> participants = new LinkedHashSet<>();
			if (activeEntry != null && isPlayerInDimension(server, dimension, activeEntry.ownerUuid)) {
				participants.add(activeEntry.ownerUuid);
			}
			for (ServerQueueEntry entry : waitingEntries) {
				if (isPlayerInDimension(server, dimension, entry.ownerUuid)) {
					participants.add(entry.ownerUuid);
				}
			}
			return participants;
		}

		S2CSpeakerQueueSync buildSyncPayload(MinecraftServer server, Optional<List<BlockPos>> retainedSpeakers) {
			if (activeEntry == null && waitingEntries.isEmpty()) {
				skipVotes.clear();
				return new S2CSpeakerQueueSync(Collections.emptyList(), 0, 0, Collections.emptyList(), retainedSpeakers);
			}
			Set<UUID> participants = getParticipants(server);
			skipVotes.retainAll(participants);
			List<QueueEntryInfo> list = buildSnapshot();
			List<UUID> voters = skipVotes.isEmpty() ? Collections.emptyList() : List.copyOf(skipVotes);
			return new S2CSpeakerQueueSync(list, voters.size(), participants.size(), voters, retainedSpeakers);
		}

		private static boolean isPlayerInDimension(MinecraftServer server, ResourceKey<Level> dimension, UUID playerUuid) {
			if (server == null || dimension == null || playerUuid == null) {
				return false;
			}
			ServerPlayer p = server.getPlayerList().getPlayer(playerUuid);
			return p != null && dimension.equals(p.level().dimension());
		}

		List<SpeakerTarget> computeCombinedSpeakers(ServerLevel level, UUID primaryOwner) {
			Map<BlockPos, SpeakerTarget> merged = new LinkedHashMap<>();
			if (primaryOwner != null) {
				List<SpeakerTarget> primary = playerLinkedSpeakers.get(primaryOwner);
				if (primary != null) {
					for (SpeakerTarget st : primary) {
						if (isSpeakerValidInLevel(level, st.pos())) {
							merged.put(st.pos(), st);
						}
					}
				}
			}
			for (Map.Entry<UUID, List<SpeakerTarget>> entry : playerLinkedSpeakers.entrySet()) {
				if (primaryOwner != null && primaryOwner.equals(entry.getKey())) {
					continue;
				}
				for (SpeakerTarget st : entry.getValue()) {
					if (merged.size() >= Mp3Payloads.MAX_LINKED_SPEAKERS) {
						break;
					}
					if (!merged.containsKey(st.pos()) && isSpeakerValidInLevel(level, st.pos())) {
						merged.put(st.pos(), st);
					}
				}
			}
			return merged.isEmpty() ? Collections.emptyList() : List.copyOf(merged.values());
		}

		void removeSpeakerPos(BlockPos pos) {
			for (Map.Entry<UUID, List<SpeakerTarget>> entry : playerLinkedSpeakers.entrySet()) {
				List<SpeakerTarget> list = entry.getValue();
				boolean found = false;
				for (SpeakerTarget st : list) {
					if (pos.equals(st.pos())) {
						found = true;
						break;
					}
				}
				if (found) {
					List<SpeakerTarget> updated = new ArrayList<>(list.size());
					for (SpeakerTarget st : list) {
						if (!pos.equals(st.pos())) {
							updated.add(st);
						}
					}
					if (updated.isEmpty()) {
						playerLinkedSpeakers.remove(entry.getKey());
					} else {
						entry.setValue(Collections.unmodifiableList(updated));
					}
				}
			}
		}

		private static boolean isSpeakerValidInLevel(ServerLevel level, BlockPos pos) {
			if (level == null || pos == null) {
				return false;
			}
			return !level.isLoaded(pos) || level.getBlockState(pos).is(Mp3PlayerMod.SPEAKER_BLOCK);
		}
	}

	private static SpeakerNetworkQueue getPlayerNetwork(UUID playerUuid) {
		if (playerUuid == null) {
			return null;
		}
		UUID netId = PLAYER_NETWORK.get(playerUuid);
		return netId != null ? NETWORKS.get(netId) : null;
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
			context.server().execute(() -> stopSessionFor(context.server(), speaker.getUUID(), payload.streamId(), false, true));
		});

		ServerPlayNetworking.registerGlobalReceiver(C2SQueueAdd.TYPE, (payload, context) -> {
			ServerPlayer speaker = context.player();
			context.server().execute(() -> handleQueueAdd(context.server(), speaker, payload));
		});

		ServerPlayNetworking.registerGlobalReceiver(C2SQueueAction.TYPE, (payload, context) -> {
			ServerPlayer speaker = context.player();
			context.server().execute(() -> handleQueueAction(context.server(), speaker, payload));
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer joining = handler.getPlayer();
			server.execute(() -> {
				PLAYER_NETWORK.remove(joining.getUUID());
				if (ServerPlayNetworking.canSend(joining, S2CSpeakerQueueSync.TYPE)) {
					ServerPlayNetworking.send(
							joining,
							new S2CSpeakerQueueSync(Collections.emptyList(), 0, 0, Collections.emptyList())
					);
				}
			});
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID leaving = handler.getPlayer().getUUID();
			server.execute(() -> {
				stopSessionFor(server, leaving, null, false, false);
				for (ActiveSession session : SESSIONS.values()) {
					session.activeListeners.remove(leaving);
				}
				PLAYER_NETWORK.remove(leaving);
				List<SpeakerNetworkQueue> allNets = new ArrayList<>(NETWORKS.values());
				for (SpeakerNetworkQueue queue : allNets) {
					queue.playerLinkedSpeakers.remove(leaving);
					queue.skipVotes.remove(leaving);
					boolean changed = queue.waitingEntries.removeIf(q -> leaving.equals(q.ownerUuid));
					ServerLevel sl = server.getLevel(queue.dimension);
					if (queue.activeEntry != null && leaving.equals(queue.activeEntry.ownerUuid)) {
						advanceQueue(server, sl, queue, false);
					} else if (changed) {
						checkSkipVoteAfterQueueChange(server, sl, queue);
					}
					if (queue.isEmptyNetwork()) {
						NETWORKS.remove(queue.networkId, queue);
					}
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
			NETWORKS.clear();
			PLAYER_NETWORK.clear();
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
			stopSessionFor(server, session.speakerUuid, session.streamId, true, true);
		}
	}

	/**
	 * Called immediately by SpeakerBlock when a placed Speaker block is broken or removed.
	 */
	public static void onSpeakerRemoved(MinecraftServer server, ServerLevel level, BlockPos pos) {
		if (server == null || level == null || pos == null) {
			return;
		}
		for (SpeakerNetworkQueue queue : NETWORKS.values()) {
			if (level.dimension().equals(queue.dimension)) {
				queue.removeSpeakerPos(pos);
				if (queue.isEmptyNetwork()) {
					NETWORKS.remove(queue.networkId, queue);
				}
			}
		}
		if (SESSIONS.isEmpty()) {
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
				stopSessionFor(server, session.speakerUuid, session.streamId, true, true);
			} else {
				broadcastStateUpdate(server, session, true);
			}
		}
	}

	// ===================== SPEAKER NETWORK & PLAYBACK QUEUE HANDLERS =====================

	/**
	 * Resolves which independent 96-block SpeakerNetworkQueue the player belongs to based on
	 * their linked speakers.
	 * - If the player links to speakers outside the 96-block scan radius of any existing network,
	 *   a separate independent SpeakerNetworkQueue is created (e.g., Home-HiFi vs. Club).
	 * - If the player connects to a network that already has an active queue, any previous
	 *   separate network of that player is closed automatically.
	 */
	private static SpeakerNetworkQueue resolvePlayerNetwork(
			MinecraftServer server,
			ServerLevel level,
			ServerPlayer player,
			List<SpeakerTarget> validSpeakers
	) {
		UUID playerUuid = player.getUUID();
		ResourceKey<Level> dimension = level.dimension();
		SpeakerNetworkQueue currentNet = getPlayerNetwork(playerUuid);

		if (currentNet != null && !dimension.equals(currentNet.dimension)) {
			closeOrLeaveSeparateNetwork(server, level, playerUuid, currentNet);
			currentNet = null;
		}

		if (validSpeakers == null || validSpeakers.isEmpty()) {
			if (currentNet != null) {
				closeOrLeaveSeparateNetwork(server, level, playerUuid, currentNet);
			}
			if (ServerPlayNetworking.canSend(player, S2CSpeakerQueueSync.TYPE)) {
				ServerPlayNetworking.send(
						player,
						new S2CSpeakerQueueSync(Collections.emptyList(), 0, 0, Collections.emptyList(), Optional.empty())
				);
			}
			return null;
		}

		// 1. Check if any of the player's linked speakers connects to an existing network with a queue
		SpeakerNetworkQueue existingQueueNet = null;
		for (SpeakerNetworkQueue candidate : NETWORKS.values()) {
			if (candidate == currentNet || !dimension.equals(candidate.dimension)) {
				continue;
			}
			if (candidate.hasQueue() && candidate.isAnySpeakerInNetworkRadius(validSpeakers, playerUuid)) {
				existingQueueNet = candidate;
				break;
			}
		}

		if (existingQueueNet != null) {
			if (currentNet != null && currentNet != existingQueueNet) {
				closeOrLeaveSeparateNetwork(server, level, playerUuid, currentNet);
			}
			List<SpeakerTarget> filtered = new ArrayList<>();
			for (SpeakerTarget st : validSpeakers) {
				if (existingQueueNet.isSpeakerInNetworkRadius(st.pos(), playerUuid)) {
					filtered.add(st);
				}
			}
			List<SpeakerTarget> effectiveSpeakers = filtered.isEmpty() ? validSpeakers : Collections.unmodifiableList(filtered);
			PLAYER_NETWORK.put(playerUuid, existingQueueNet.networkId);
			existingQueueNet.playerLinkedSpeakers.put(playerUuid, effectiveSpeakers);

			if (effectiveSpeakers.size() < validSpeakers.size() && ServerPlayNetworking.canSend(player, S2CSpeakerQueueSync.TYPE)) {
				List<BlockPos> retainedPos = new ArrayList<>(effectiveSpeakers.size());
				for (SpeakerTarget st : effectiveSpeakers) {
					retainedPos.add(st.pos());
				}
				ServerPlayNetworking.send(
						player,
						existingQueueNet.buildSyncPayload(server, Optional.of(Collections.unmodifiableList(retainedPos)))
				);
			}
			return existingQueueNet;
		}

		// 2. If the player already has a currentNet, check whether their speakers are still within its 96-block radius
		if (currentNet != null) {
			boolean stillInSameArea = currentNet.isAnySpeakerInNetworkRadius(validSpeakers, null);
			if (!stillInSameArea) {
				closeOrLeaveSeparateNetwork(server, level, playerUuid, currentNet);
				currentNet = null;
			}
		}

		// 3. Otherwise, find a nearby network within 96 blocks or create a separate independent network
		if (currentNet == null) {
			for (SpeakerNetworkQueue candidate : NETWORKS.values()) {
				if (dimension.equals(candidate.dimension) && candidate.isAnySpeakerInNetworkRadius(validSpeakers, playerUuid)) {
					currentNet = candidate;
					break;
				}
			}
			if (currentNet == null) {
				currentNet = new SpeakerNetworkQueue(dimension);
				NETWORKS.put(currentNet.networkId, currentNet);
			}
		}

		PLAYER_NETWORK.put(playerUuid, currentNet.networkId);
		currentNet.playerLinkedSpeakers.put(playerUuid, validSpeakers);
		return currentNet;
	}

	private static void closeOrLeaveSeparateNetwork(
			MinecraftServer server,
			ServerLevel level,
			UUID playerUuid,
			SpeakerNetworkQueue oldNet
	) {
		if (oldNet == null || playerUuid == null) {
			return;
		}
		PLAYER_NETWORK.remove(playerUuid, oldNet.networkId);
		oldNet.playerLinkedSpeakers.remove(playerUuid);
		oldNet.skipVotes.remove(playerUuid);
		boolean removedWaiting = oldNet.waitingEntries.removeIf(q -> playerUuid.equals(q.ownerUuid));

		if (oldNet.activeEntry != null && playerUuid.equals(oldNet.activeEntry.ownerUuid)) {
			advanceQueue(server, level, oldNet, true);
		} else {
			ActiveSession ownSession = SESSIONS.get(playerUuid);
			if (ownSession != null && !ownSession.speakers.isEmpty()) {
				stopSessionFor(server, playerUuid, ownSession.streamId, true, false);
			}
			if (oldNet.activeEntry != null) {
				ActiveSession activeSession = SESSIONS.get(oldNet.activeEntry.ownerUuid);
				if (activeSession != null && oldNet.dimension.equals(activeSession.dimension)) {
					updateSessionSpeakersFromPool(server, level, oldNet, activeSession);
				}
			}
			if (removedWaiting) {
				checkSkipVoteAfterQueueChange(server, level, oldNet);
			}
		}

		if (oldNet.isEmptyNetwork()) {
			NETWORKS.remove(oldNet.networkId, oldNet);
		} else {
			broadcastQueueSync(server, oldNet);
		}
	}

	private static void handleQueueAdd(MinecraftServer server, ServerPlayer player, C2SQueueAdd payload) {
		if (!(player.level() instanceof ServerLevel sl)) {
			return;
		}
		List<SpeakerTarget> validSpeakers = validateSpeakers(sl, player, payload.speakers());
		SpeakerNetworkQueue queue = resolvePlayerNetwork(server, sl, player, validSpeakers);
		if (queue == null) {
			queue = getPlayerNetwork(player.getUUID());
			if (queue == null) {
				queue = new SpeakerNetworkQueue(sl.dimension());
				NETWORKS.put(queue.networkId, queue);
				PLAYER_NETWORK.put(player.getUUID(), queue.networkId);
			}
		}

		ServerQueueEntry newEntry = new ServerQueueEntry(
				UUID.randomUUID(),
				player.getUUID(),
				player.getScoreboardName(),
				payload.trackId(),
				payload.trackTitle(),
				payload.durationSeconds()
		);

		// 1. If this speaker network is completely idle, start playing immediately!
		if (queue.activeEntry == null && !isAnySessionUsingSpeakersInNetwork(queue, null)) {
			queue.skipVotes.clear();
			queue.activeEntry = newEntry;
			broadcastQueueSync(server, queue);
			triggerQueueEntryPlayback(server, sl, queue, newEntry);
			return;
		}

		// 2. If the requesting player is ALREADY the active DJ on this network and clicked directly on a track while queue is empty
		if (queue.activeEntry != null
				&& queue.activeEntry.ownerUuid.equals(player.getUUID())
				&& payload.switchActiveIfOwner()
				&& queue.waitingEntries.isEmpty()) {
			queue.skipVotes.clear();
			queue.activeEntry = newEntry;
			broadcastQueueSync(server, queue);
			triggerQueueEntryPlayback(server, sl, queue, newEntry);
			return;
		}

		// 3. Otherwise, append to this speaker network's waiting queue so playbacks never overlap!
		if (queue.waitingEntries.size() < Mp3Payloads.MAX_QUEUE_ENTRIES) {
			queue.waitingEntries.addLast(newEntry);
			broadcastQueueSync(server, queue);
		}
	}

	private static void handleQueueAction(MinecraftServer server, ServerPlayer player, C2SQueueAction payload) {
		if (!(player.level() instanceof ServerLevel sl)) {
			return;
		}
		List<SpeakerTarget> validSpeakers = validateSpeakers(sl, player, payload.speakers());

		if (payload.action() == QueueActionType.SYNC_SPEAKERS) {
			SpeakerNetworkQueue queue = resolvePlayerNetwork(server, sl, player, validSpeakers);
			if (queue != null) {
				if (queue.activeEntry != null) {
					ActiveSession activeSession = SESSIONS.get(queue.activeEntry.ownerUuid);
					if (activeSession != null && sl.dimension().equals(activeSession.dimension)) {
						updateSessionSpeakersFromPool(server, sl, queue, activeSession);
					}
				}
				broadcastQueueSync(server, queue);
			}
			return;
		}

		SpeakerNetworkQueue queue = !validSpeakers.isEmpty()
				? resolvePlayerNetwork(server, sl, player, validSpeakers)
				: getPlayerNetwork(player.getUUID());
		if (queue == null) {
			return;
		}

		switch (payload.action()) {
			case REMOVE_ENTRY -> {
				if (payload.targetEntryId() == null || payload.targetEntryId().isEmpty()) {
					return;
				}
				UUID targetId = payload.targetEntryId().get();
				if (queue.activeEntry != null && queue.activeEntry.entryId.equals(targetId)) {
					handleSkipVote(server, sl, queue, player);
				} else {
					boolean removed = false;
					Iterator<ServerQueueEntry> it = queue.waitingEntries.iterator();
					while (it.hasNext()) {
						ServerQueueEntry entry = it.next();
						if (entry.entryId.equals(targetId) && canModifyQueueEntry(player, entry)) {
							it.remove();
							removed = true;
							break;
						}
					}
					if (removed) {
						checkSkipVoteAfterQueueChange(server, sl, queue);
					}
				}
			}
			case SKIP_CURRENT -> {
				if (queue.activeEntry != null) {
					handleSkipVote(server, sl, queue, player);
				}
			}
			case ADVANCE_FINISHED -> {
				if (queue.activeEntry != null && queue.activeEntry.ownerUuid.equals(player.getUUID())) {
					if (payload.targetEntryId() == null
							|| payload.targetEntryId().isEmpty()
							|| payload.targetEntryId().get().equals(queue.activeEntry.entryId)) {
						advanceQueue(server, sl, queue, false);
					}
				}
			}
			case CLEAR_ALL -> {
				boolean isOp = player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR);
				boolean changed;
				if (isOp) {
					changed = !queue.waitingEntries.isEmpty();
					queue.waitingEntries.clear();
				} else {
					changed = queue.waitingEntries.removeIf(e -> player.getUUID().equals(e.ownerUuid));
				}
				if (changed) {
					checkSkipVoteAfterQueueChange(server, sl, queue);
				}
			}
			default -> {
			}
		}
	}

	/**
	 * Handles a player's request to skip the currently playing speaker queue song.
	 * If only 1 player has songs in the queue, skips immediately.
	 * If multiple players have songs in the queue, requires all of them to vote before skipping.
	 */
	private static void handleSkipVote(
			MinecraftServer server,
			ServerLevel sl,
			SpeakerNetworkQueue queue,
			ServerPlayer player
	) {
		if (queue.activeEntry == null || player == null) {
			return;
		}
		Set<UUID> participants = queue.getParticipants(server);
		queue.skipVotes.retainAll(participants);

		// Instant skip when only 1 player has songs in the queue
		if (participants.size() <= 1) {
			if (participants.isEmpty() || participants.contains(player.getUUID())) {
				queue.skipVotes.clear();
				advanceQueue(server, sl, queue, true);
			}
			return;
		}

		// Only players who have added songs to the queue can vote
		if (!participants.contains(player.getUUID())) {
			return;
		}

		queue.skipVotes.add(player.getUUID());
		if (queue.skipVotes.containsAll(participants)) {
			queue.skipVotes.clear();
			advanceQueue(server, sl, queue, true);
		} else {
			broadcastQueueSync(server, queue);
		}
	}

	private static void checkSkipVoteAfterQueueChange(
			MinecraftServer server,
			ServerLevel sl,
			SpeakerNetworkQueue queue
	) {
		if (queue.activeEntry == null) {
			queue.skipVotes.clear();
			broadcastQueueSync(server, queue);
			return;
		}
		Set<UUID> participants = queue.getParticipants(server);
		queue.skipVotes.retainAll(participants);
		if (!queue.skipVotes.isEmpty() && !participants.isEmpty() && queue.skipVotes.containsAll(participants)) {
			queue.skipVotes.clear();
			advanceQueue(server, sl, queue, true);
		} else {
			broadcastQueueSync(server, queue);
		}
	}

	private static boolean canModifyQueueEntry(ServerPlayer player, ServerQueueEntry entry) {
		if (player == null || entry == null) {
			return false;
		}
		return player.getUUID().equals(entry.ownerUuid)
				|| player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR);
	}

	private static void advanceQueue(
			MinecraftServer server,
			ServerLevel level,
			SpeakerNetworkQueue queue,
			boolean notifyOldOwnerToStop
	) {
		ServerQueueEntry oldActive = queue.activeEntry;
		queue.activeEntry = null;
		queue.skipVotes.clear();

		if (oldActive != null) {
			stopSessionFor(server, oldActive.ownerUuid, null, notifyOldOwnerToStop, false);
		}

		while (!queue.waitingEntries.isEmpty()) {
			ServerQueueEntry next = queue.waitingEntries.pollFirst();
			ServerPlayer nextOwner = server.getPlayerList().getPlayer(next.ownerUuid);
			if (nextOwner != null && nextOwner.level() instanceof ServerLevel ownerLevel && ownerLevel.dimension().equals(queue.dimension)) {
				next.triggeredAtMs = System.currentTimeMillis();
				next.streamStarted = false;
				next.paused = false;
				queue.activeEntry = next;
				broadcastQueueSync(server, queue);
				triggerQueueEntryPlayback(server, ownerLevel, queue, next);
				return;
			}
		}

		broadcastQueueSync(server, queue);
		if (queue.isEmptyNetwork()) {
			NETWORKS.remove(queue.networkId, queue);
		}
	}

	private static void triggerQueueEntryPlayback(
			MinecraftServer server,
			ServerLevel level,
			SpeakerNetworkQueue queue,
			ServerQueueEntry entry
	) {
		ServerPlayer targetPlayer = server.getPlayerList().getPlayer(entry.ownerUuid);
		if (targetPlayer == null || !ServerPlayNetworking.canSend(targetPlayer, S2CQueuePlayTrigger.TYPE)) {
			advanceQueue(server, level, queue, false);
			return;
		}
		List<SpeakerTarget> combinedSpeakers = queue.computeCombinedSpeakers(level, entry.ownerUuid);
		entry.triggeredAtMs = System.currentTimeMillis();
		ServerPlayNetworking.send(targetPlayer, new S2CQueuePlayTrigger(
				entry.entryId,
				entry.trackId,
				entry.trackTitle,
				combinedSpeakers
		));
	}

	private static void broadcastQueueSync(
			MinecraftServer server,
			SpeakerNetworkQueue queue
	) {
		if (server == null || queue == null) {
			return;
		}
		S2CSpeakerQueueSync syncMsg = queue.buildSyncPayload(server, Optional.empty());
		Set<UUID> recipients = new LinkedHashSet<>(queue.playerLinkedSpeakers.keySet());
		recipients.addAll(queue.getParticipants(server));
		for (Map.Entry<UUID, UUID> entry : PLAYER_NETWORK.entrySet()) {
			if (queue.networkId.equals(entry.getValue())) {
				recipients.add(entry.getKey());
			}
		}
		for (UUID playerId : recipients) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null
					&& player.level().dimension().equals(queue.dimension)
					&& ServerPlayNetworking.canSend(player, S2CSpeakerQueueSync.TYPE)) {
				ServerPlayNetworking.send(player, syncMsg);
			}
		}
	}

	private static void updateSessionSpeakersFromPool(
			MinecraftServer server,
			ServerLevel level,
			SpeakerNetworkQueue queue,
			ActiveSession session
	) {
		List<SpeakerTarget> oldSpeakers = session.speakers;
		List<SpeakerTarget> updated = queue.computeCombinedSpeakers(level, session.speakerUuid);
		session.speakers = updated;

		for (SpeakerTarget oldTarget : oldSpeakers) {
			boolean stillPresent = false;
			for (SpeakerTarget newTarget : updated) {
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
		broadcastStateUpdate(server, session, true);
	}

	private static boolean isAnySessionUsingSpeakersInNetwork(SpeakerNetworkQueue queue, UUID excludingSpeakerUuid) {
		if (queue == null) {
			return false;
		}
		for (ActiveSession session : SESSIONS.values()) {
			if (queue.dimension.equals(session.dimension)
					&& (excludingSpeakerUuid == null || !excludingSpeakerUuid.equals(session.speakerUuid))
					&& !session.speakers.isEmpty()
					&& queue.isAnySpeakerInNetworkRadius(session.speakers, null)) {
				return true;
			}
		}
		return false;
	}

	private static void handleStart(MinecraftServer server, ServerPlayer speaker, C2SStreamStart payload) {
		stopSessionFor(server, speaker.getUUID(), null, false, false);

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
					stopSessionFor(server, other.speakerUuid, other.streamId, true, true);
				}
			}
		}

		List<SpeakerTarget> validSpeakers = validateSpeakers(sl, speaker, payload.speakers());
		boolean emitFromPlayer = payload.emitFromPlayer() && boomboxPos == null;

		SpeakerNetworkQueue queue = !validSpeakers.isEmpty()
				? resolvePlayerNetwork(server, sl, speaker, validSpeakers)
				: getPlayerNetwork(speaker.getUUID());

		// Prevent overlapping speaker playback if another player is already the active speaker DJ in THIS network!
		boolean anotherPlayerOwnsSpeakers = queue != null
				&& ((queue.activeEntry != null && !queue.activeEntry.ownerUuid.equals(speaker.getUUID()))
				|| isAnySessionUsingSpeakersInNetwork(queue, speaker.getUUID()));

		if (queue != null && !validSpeakers.isEmpty() && anotherPlayerOwnsSpeakers) {
			if (boomboxPos == null && !emitFromPlayer) {
				// Automatically place into this speaker network's queue and stop this overlapping stream
				boolean alreadyQueued = false;
				for (ServerQueueEntry qe : queue.waitingEntries) {
					if (qe.ownerUuid.equals(speaker.getUUID()) && qe.trackTitle.equals(payload.trackTitle())) {
						alreadyQueued = true;
						break;
					}
				}
				if (!alreadyQueued && queue.waitingEntries.size() < Mp3Payloads.MAX_QUEUE_ENTRIES) {
					queue.waitingEntries.addLast(new ServerQueueEntry(
							UUID.randomUUID(),
							speaker.getUUID(),
							speaker.getScoreboardName(),
							payload.trackTitle(),
							payload.trackTitle(),
							0
					));
					broadcastQueueSync(server, queue);
				}
				if (ServerPlayNetworking.canSend(speaker, S2CStreamStop.TYPE)) {
					ServerPlayNetworking.send(speaker, new S2CStreamStop(speaker.getUUID(), payload.streamId()));
				}
				return;
			} else {
				// Keep Boombox / Body speaker independent without overlapping onto the busy speaker network
				validSpeakers = Collections.emptyList();
			}
		} else if (queue != null && (!validSpeakers.isEmpty() || (queue.activeEntry != null && queue.activeEntry.ownerUuid.equals(speaker.getUUID())))) {
			List<SpeakerTarget> combined = queue.computeCombinedSpeakers(sl, speaker.getUUID());
			if (!combined.isEmpty()) {
				validSpeakers = combined;
			}
			if (!validSpeakers.isEmpty()) {
				if (queue.activeEntry == null) {
					queue.activeEntry = new ServerQueueEntry(
							UUID.randomUUID(),
							speaker.getUUID(),
							speaker.getScoreboardName(),
							payload.trackTitle(),
							payload.trackTitle(),
							0
					);
				}
				if (queue.activeEntry.ownerUuid.equals(speaker.getUUID())) {
					queue.activeEntry.trackTitle = payload.trackTitle();
					queue.activeEntry.paused = false;
					queue.activeEntry.streamStarted = true;
					queue.activeEntry.triggeredAtMs = System.currentTimeMillis();
				}
				broadcastQueueSync(server, queue);
			}
		}

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

		// If combined speakers were added from the shared pool, notify the owner so their local 3D nodes match
		if (!validSpeakers.equals(payload.speakers()) && ServerPlayNetworking.canSend(speaker, S2CStreamState.TYPE)) {
			ServerPlayNetworking.send(speaker, new S2CStreamState(
					session.speakerUuid,
					session.streamId,
					session.paused,
					session.volume,
					session.optionalBoomboxPos(),
					session.emitFromPlayer,
					session.speakers
			));
		}

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
			stopSessionFor(server, session.speakerUuid, session.streamId, true, true);
			return;
		}

		if (session.boomboxPos != null
				&& sourceLevel.isLoaded(session.boomboxPos)
				&& !sourceLevel.getBlockState(session.boomboxPos).is(Mp3PlayerMod.BOOMBOX_BLOCK)) {
			stopSessionFor(server, session.speakerUuid, session.streamId, true, true);
			return;
		}

		// Prune any linked speaker blocks that were broken or replaced
		pruneInvalidSpeakers(server, sourceLevel, session);
		if (!session.hasAnyAudioSource()) {
			stopSessionFor(server, session.speakerUuid, session.streamId, true, true);
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
		SpeakerNetworkQueue queue = !newSpeakers.isEmpty()
				? resolvePlayerNetwork(server, sourceLevel, speaker, newSpeakers)
				: getPlayerNetwork(speaker.getUUID());

		if (queue != null && newSpeakers.isEmpty()) {
			queue.playerLinkedSpeakers.remove(speaker.getUUID());
		}

		boolean anotherPlayerOwnsSpeakers = queue != null
				&& ((queue.activeEntry != null && !queue.activeEntry.ownerUuid.equals(speaker.getUUID()))
				|| isAnySessionUsingSpeakersInNetwork(queue, speaker.getUUID()));

		if (anotherPlayerOwnsSpeakers) {
			newSpeakers = Collections.emptyList();
			if (session.boomboxPos == null && ! payload.emitFromPlayer()) {
				stopSessionFor(server, speaker.getUUID(), session.streamId, true, false);
				if (queue != null) {
					broadcastQueueSync(server, queue);
				}
				return;
			}
		} else if (queue != null && !newSpeakers.isEmpty()) {
			newSpeakers = queue.computeCombinedSpeakers(sourceLevel, speaker.getUUID());
		}

		boolean pauseChanged = (session.paused != payload.paused());
		session.paused = payload.paused();
		session.volume = payload.volume();
		session.emitFromPlayer = payload.emitFromPlayer() && session.boomboxPos == null;
		session.speakers = newSpeakers;
		session.lastChunkTimeMs = System.currentTimeMillis();

		if (queue != null) {
			if (queue.activeEntry != null && queue.activeEntry.ownerUuid.equals(speaker.getUUID())) {
				if (newSpeakers.isEmpty() && queue.waitingEntries.isEmpty()) {
					queue.activeEntry = null;
					queue.skipVotes.clear();
					broadcastQueueSync(server, queue);
				} else {
					queue.activeEntry.paused = session.paused;
					if (pauseChanged) {
						broadcastQueueSync(server, queue);
					}
				}
			} else if (queue.activeEntry == null && !newSpeakers.isEmpty()) {
				queue.activeEntry = new ServerQueueEntry(
						UUID.randomUUID(),
						speaker.getUUID(),
						speaker.getScoreboardName(),
						session.trackTitle,
						session.trackTitle,
						0
				);
				queue.activeEntry.paused = session.paused;
				queue.activeEntry.streamStarted = true;
				broadcastQueueSync(server, queue);
			}
		}

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
			boolean notifyOwner,
			boolean advanceSpeakerQueue
	) {
		ActiveSession existing = SESSIONS.get(speakerUuid);
		if (existing == null) {
			if (advanceSpeakerQueue) {
				for (SpeakerNetworkQueue q : new ArrayList<>(NETWORKS.values())) {
					if (q.activeEntry != null && speakerUuid.equals(q.activeEntry.ownerUuid)) {
						ServerLevel sl = server.getLevel(q.dimension);
						advanceQueue(server, sl, q, false);
					}
				}
			}
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

		if (advanceSpeakerQueue) {
			for (SpeakerNetworkQueue queue : new ArrayList<>(NETWORKS.values())) {
				if (existing.dimension.equals(queue.dimension)
						&& queue.activeEntry != null
						&& speakerUuid.equals(queue.activeEntry.ownerUuid)) {
					ServerLevel sl = server.getLevel(existing.dimension);
					advanceQueue(server, sl, queue, false);
				}
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
		long now = System.currentTimeMillis();

		// Check if any triggered queue entry failed to start streaming within 8 seconds
		if (!NETWORKS.isEmpty()) {
			for (SpeakerNetworkQueue queue : new ArrayList<>(NETWORKS.values())) {
				ServerQueueEntry active = queue.activeEntry;
				if (active != null && !active.streamStarted && (now - active.triggeredAtMs > 8_000L)) {
					ServerLevel sl = server.getLevel(queue.dimension);
					advanceQueue(server, sl, queue, true);
				}
			}
		}

		if (SESSIONS.isEmpty()) {
			return;
		}
		boolean spawnNotes = (tickCounter % 14) == 0;

		List<ActiveSession> snapshot = new ArrayList<>(SESSIONS.values());
		for (ActiveSession session : snapshot) {
			ServerPlayer speaker = server.getPlayerList().getPlayer(session.speakerUuid);

			if (speaker == null || (!session.paused && now - session.lastChunkTimeMs > 12_000L)) {
				stopSessionFor(server, session.speakerUuid, session.streamId, false, true);
				continue;
			}

			ServerLevel sessionLevel = server.getLevel(session.dimension);
			if (sessionLevel == null) {
				stopSessionFor(server, session.speakerUuid, session.streamId, true, true);
				continue;
			}

			pruneInvalidSpeakers(server, sessionLevel, session);

			if (session.boomboxPos != null) {
				if (sessionLevel.isLoaded(session.boomboxPos)
						&& !sessionLevel.getBlockState(session.boomboxPos).is(Mp3PlayerMod.BOOMBOX_BLOCK)) {
					stopSessionFor(server, session.speakerUuid, session.streamId, true, true);
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
