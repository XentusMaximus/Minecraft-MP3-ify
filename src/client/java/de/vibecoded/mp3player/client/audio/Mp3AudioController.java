package de.vibecoded.mp3player.client.audio;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.client.audio.Opus3dStreamCodec.Opus3dBroadcaster;
import de.vibecoded.mp3player.client.audio.decoder.AudioTrackDecoder;
import de.vibecoded.mp3player.client.config.Mp3Config;
import de.vibecoded.mp3player.client.config.Mp3Config.LinkedSpeaker;
import de.vibecoded.mp3player.client.config.Mp3Config.LoopMode;
import de.vibecoded.mp3player.client.library.Mp3LibraryManager;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import de.vibecoded.mp3player.network.Mp3Payloads;
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
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.SpeakerTarget;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Controls local multi-format audio playback (MP3, M4A, OPUS, WAV, FLAC, OGG),
 * placed Boombox 3D world-positioned playback, multi-speaker 3D stream fan-out,
 * playlist navigation (Shuffle / Loop / Seek), and synchronized 48 kHz Opus 3D
 * spatial network streaming (Essential & Multiplayer safe).
 */
public final class Mp3AudioController {
	public static final int DEFAULT_SPEAKER_SCAN_RADIUS = 96;
	public static final double SPEAKER_RANGE_XYZ = 32.0;
	public static final double ACTIVE_SPEAKER_RANGE_SQR = 80.0 * 80.0;
	private static final int MAX_CHUNKS_PER_TICK = 5;

	private static final class LocalSpeakerNode implements AutoCloseable {
		final BlockPos pos;
		float volume;
		final Queue<PcmChunk> inbox = new ArrayDeque<>(24);
		OpenAlStreamChannel channel;

		LocalSpeakerNode(BlockPos pos, float volume) {
			this.pos = pos.immutable();
			this.volume = volume;
		}

		void enqueue(PcmChunk chunk) {
			if (inbox.size() >= 20) {
				inbox.poll();
			}
			inbox.offer(chunk);
		}

		void pump(OpenAlStreamChannel master, boolean paused, float effectiveGain, boolean withinRange) {
			if (master == null || !withinRange || effectiveGain <= 0.0001f) {
				inbox.clear();
				if (channel != null) {
					channel.close();
					channel = null;
				}
				return;
			}
			if (channel == null) {
				channel = OpenAlStreamChannel.createSpeakerChannel();
				channel.setWorldPosition(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
			} else {
				channel.setWorldPosition(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
			}
			channel.pumpFollower(master, inbox, paused, effectiveGain);
		}

		void updateGainNow(float baseGain) {
			if (channel != null) {
				channel.updateGainNow(baseGain * volume);
			}
		}

		@Override
		public void close() {
			inbox.clear();
			if (channel != null) {
				channel.close();
				channel = null;
			}
		}
	}

	private final Mp3Config config;
	private final Mp3LibraryManager library;
	private final Random random = new Random();
	private final Deque<Mp3TrackInfo> history = new ArrayDeque<>();

	private Mp3TrackInfo currentTrack;
	private Mp3DecodeWorker decodeWorker;
	private OpenAlStreamChannel alChannel;
	private final Map<BlockPos, LocalSpeakerNode> speakerNodes = new LinkedHashMap<>();
	private boolean paused = false;
	private double seekBaseSeconds = 0.0;

	// Placed Boombox target (when non-null, BOTH local OpenAL and network stream emanate in 3D from this BlockPos)
	private BlockPos activeBoomboxPos;
	private ResourceKey<Level> activeBoomboxDimension;

	// Speaker-System-Only target mode (when true, primary headphones are muted and audio emanates purely from linked 3D speakers)
	private boolean speakerSystemMode = false;

	// Shared Speaker Playback Queue state (synced from server, or local fallback on unmodded servers)
	private volatile List<QueueEntryInfo> serverSpeakerQueue = Collections.emptyList();
	private volatile int serverSkipVotes = 0;
	private volatile int serverRequiredSkipVotes = 0;
	private volatile Set<UUID> serverSkipVoters = Collections.emptySet();
	private final Deque<Mp3TrackInfo> localFallbackQueue = new ArrayDeque<>();
	private UUID activeQueueEntryId;

	// Outgoing 3D Opus network stream state
	private UUID activeStreamId;
	private Opus3dBroadcaster opusBroadcaster;
	private int streamSequence;
	private float lastSentNetworkVolume = -1.0f;
	private int volumeSyncCooldown = 0;

	public Mp3AudioController(Mp3Config config, Mp3LibraryManager library) {
		this.config = config;
		this.library = library;
	}

	public Mp3Config config() {
		return config;
	}

	public Mp3LibraryManager library() {
		return library;
	}

	public Mp3TrackInfo currentTrack() {
		return currentTrack;
	}

	public boolean isPlaying() {
		return currentTrack != null && alChannel != null && !paused;
	}

	public boolean isPaused() {
		return currentTrack != null && alChannel != null && paused;
	}

	public boolean hasActiveTrack() {
		return currentTrack != null && alChannel != null;
	}

	public boolean isBoomboxActive() {
		return activeBoomboxPos != null && activeBoomboxDimension != null;
	}

	public BlockPos activeBoomboxPos() {
		return activeBoomboxPos;
	}

	public ResourceKey<Level> activeBoomboxDimension() {
		return activeBoomboxDimension;
	}

	public boolean isTargetingBoombox(ResourceKey<Level> dimension, BlockPos pos) {
		return pos != null
				&& dimension != null
				&& pos.equals(activeBoomboxPos)
				&& dimension.equals(activeBoomboxDimension);
	}

	public boolean isSpeakerSystemMode() {
		return speakerSystemMode && !isBoomboxActive();
	}

	public void setSpeakerSystemMode(boolean enabled) {
		if (enabled) {
			this.activeBoomboxPos = null;
			this.activeBoomboxDimension = null;
			this.speakerSystemMode = true;
			if (!config.linkedSpeakersEnabled()) {
				config.setLinkedSpeakersEnabled(true);
				config.save();
			}
			if (alChannel != null) {
				alChannel.setPositional3D(false);
			}
			sendSyncSpeakersToServer();
			if (currentTrack != null && alChannel != null && isAnotherPlayerPlayingOnSpeakers()) {
				// Do not overlap with another player's active speaker stream; enqueue our track instead!
				Mp3TrackInfo toQueue = currentTrack;
				stopLocalAndNetwork(false);
				currentTrack = null;
				paused = false;
				seekBaseSeconds = 0.0;
				enqueueTrackOnSpeakers(toQueue, false);
				return;
			}
		} else {
			this.speakerSystemMode = false;
			closeAllSpeakerNodes();
		}
		refreshImmediateGain(Minecraft.getInstance());
		syncNetworkStreamWithCurrentTargets();
	}

	// ===================== SPEAKER PLAYBACK QUEUE =====================

	public List<QueueEntryInfo> speakerQueueEntries() {
		if (isServerSupporting3D()) {
			return serverSpeakerQueue;
		}
		if (localFallbackQueue.isEmpty() && (!isSpeakerSystemMode() || currentTrack == null)) {
			return Collections.emptyList();
		}
		Minecraft mc = Minecraft.getInstance();
		UUID myUuid = (mc != null && mc.player != null) ? mc.player.getUUID() : new UUID(0L, 0L);
		String myName = (mc != null && mc.player != null) ? mc.player.getScoreboardName() : "Player";
		List<QueueEntryInfo> list = new ArrayList<>(1 + localFallbackQueue.size());
		if (isSpeakerSystemMode() && currentTrack != null) {
			list.add(new QueueEntryInfo(
					activeQueueEntryId != null ? activeQueueEntryId : new UUID(0L, 1L),
					myUuid,
					myName,
					trackKey(currentTrack),
					currentTrack.fullDisplayLabel(),
					currentTrack.durationSeconds(),
					true,
					paused
			));
		}
		long idx = 2L;
		for (Mp3TrackInfo queued : localFallbackQueue) {
			list.add(new QueueEntryInfo(
					new UUID(0L, idx++),
					myUuid,
					myName,
					trackKey(queued),
					queued.fullDisplayLabel(),
					queued.durationSeconds(),
					false,
					false
			));
		}
		return list;
	}

	public int speakerQueueSize() {
		return speakerQueueEntries().size();
	}

	public int waitingSpeakerQueueCount() {
		int count = 0;
		for (QueueEntryInfo entry : speakerQueueEntries()) {
			if (!entry.nowPlaying()) {
				count++;
			}
		}
		return count;
	}

	public QueueEntryInfo activeSpeakerQueueEntry() {
		for (QueueEntryInfo entry : speakerQueueEntries()) {
			if (entry.nowPlaying()) {
				return entry;
			}
		}
		return null;
	}

	public boolean isAnotherPlayerPlayingOnSpeakers() {
		Minecraft mc = Minecraft.getInstance();
		UUID myUuid = (mc != null && mc.player != null) ? mc.player.getUUID() : null;
		QueueEntryInfo active = activeSpeakerQueueEntry();
		return active != null && myUuid != null && !myUuid.equals(active.ownerUuid());
	}

	public boolean isLocalPlayerActiveSpeakerDj() {
		Minecraft mc = Minecraft.getInstance();
		UUID myUuid = (mc != null && mc.player != null) ? mc.player.getUUID() : null;
		QueueEntryInfo active = activeSpeakerQueueEntry();
		return active != null && myUuid != null && myUuid.equals(active.ownerUuid());
	}

	public boolean isLocalPlayerInQueue() {
		Minecraft mc = Minecraft.getInstance();
		UUID myUuid = (mc != null && mc.player != null) ? mc.player.getUUID() : null;
		if (myUuid == null) {
			return false;
		}
		for (QueueEntryInfo entry : speakerQueueEntries()) {
			if (myUuid.equals(entry.ownerUuid())) {
				return true;
			}
		}
		return false;
	}

	public int skipVoteCount() {
		return isServerSupporting3D() ? serverSkipVotes : 0;
	}

	public int requiredSkipVotes() {
		if (isServerSupporting3D()) {
			if (serverRequiredSkipVotes > 0) {
				return serverRequiredSkipVotes;
			}
			Set<UUID> owners = new HashSet<>();
			for (QueueEntryInfo entry : serverSpeakerQueue) {
				owners.add(entry.ownerUuid());
			}
			return owners.size();
		}
		return speakerQueueSize() > 0 ? 1 : 0;
	}

	public boolean hasPlayerVotedSkip(UUID playerUuid) {
		return playerUuid != null && serverSkipVoters.contains(playerUuid);
	}

	public boolean hasLocalPlayerVotedSkip() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.player != null && hasPlayerVotedSkip(mc.player.getUUID());
	}

	/**
	 * Adds a track to the shared Speaker Playback Queue (or starts playing it immediately
	 * on the speakers if the queue is currently idle).
	 * Returns true if the track was queued behind an existing track, or false if it started immediately.
	 */
	public boolean enqueueTrackOnSpeakers(Mp3TrackInfo track, boolean switchActiveIfOwner) {
		if (track == null) {
			return false;
		}
		this.activeBoomboxPos = null;
		this.activeBoomboxDimension = null;
		this.speakerSystemMode = true;
		if (!config.linkedSpeakersEnabled()) {
			config.setLinkedSpeakersEnabled(true);
			config.save();
		}

		Minecraft mc = Minecraft.getInstance();
		if (totalLinkedSpeakerCount() == 0 && mc != null && mc.level != null && mc.player != null) {
			scanAndLinkNearbySpeakers(mc.level, mc.player.blockPosition(), DEFAULT_SPEAKER_SCAN_RADIUS);
		}

		if (isServerSupporting3D()) {
			boolean willQueue = activeSpeakerQueueEntry() != null
					&& (!isLocalPlayerActiveSpeakerDj() || !switchActiveIfOwner || waitingSpeakerQueueCount() > 0);
			ClientPlayNetworking.send(new C2SQueueAdd(
					trackKey(track),
					track.fullDisplayLabel(),
					track.durationSeconds(),
					switchActiveIfOwner,
					buildActiveSpeakerTargets()
			));
			return willQueue;
		}

		// Local fallback when on an unmodded server
		if (!hasActiveTrack() || (switchActiveIfOwner && localFallbackQueue.isEmpty())) {
			playTrackInternal(track, 0.0, true);
			return false;
		} else {
			if (localFallbackQueue.size() < Mp3Payloads.MAX_QUEUE_ENTRIES) {
				localFallbackQueue.addLast(track);
			}
			return true;
		}
	}

	public void removeSpeakerQueueEntry(UUID entryId) {
		if (entryId == null) {
			return;
		}
		if (isServerSupporting3D()) {
			ClientPlayNetworking.send(new C2SQueueAction(
					QueueActionType.REMOVE_ENTRY,
					Optional.of(entryId),
					buildActiveSpeakerTargets()
			));
		} else {
			long idx = 2L;
			Iterator<Mp3TrackInfo> it = localFallbackQueue.iterator();
			while (it.hasNext()) {
				it.next();
				if (entryId.equals(new UUID(0L, idx++))) {
					it.remove();
					break;
				}
			}
		}
	}

	public void skipSpeakerQueueTrack() {
		if (isServerSupporting3D()) {
			ClientPlayNetworking.send(new C2SQueueAction(
					QueueActionType.SKIP_CURRENT,
					Optional.empty(),
					buildActiveSpeakerTargets()
			));
		} else if (!localFallbackQueue.isEmpty()) {
			Mp3TrackInfo next = localFallbackQueue.pollFirst();
			if (next != null) {
				playTrackInternal(next, 0.0, true);
			}
		} else {
			stop();
		}
	}

	public void clearSpeakerQueue() {
		localFallbackQueue.clear();
		if (isServerSupporting3D()) {
			ClientPlayNetworking.send(new C2SQueueAction(
					QueueActionType.CLEAR_ALL,
					Optional.empty(),
					buildActiveSpeakerTargets()
			));
		}
	}

	public void sendSyncSpeakersToServer() {
		if (isServerSupporting3D()) {
			ClientPlayNetworking.send(new C2SQueueAction(
					QueueActionType.SYNC_SPEAKERS,
					Optional.empty(),
					buildActiveSpeakerTargets()
			));
		}
	}

	public void onServerQueueSync(S2CSpeakerQueueSync payload) {
		if (payload == null) {
			this.serverSpeakerQueue = Collections.emptyList();
			this.serverSkipVotes = 0;
			this.serverRequiredSkipVotes = 0;
			this.serverSkipVoters = Collections.emptySet();
		} else {
			this.serverSpeakerQueue = payload.entries() != null ? payload.entries() : Collections.emptyList();
			this.serverSkipVotes = Math.max(0, payload.skipVotes());
			this.serverRequiredSkipVotes = Math.max(0, payload.requiredSkipVotes());
			this.serverSkipVoters = payload.skipVoterUuids() != null && !payload.skipVoterUuids().isEmpty()
					? Set.copyOf(payload.skipVoterUuids())
					: Collections.emptySet();

			if (payload.retainedSpeakers() != null && payload.retainedSpeakers().isPresent()) {
				Minecraft mc = Minecraft.getInstance();
				if (mc != null && mc.level != null) {
					ResourceKey<Level> dim = mc.level.dimension();
					Set<BlockPos> keep = new HashSet<>(payload.retainedSpeakers().get());
					List<LinkedSpeaker> modifiable = new ArrayList<>(config.linkedSpeakers());
					if (modifiable.removeIf(ls -> dim.equals(ls.dimension()) && !keep.contains(ls.pos()))) {
						config.setLinkedSpeakers(modifiable);
						syncLocalSpeakerNodes(mc);
					}
				}
			}
		}

		// If we were playing in Speaker System Mode, but another player is now the active DJ (or queue was cleared/skipped),
		// ensure we stop any local speaker playback immediately so we never overlap!
		if (isSpeakerSystemMode() && hasActiveTrack() && !isLocalPlayerActiveSpeakerDj() && isAnotherPlayerPlayingOnSpeakers()) {
			stopLocalAndNetwork(false);
			currentTrack = null;
			paused = false;
			seekBaseSeconds = 0.0;
			activeQueueEntryId = null;
		}
	}

	public void onServerQueuePlayTrigger(S2CQueuePlayTrigger payload) {
		if (payload == null) {
			return;
		}
		library.refresh();
		Mp3TrackInfo match = findTrackByKeyOrTitle(payload.trackId(), payload.trackTitle());
		if (match == null) {
			if (isServerSupporting3D()) {
				ClientPlayNetworking.send(new C2SQueueAction(
						QueueActionType.ADVANCE_FINISHED,
						Optional.of(payload.entryId()),
						buildActiveSpeakerTargets()
				));
			}
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.level != null && payload.speakers() != null && !payload.speakers().isEmpty()) {
			ResourceKey<Level> dim = mc.level.dimension();
			List<LinkedSpeaker> modifiable = new ArrayList<>(config.linkedSpeakers());
			boolean changed = false;
			for (SpeakerTarget st : payload.speakers()) {
				boolean exists = false;
				for (LinkedSpeaker ls : modifiable) {
					if (dim.equals(ls.dimension()) && st.pos().equals(ls.pos())) {
						exists = true;
						break;
					}
				}
				if (!exists && modifiable.size() < Mp3Payloads.MAX_LINKED_SPEAKERS) {
					modifiable.add(new LinkedSpeaker(dim, st.pos(), true, st.volume()));
					changed = true;
				}
			}
			if (changed) {
				config.setLinkedSpeakers(modifiable);
			}
		}

		this.activeBoomboxPos = null;
		this.activeBoomboxDimension = null;
		this.speakerSystemMode = true;
		this.activeQueueEntryId = payload.entryId();
		if (!config.linkedSpeakersEnabled()) {
			config.setLinkedSpeakersEnabled(true);
		}
		playTrackInternal(match, 0.0, true);
	}

	private static String trackKey(Mp3TrackInfo track) {
		if (track == null || track.file() == null) {
			return "";
		}
		return track.file().getFileName().toString();
	}

	private Mp3TrackInfo findTrackByKeyOrTitle(String key, String title) {
		List<Mp3TrackInfo> tracks = library.tracks();
		if (key != null && !key.isBlank()) {
			for (Mp3TrackInfo t : tracks) {
				if (key.equalsIgnoreCase(trackKey(t)) || key.equalsIgnoreCase(t.file().toString())) {
					return t;
				}
			}
		}
		if (title != null && !title.isBlank()) {
			for (Mp3TrackInfo t : tracks) {
				if (title.equalsIgnoreCase(t.fullDisplayLabel()) || title.equalsIgnoreCase(t.displayTitle())) {
					return t;
				}
			}
		}
		return null;
	}

	// ===================== LINKED SPEAKER MANAGEMENT =====================

	public List<LinkedSpeaker> linkedSpeakers() {
		return config.linkedSpeakers();
	}

	public List<LinkedSpeaker> linkedSpeakersInDimension(ResourceKey<Level> dimension) {
		if (dimension == null) {
			return Collections.emptyList();
		}
		List<LinkedSpeaker> result = new ArrayList<>();
		for (LinkedSpeaker ls : config.linkedSpeakers()) {
			if (dimension.equals(ls.dimension())) {
				result.add(ls);
			}
		}
		return result;
	}

	public List<LinkedSpeaker> linkedSpeakersInCurrentLevel() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			return config.linkedSpeakers();
		}
		return linkedSpeakersInDimension(mc.level.dimension());
	}

	public int totalLinkedSpeakerCount() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.level != null) {
			return linkedSpeakersInDimension(mc.level.dimension()).size();
		}
		return config.linkedSpeakers().size();
	}

	public int activeLinkedSpeakerCount() {
		if (!config.linkedSpeakersEnabled()) {
			return 0;
		}
		Minecraft mc = Minecraft.getInstance();
		ResourceKey<Level> dim = (mc != null && mc.level != null) ? mc.level.dimension() : null;
		int count = 0;
		for (LinkedSpeaker ls : config.linkedSpeakers()) {
			if ((dim == null || dim.equals(ls.dimension())) && ls.enabled() && ls.volume() > 0.001f) {
				count++;
			}
		}
		return count;
	}

	public boolean isSpeakerLinked(ResourceKey<Level> dimension, BlockPos pos) {
		if (dimension == null || pos == null) {
			return false;
		}
		for (LinkedSpeaker ls : config.linkedSpeakers()) {
			if (dimension.equals(ls.dimension()) && pos.equals(ls.pos())) {
				return true;
			}
		}
		return false;
	}

	public boolean isSpeakerStreaming(BlockPos pos) {
		return isPlaying() && config.linkedSpeakersEnabled() && speakerNodes.containsKey(pos);
	}

	/**
	 * Links the speaker at (dimension, pos) if not yet linked; unlinks it if already linked.
	 * Returns true if the speaker is now linked, false if it was unlinked.
	 */
	public boolean toggleSpeakerLink(ResourceKey<Level> dimension, BlockPos pos) {
		if (isSpeakerLinked(dimension, pos)) {
			unlinkSpeaker(dimension, pos);
			return false;
		} else {
			linkSpeaker(dimension, pos);
			return true;
		}
	}

	public boolean linkSpeaker(ResourceKey<Level> dimension, BlockPos pos) {
		if (dimension == null || pos == null) {
			return false;
		}
		if (!config.linkedSpeakersEnabled()) {
			config.setLinkedSpeakersEnabled(true);
		}
		if (!isBoomboxActive() && !config.speaker3dMode()) {
			this.speakerSystemMode = true;
		}
		List<LinkedSpeaker> modifiable = new ArrayList<>(config.linkedSpeakers());
		for (int i = 0; i < modifiable.size(); i++) {
			LinkedSpeaker existing = modifiable.get(i);
			if (dimension.equals(existing.dimension()) && pos.equals(existing.pos())) {
				if (!existing.enabled()) {
					modifiable.set(i, new LinkedSpeaker(dimension, pos, true, existing.volume()));
					config.setLinkedSpeakers(modifiable);
					config.save();
					sendSyncSpeakersToServer();
					syncSpeakersDuringPlaybackNow();
					syncNetworkStreamWithCurrentTargets();
				}
				return true;
			}
		}

		// If the newly linked speaker is outside the scan radius (96 blocks) of all previously
		// linked speakers in this dimension, switch cleanly to the new speaker network!
		double maxNetDistSqr = (double) DEFAULT_SPEAKER_SCAN_RADIUS * (double) DEFAULT_SPEAKER_SCAN_RADIUS;
		boolean hasAnyInDim = false;
		boolean withinExistingNet = false;
		for (LinkedSpeaker existing : modifiable) {
			if (dimension.equals(existing.dimension())) {
				hasAnyInDim = true;
				if (existing.pos().distSqr(pos) <= maxNetDistSqr) {
					withinExistingNet = true;
					break;
				}
			}
		}
		if (hasAnyInDim && !withinExistingNet) {
			modifiable.removeIf(ls -> dimension.equals(ls.dimension()));
			closeAllSpeakerNodes();
		}

		if (modifiable.size() >= Mp3Payloads.MAX_LINKED_SPEAKERS) {
			return false;
		}
		modifiable.add(new LinkedSpeaker(dimension, pos.immutable(), true, 1.0f));
		config.setLinkedSpeakers(modifiable);
		config.save();
		sendSyncSpeakersToServer();
		syncSpeakersDuringPlaybackNow();
		syncNetworkStreamWithCurrentTargets();
		return true;
	}

	public boolean unlinkSpeaker(ResourceKey<Level> dimension, BlockPos pos) {
		if (dimension == null || pos == null) {
			return false;
		}
		List<LinkedSpeaker> modifiable = new ArrayList<>(config.linkedSpeakers());
		boolean removed = modifiable.removeIf(ls -> dimension.equals(ls.dimension()) && pos.equals(ls.pos()));
		if (removed) {
			config.setLinkedSpeakers(modifiable);
			config.save();
			LocalSpeakerNode node = speakerNodes.remove(pos);
			if (node != null) {
				node.close();
			}
			sendSyncSpeakersToServer();
			refreshImmediateGain(Minecraft.getInstance());
			syncNetworkStreamWithCurrentTargets();
		}
		return removed;
	}

	public void setSpeakerEnabled(ResourceKey<Level> dimension, BlockPos pos, boolean enabled) {
		if (dimension == null || pos == null) {
			return;
		}
		List<LinkedSpeaker> modifiable = new ArrayList<>(config.linkedSpeakers());
		boolean changed = false;
		for (int i = 0; i < modifiable.size(); i++) {
			LinkedSpeaker ls = modifiable.get(i);
			if (dimension.equals(ls.dimension()) && pos.equals(ls.pos()) && ls.enabled() != enabled) {
				modifiable.set(i, new LinkedSpeaker(dimension, pos, enabled, ls.volume()));
				changed = true;
				break;
			}
		}
		if (changed) {
			config.setLinkedSpeakers(modifiable);
			config.save();
			if (!enabled) {
				LocalSpeakerNode node = speakerNodes.remove(pos);
				if (node != null) {
					node.close();
				}
			} else {
				syncSpeakersDuringPlaybackNow();
			}
			sendSyncSpeakersToServer();
			refreshImmediateGain(Minecraft.getInstance());
			syncNetworkStreamWithCurrentTargets();
		}
	}

	public void cycleSpeakerVolume(ResourceKey<Level> dimension, BlockPos pos) {
		if (dimension == null || pos == null) {
			return;
		}
		List<LinkedSpeaker> modifiable = new ArrayList<>(config.linkedSpeakers());
		for (int i = 0; i < modifiable.size(); i++) {
			LinkedSpeaker ls = modifiable.get(i);
			if (dimension.equals(ls.dimension()) && pos.equals(ls.pos())) {
				float v = ls.volume();
				float nextVol;
				if (v >= 0.95f) {
					nextVol = 0.75f;
				} else if (v >= 0.70f) {
					nextVol = 0.50f;
				} else if (v >= 0.45f) {
					nextVol = 0.25f;
				} else {
					nextVol = 1.00f;
				}
				modifiable.set(i, new LinkedSpeaker(dimension, pos, ls.enabled(), nextVol));
				config.setLinkedSpeakers(modifiable);
				config.save();
				LocalSpeakerNode node = speakerNodes.get(pos);
				if (node != null) {
					node.volume = nextVol;
				}
				sendSyncSpeakersToServer();
				refreshImmediateGain(Minecraft.getInstance());
				syncNetworkStreamWithCurrentTargets();
				break;
			}
		}
	}

	public void clearLinkedSpeakers(ResourceKey<Level> dimension) {
		List<LinkedSpeaker> modifiable = new ArrayList<>(config.linkedSpeakers());
		boolean changed;
		if (dimension != null) {
			changed = modifiable.removeIf(ls -> dimension.equals(ls.dimension()));
		} else {
			changed = !modifiable.isEmpty();
			modifiable.clear();
		}
		if (changed) {
			config.setLinkedSpeakers(modifiable);
			config.save();
			closeAllSpeakerNodes();
			if (speakerSystemMode) {
				speakerSystemMode = false;
			}
			sendSyncSpeakersToServer();
			refreshImmediateGain(Minecraft.getInstance());
			syncNetworkStreamWithCurrentTargets();
		}
	}

	/**
	 * Resets and deletes all linked speaker connections (invoked on server/world join and leave).
	 */
	public void resetLinkedSpeakers() {
		closeAllSpeakerNodes();
		config.setLinkedSpeakers(Collections.emptyList());
		serverSpeakerQueue = Collections.emptyList();
		serverSkipVotes = 0;
		serverRequiredSkipVotes = 0;
		serverSkipVoters = Collections.emptySet();
		localFallbackQueue.clear();
		activeQueueEntryId = null;
		if (speakerSystemMode) {
			speakerSystemMode = false;
		}
	}

	public void setLinkedSpeakersEnabled(boolean enabled) {
		if (config.linkedSpeakersEnabled() == enabled) {
			return;
		}
		config.setLinkedSpeakersEnabled(enabled);
		config.save();
		if (!enabled) {
			closeAllSpeakerNodes();
			if (speakerSystemMode) {
				speakerSystemMode = false;
			}
		} else {
			if (!isBoomboxActive() && !config.speaker3dMode()) {
				speakerSystemMode = true;
			}
			syncSpeakersDuringPlaybackNow();
		}
		sendSyncSpeakersToServer();
		refreshImmediateGain(Minecraft.getInstance());
		syncNetworkStreamWithCurrentTargets();
	}

	/**
	 * Scans around {@code center} within {@code radius} blocks for placed {@link Mp3PlayerMod#SPEAKER_BLOCK}
	 * blocks using fast chunk-section palette filtering, and links any that are not yet linked
	 * (sorted nearest-first).
	 */
	public int scanAndLinkNearbySpeakers(Level level, BlockPos center, int radius) {
		if (level == null || center == null || radius <= 0) {
			return 0;
		}
		ResourceKey<Level> dimension = level.dimension();
		List<BlockPos> found = new ArrayList<>();
		int vRadius = Math.max(16, radius / 2);
		double maxHorizDistSqr = (double) radius * (double) radius;

		int minX = center.getX() - radius;
		int maxX = center.getX() + radius;
		int minZ = center.getZ() - radius;
		int maxZ = center.getZ() + radius;
		int minY = Math.max(level.getMinY(), center.getY() - vRadius);
		int maxY = Math.min(level.getMaxY() - 1, center.getY() + vRadius);

		int minChunkX = minX >> 4;
		int maxChunkX = maxX >> 4;
		int minChunkZ = minZ >> 4;
		int maxChunkZ = maxZ >> 4;

		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				LevelChunk chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				int chunkBlockX = cx << 4;
				int chunkBlockZ = cz << 4;

				for (int sIdx = 0; sIdx < sections.length; sIdx++) {
					LevelChunkSection section = sections[sIdx];
					if (section == null || section.hasOnlyAir()) {
						continue;
					}
					int sectionBottomY = chunk.getSectionYFromSectionIndex(sIdx) << 4;
					int sectionTopY = sectionBottomY + 15;
					if (sectionTopY < minY || sectionBottomY > maxY) {
						continue;
					}
					if (!section.maybeHas(state -> state.is(Mp3PlayerMod.SPEAKER_BLOCK))) {
						continue;
					}

					int lyStart = Math.max(0, minY - sectionBottomY);
					int lyEnd = Math.min(15, maxY - sectionBottomY);
					for (int ly = lyStart; ly <= lyEnd; ly++) {
						int worldY = sectionBottomY + ly;
						for (int lz = 0; lz < 16; lz++) {
							int worldZ = chunkBlockZ + lz;
							if (worldZ < minZ || worldZ > maxZ) {
								continue;
							}
							double dz = worldZ - center.getZ();
							for (int lx = 0; lx < 16; lx++) {
								int worldX = chunkBlockX + lx;
								if (worldX < minX || worldX > maxX) {
									continue;
								}
								double dx = worldX - center.getX();
								if (dx * dx + dz * dz <= maxHorizDistSqr
										&& section.getBlockState(lx, ly, lz).is(Mp3PlayerMod.SPEAKER_BLOCK)) {
									found.add(new BlockPos(worldX, worldY, worldZ));
								}
							}
						}
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(p -> p.distSqr(center)));

		List<LinkedSpeaker> modifiable = new ArrayList<>(config.linkedSpeakers());
		if (!found.isEmpty()) {
			double maxNetDistSqr = (double) DEFAULT_SPEAKER_SCAN_RADIUS * (double) DEFAULT_SPEAKER_SCAN_RADIUS;
			boolean hasAnyInDim = false;
			boolean overlapsExistingNet = false;
			for (LinkedSpeaker existing : modifiable) {
				if (dimension.equals(existing.dimension())) {
					hasAnyInDim = true;
					for (BlockPos pos : found) {
						if (existing.pos().distSqr(pos) <= maxNetDistSqr) {
							overlapsExistingNet = true;
							break;
						}
					}
					if (overlapsExistingNet) {
						break;
					}
				}
			}
			if (hasAnyInDim && !overlapsExistingNet) {
				modifiable.removeIf(ls -> dimension.equals(ls.dimension()));
				closeAllSpeakerNodes();
			}
		}

		int added = 0;
		for (BlockPos pos : found) {
			if (modifiable.size() >= Mp3Payloads.MAX_LINKED_SPEAKERS) {
				break;
			}
			boolean exists = false;
			for (LinkedSpeaker existing : modifiable) {
				if (dimension.equals(existing.dimension()) && pos.equals(existing.pos())) {
					exists = true;
					break;
				}
			}
			if (!exists) {
				modifiable.add(new LinkedSpeaker(dimension, pos, true, 1.0f));
				added++;
			}
		}

		if (added > 0) {
			if (!config.linkedSpeakersEnabled()) {
				config.setLinkedSpeakersEnabled(true);
			}
			if (!isBoomboxActive() && !config.speaker3dMode()) {
				this.speakerSystemMode = true;
			}
			config.setLinkedSpeakers(modifiable);
			config.save();
			sendSyncSpeakersToServer();
			syncSpeakersDuringPlaybackNow();
			syncNetworkStreamWithCurrentTargets();
		}
		return added;
	}

	private void syncSpeakersDuringPlaybackNow() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || alChannel == null || decodeWorker == null) {
			return;
		}
		syncLocalSpeakerNodes(mc);
		float baseGain = computeBaseGain(mc);
		for (LocalSpeakerNode node : speakerNodes.values()) {
			boolean inRange = mc.player != null
					&& node.pos.distToCenterSqr(mc.player.position()) <= ACTIVE_SPEAKER_RANGE_SQR;
			node.pump(alChannel, paused, baseGain * node.volume, inRange);
		}
	}

	public void playTrackOnSpeakers(Mp3TrackInfo track) {
		enqueueTrackOnSpeakers(track, true);
	}

	public void togglePlayPauseOnSpeakers() {
		if (!config.linkedSpeakersEnabled()) {
			config.setLinkedSpeakersEnabled(true);
			config.save();
		}
		if (hasActiveTrack()) {
			if (!isSpeakerSystemMode() && !isBoomboxActive()) {
				setSpeakerSystemMode(true);
				if (paused) {
					setPaused(false);
				}
				return;
			}
			setPaused(!paused);
		}
	}

	public boolean isServerSupporting3D() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getConnection() != null && ClientPlayNetworking.canSend(C2SStreamStart.TYPE);
	}

	public boolean isCurrentlyStreaming3D() {
		return activeStreamId != null;
	}

	public float visualizerLevel() {
		return alChannel != null ? alChannel.visualizerLevel() : 0.0f;
	}

	public int currentPositionSeconds() {
		if (currentTrack == null || alChannel == null) {
			return 0;
		}
		int pos = (int) Math.round(seekBaseSeconds + alChannel.elapsedSeconds());
		if (currentTrack.durationSeconds() > 0) {
			return Math.min(pos, currentTrack.durationSeconds());
		}
		return Math.max(0, pos);
	}

	public double progressRatio() {
		if (currentTrack == null || alChannel == null || currentTrack.durationSeconds() <= 0) {
			return 0.0;
		}
		double elapsed = seekBaseSeconds + alChannel.elapsedSeconds();
		return Math.max(0.0, Math.min(1.0, elapsed / currentTrack.durationSeconds()));
	}

	public void playTrack(Mp3TrackInfo track) {
		if (isSpeakerSystemMode()) {
			enqueueTrackOnSpeakers(track, true);
			return;
		}
		playTrackInternal(track, 0.0, true);
	}

	public void playTrackOnBoombox(Mp3TrackInfo track, ResourceKey<Level> dimension, BlockPos pos) {
		if (dimension != null && pos != null) {
			this.activeBoomboxPos = pos.immutable();
			this.activeBoomboxDimension = dimension;
			this.speakerSystemMode = false;
		}
		playTrackInternal(track, 0.0, true);
	}

	/**
	 * Routes playback to the placed Boombox at (dimension, pos).
	 * If a track is already playing or paused (e.g. on Headphones), seamlessly transfers it
	 * at its exact current timestamp to the Boombox in 3D!
	 */
	public void activateBoomboxTarget(ResourceKey<Level> dimension, BlockPos pos) {
		if (dimension == null || pos == null) {
			return;
		}
		if (isTargetingBoombox(dimension, pos)) {
			if (!hasActiveTrack()) {
				togglePlayPause();
			}
			return;
		}
		this.activeBoomboxPos = pos.immutable();
		this.activeBoomboxDimension = dimension;
		this.speakerSystemMode = false;

		if (currentTrack != null && alChannel != null) {
			boolean wasPaused = this.paused;
			double ratio = progressRatio();
			playTrackInternal(currentTrack, ratio, false);
			if (wasPaused) {
				setPaused(true);
			}
		}
	}

	/**
	 * Detaches playback from any placed Boombox and returns to Handheld mode
	 * (Headphones stereo or Handheld 3D Speaker).
	 */
	public void deactivateBoomboxTarget() {
		if (!isBoomboxActive()) {
			return;
		}
		this.activeBoomboxPos = null;
		this.activeBoomboxDimension = null;

		if (currentTrack != null && alChannel != null) {
			boolean wasPaused = this.paused;
			double ratio = progressRatio();
			playTrackInternal(currentTrack, ratio, false);
			if (wasPaused) {
				setPaused(true);
			}
		}
	}

	public void togglePlayPauseOnBoombox(ResourceKey<Level> dimension, BlockPos pos) {
		if (dimension == null || pos == null) {
			togglePlayPause();
			return;
		}
		if (isTargetingBoombox(dimension, pos) && hasActiveTrack()) {
			setPaused(!paused);
			return;
		}
		if (hasActiveTrack()) {
			activateBoomboxTarget(dimension, pos);
			if (paused) {
				setPaused(false);
			}
		}
	}

	public boolean shouldUseLinkedSpeakers() {
		if (!config.linkedSpeakersEnabled() || activeLinkedSpeakerCount() <= 0) {
			return false;
		}
		if (isAnotherPlayerPlayingOnSpeakers()) {
			return false;
		}
		return isSpeakerSystemMode() || isBoomboxActive() || config.speaker3dMode();
	}

	private boolean shouldBroadcastNetwork3D() {
		return isBoomboxActive()
				|| config.speaker3dMode()
				|| shouldUseLinkedSpeakers();
	}

	private boolean shouldEmitFromPlayerBody() {
		return !isBoomboxActive() && !isSpeakerSystemMode() && config.speaker3dMode();
	}

	private List<SpeakerTarget> buildActiveSpeakerTargets() {
		if (!config.linkedSpeakersEnabled()) {
			return Collections.emptyList();
		}
		Minecraft mc = Minecraft.getInstance();
		ResourceKey<Level> currentDim = (mc != null && mc.level != null)
				? mc.level.dimension()
				: activeBoomboxDimension;
		if (currentDim == null) {
			return Collections.emptyList();
		}
		List<SpeakerTarget> targets = new ArrayList<>();
		for (LinkedSpeaker ls : config.linkedSpeakers()) {
			if (currentDim.equals(ls.dimension()) && ls.enabled() && ls.volume() > 0.001f) {
				targets.add(new SpeakerTarget(ls.pos(), ls.volume()));
				if (targets.size() >= Mp3Payloads.MAX_LINKED_SPEAKERS) {
					break;
				}
			}
		}
		return targets;
	}

	private List<SpeakerTarget> buildActiveSpeakerTargetsForStream() {
		return shouldUseLinkedSpeakers() ? buildActiveSpeakerTargets() : Collections.emptyList();
	}

	private void syncNetworkStreamWithCurrentTargets() {
		if (currentTrack == null || alChannel == null) {
			return;
		}
		if (!isServerSupporting3D()) {
			stopOutgoing3dStream(false);
			return;
		}
		if (shouldBroadcastNetwork3D()) {
			if (activeStreamId == null) {
				startOutgoing3dStream(currentTrack);
				if (paused) {
					sendNetworkStateNow();
				}
			} else {
				sendNetworkStateNow();
			}
		} else if (activeStreamId != null) {
			stopOutgoing3dStream(true);
		}
	}

	private void playTrackInternal(Mp3TrackInfo track, double ratio, boolean recordHistory) {
		if (track == null) {
			return;
		}
		if (recordHistory && currentTrack != null && !currentTrack.equals(track)) {
			history.push(currentTrack);
			while (history.size() > 50) {
				history.removeLast();
			}
		}

		stopLocalAndNetwork(true);

		try {
			double clampedRatio = Math.max(0.0, Math.min(0.995, ratio));
			AudioTrackDecoder trackDecoder = AudioTrackDecoder.open(track, clampedRatio);

			this.currentTrack = track;
			this.paused = false;
			this.seekBaseSeconds = track.durationSeconds() * clampedRatio;

			syncLocalSpeakerNodes(Minecraft.getInstance());

			boolean boomboxMode = isBoomboxActive();
			if (shouldBroadcastNetwork3D() && isServerSupporting3D()) {
				startOutgoing3dStream(track);
			}

			this.decodeWorker = new Mp3DecodeWorker("Local-" + track.formatBadge(), trackDecoder, false);
			this.alChannel = new OpenAlStreamChannel(boomboxMode);
			if (boomboxMode) {
				this.alChannel.setWorldPosition(
						activeBoomboxPos.getX() + 0.5,
						activeBoomboxPos.getY() + 0.5,
						activeBoomboxPos.getZ() + 0.5
				);
			}
			this.decodeWorker.start();
		} catch (IOException e) {
			Mp3PlayerMod.LOGGER.error("Failed to play audio track {} ({})", track.file(), track.formatBadge(), e);
			stop();
		}
	}

	public void togglePlayPause() {
		if (currentTrack != null && alChannel != null) {
			setPaused(!paused);
		}
	}

	public void setPaused(boolean shouldPause) {
		if (currentTrack == null || alChannel == null || this.paused == shouldPause) {
			return;
		}
		this.paused = shouldPause;
		if (activeStreamId != null) {
			sendNetworkStateNow();
		}
	}

	public void stop() {
		stopLocalAndNetwork(true);
		currentTrack = null;
		paused = false;
		seekBaseSeconds = 0.0;
		activeBoomboxPos = null;
		activeBoomboxDimension = null;
		activeQueueEntryId = null;
		speakerSystemMode = false;
	}

	/**
	 * Invoked when the server terminates our active stream (e.g. placed Boombox was broken,
	 * another player took over that Boombox, or our speaker queue track was skipped).
	 */
	public boolean onServerStoppedStream(UUID stoppedStreamId) {
		if (activeStreamId == null || !activeStreamId.equals(stoppedStreamId)) {
			return false;
		}
		if (isBoomboxActive() || isSpeakerSystemMode()) {
			stopLocalAndNetwork(false);
			currentTrack = null;
			paused = false;
			seekBaseSeconds = 0.0;
			activeBoomboxPos = null;
			activeBoomboxDimension = null;
			activeQueueEntryId = null;
		} else {
			stopOutgoing3dStream(false);
		}
		return true;
	}

	/**
	 * Invoked when the server notifies us that one or more of our linked speaker blocks were broken
	 * or new speakers were linked into the shared speaker pool.
	 */
	public void onServerUpdatedStreamState(S2CStreamState payload) {
		if (activeStreamId == null || !activeStreamId.equals(payload.streamId())) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			return;
		}
		ResourceKey<Level> dim = mc.level.dimension();
		Set<BlockPos> serverActivePositions = new HashSet<>();
		List<LinkedSpeaker> modifiable = new ArrayList<>(config.linkedSpeakers());
		boolean changed = false;

		if (payload.speakers() != null) {
			for (SpeakerTarget st : payload.speakers()) {
				serverActivePositions.add(st.pos());
				boolean exists = false;
				for (LinkedSpeaker ls : modifiable) {
					if (dim.equals(ls.dimension()) && st.pos().equals(ls.pos())) {
						exists = true;
						break;
					}
				}
				if (!exists && modifiable.size() < Mp3Payloads.MAX_LINKED_SPEAKERS) {
					modifiable.add(new LinkedSpeaker(dim, st.pos(), true, st.volume()));
					changed = true;
				}
			}
		}

		boolean removedBroken = modifiable.removeIf(ls ->
				dim.equals(ls.dimension())
						&& ls.enabled()
						&& ls.volume() > 0.001f
						&& !serverActivePositions.contains(ls.pos())
						&& mc.level.isLoaded(ls.pos())
						&& !mc.level.getBlockState(ls.pos()).is(Mp3PlayerMod.SPEAKER_BLOCK));
		if (changed || removedBroken) {
			config.setLinkedSpeakers(modifiable);
			config.save();
			syncSpeakersDuringPlaybackNow();
		}
	}

	public void seek(double ratio) {
		if (currentTrack == null) {
			return;
		}
		boolean wasPaused = this.paused;
		playTrackInternal(currentTrack, Math.max(0.0, Math.min(0.995, ratio)), false);
		if (wasPaused) {
			setPaused(true);
		}
	}

	public void nextTrack() {
		if (isSpeakerSystemMode() && (waitingSpeakerQueueCount() > 0 || isAnotherPlayerPlayingOnSpeakers())) {
			skipSpeakerQueueTrack();
			return;
		}
		if (currentTrack == null) {
			return;
		}
		List<Mp3TrackInfo> tracks = library.tracks();
		if (tracks.isEmpty()) {
			stop();
			return;
		}
		if (config.shuffle() && tracks.size() > 1) {
			Mp3TrackInfo candidate = currentTrack;
			for (int tries = 0; tries < 6 && candidate != null && candidate.equals(currentTrack); tries++) {
				candidate = tracks.get(random.nextInt(tracks.size()));
			}
			playTrack(candidate != null ? candidate : tracks.getFirst());
			return;
		}

		int idx = findTrackIndex(tracks, currentTrack);
		int nextIdx = (idx + 1) % tracks.size();
		playTrack(tracks.get(nextIdx));
	}

	public void previousTrack() {
		if (currentTrack == null) {
			return;
		}
		List<Mp3TrackInfo> tracks = library.tracks();
		if (tracks.isEmpty()) {
			return;
		}
		if (currentPositionSeconds() > 3) {
			seek(0.0);
			return;
		}
		while (!history.isEmpty()) {
			Mp3TrackInfo prev = history.pop();
			int idx = findTrackIndex(tracks, prev);
			if (idx >= 0) {
				playTrackInternal(tracks.get(idx), 0.0, false);
				return;
			}
		}
		int idx = currentTrack != null ? findTrackIndex(tracks, currentTrack) : 0;
		int prevIdx = (idx - 1 + tracks.size()) % tracks.size();
		playTrackInternal(tracks.get(prevIdx), 0.0, false);
	}

	public void setSpeaker3dMode(boolean enabled) {
		this.speakerSystemMode = false;
		if (isBoomboxActive()) {
			config.setSpeaker3dMode(enabled);
			config.save();
			deactivateBoomboxTarget();
			return;
		}
		if (config.speaker3dMode() != enabled) {
			config.setSpeaker3dMode(enabled);
			config.save();
		}
		refreshImmediateGain(Minecraft.getInstance());
		syncNetworkStreamWithCurrentTargets();
	}

	public void setVolume(float newVolume) {
		config.setVolume(newVolume);
		refreshImmediateGain(Minecraft.getInstance());
		if (activeStreamId != null && volumeSyncCooldown <= 0) {
			sendNetworkStateNow();
			volumeSyncCooldown = 3;
		}
	}

	public void refreshImmediateGain(Minecraft client) {
		if (client == null) {
			return;
		}
		if (alChannel != null) {
			alChannel.updateGainNow(computePrimaryGain(client));
		}
		float baseGain = computeBaseGain(client);
		for (LocalSpeakerNode node : speakerNodes.values()) {
			node.updateGainNow(baseGain);
		}
	}

	private float computeBaseGain(Minecraft client) {
		if (client == null || client.options == null) {
			return 0.0f;
		}
		float masterVol = client.options.getSoundSourceVolume(SoundSource.MASTER);
		float optionsVol = config.optionsVolume();
		return config.volume() * optionsVol * masterVol;
	}

	private float computePrimaryGain(Minecraft client) {
		if (client == null || client.options == null) {
			return 0.0f;
		}
		if (isBoomboxActive() && (client.level == null || !client.level.dimension().equals(activeBoomboxDimension))) {
			return 0.0f;
		}
		if (isSpeakerSystemMode() && activeLinkedSpeakerCount() > 0) {
			// In Speaker System mode, sound comes in 3D from the linked speaker blocks in the world
			return 0.0f;
		}
		return computeBaseGain(client);
	}

	public static boolean hasActiveWorldConnection(Minecraft client) {
		if (client == null || client.level == null || client.player == null) {
			return false;
		}
		ClientPacketListener listener = client.getConnection();
		if (listener == null) {
			return false;
		}
		Connection connection = listener.getConnection();
		return connection != null && connection.isConnected();
	}

	private void syncLocalSpeakerNodes(Minecraft client) {
		if (client == null || client.level == null || !shouldUseLinkedSpeakers()) {
			closeAllSpeakerNodes();
			return;
		}
		ResourceKey<Level> currentDim = client.level.dimension();
		List<LinkedSpeaker> currentList = config.linkedSpeakers();
		List<BlockPos> brokenPositions = null;
		Set<BlockPos> activePositions = new HashSet<>();

		for (LinkedSpeaker ls : currentList) {
			if (!currentDim.equals(ls.dimension())) {
				continue;
			}
			BlockPos pos = ls.pos();
			if (client.level.isLoaded(pos) && !client.level.getBlockState(pos).is(Mp3PlayerMod.SPEAKER_BLOCK)) {
				if (brokenPositions == null) {
					brokenPositions = new ArrayList<>();
				}
				brokenPositions.add(pos);
				continue;
			}
			if (ls.enabled() && ls.volume() > 0.001f) {
				activePositions.add(pos);
				LocalSpeakerNode node = speakerNodes.get(pos);
				if (node == null) {
					speakerNodes.put(pos, new LocalSpeakerNode(pos, ls.volume()));
				} else {
					node.volume = ls.volume();
				}
			}
		}

		Iterator<Map.Entry<BlockPos, LocalSpeakerNode>> it = speakerNodes.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<BlockPos, LocalSpeakerNode> entry = it.next();
			if (!activePositions.contains(entry.getKey())) {
				entry.getValue().close();
				it.remove();
			}
		}

		if (brokenPositions != null) {
			List<LinkedSpeaker> updated = new ArrayList<>(currentList);
			Set<BlockPos> toRemove = new HashSet<>(brokenPositions);
			updated.removeIf(ls -> currentDim.equals(ls.dimension()) && toRemove.contains(ls.pos()));
			config.setLinkedSpeakers(updated);
			config.save();
			if (activeStreamId != null) {
				sendNetworkStateNow();
			}
		}
	}

	private void closeAllSpeakerNodes() {
		for (LocalSpeakerNode node : speakerNodes.values()) {
			node.close();
		}
		speakerNodes.clear();
	}

	public void tick(Minecraft client) {
		// Immediately stop all local & network playback if the server connection was lost or timed out
		if (!hasActiveWorldConnection(client)) {
			if (alChannel != null || decodeWorker != null || currentTrack != null || activeStreamId != null) {
				onClientDisconnect();
			}
			return;
		}

		// Immediately stop Boombox playback if the placed Boombox block was broken locally
		if (isBoomboxActive()
				&& client.level.dimension().equals(activeBoomboxDimension)
				&& client.level.isLoaded(activeBoomboxPos)
				&& !client.level.getBlockState(activeBoomboxPos).is(Mp3PlayerMod.BOOMBOX_BLOCK)) {
			if (activeLinkedSpeakerCount() > 0) {
				this.activeBoomboxPos = null;
				this.activeBoomboxDimension = null;
				this.speakerSystemMode = true;
				if (alChannel != null) {
					alChannel.setPositional3D(false);
				}
				syncNetworkStreamWithCurrentTargets();
			} else {
				stop();
				return;
			}
		}

		if (alChannel != null && decodeWorker != null) {
			syncLocalSpeakerNodes(client);
			if (isSpeakerSystemMode() && activeLinkedSpeakerCount() == 0) {
				stop();
				return;
			}
		}

		if (volumeSyncCooldown > 0) {
			volumeSyncCooldown--;
		}
		if (activeStreamId != null && Math.abs(lastSentNetworkVolume - config.volume()) > 0.005f && volumeSyncCooldown == 0) {
			sendNetworkStateNow();
		}

		// 1. Pump local OpenAL playback (tapping queued PCM chunks into 3D Opus broadcaster and linked Speaker nodes)
		boolean finished = false;
		if (alChannel != null && decodeWorker != null) {
			if (isBoomboxActive()) {
				alChannel.setWorldPosition(
						activeBoomboxPos.getX() + 0.5,
						activeBoomboxPos.getY() + 0.5,
						activeBoomboxPos.getZ() + 0.5
				);
			}
			float primaryGain = computePrimaryGain(client);
			finished = alChannel.pump(
					decodeWorker.outputQueue(),
					paused,
					primaryGain,
					2,
					this::onLocalChunkQueued
			);

			float baseGain = computeBaseGain(client);
			for (LocalSpeakerNode node : speakerNodes.values()) {
				boolean inRange = client.player != null
						&& node.pos.distToCenterSqr(client.player.position()) <= ACTIVE_SPEAKER_RANGE_SQR;
				node.pump(alChannel, paused, baseGain * node.volume, inRange);
			}
		}

		// 2. Drain ready <= 4 KB Opus 3D packets to server
		if (activeStreamId != null) {
			if (!isServerSupporting3D()) {
				stopOutgoing3dStream(false);
			} else if (!paused && opusBroadcaster != null) {
				int sentThisTick = 0;
				while (sentThisTick < MAX_CHUNKS_PER_TICK) {
					byte[] opusPacket = opusBroadcaster.pollOutboundPacket();
					if (opusPacket == null) {
						break;
					}
					ClientPlayNetworking.send(new C2SStreamChunk(
							activeStreamId,
							streamSequence++,
							opusPacket
					));
					sentThisTick++;
				}
			}
		}

		if (finished) {
			onTrackNaturallyFinished();
		}
	}

	private void onLocalChunkQueued(PcmChunk chunk) {
		Opus3dBroadcaster broadcaster = this.opusBroadcaster;
		if (broadcaster != null && activeStreamId != null) {
			broadcaster.submitPcm(chunk);
		}
		if (!speakerNodes.isEmpty()) {
			for (LocalSpeakerNode node : speakerNodes.values()) {
				node.enqueue(chunk);
			}
		}
	}

	public void onClientDisconnect() {
		stopLocalAndNetwork(false);
		resetLinkedSpeakers();
		currentTrack = null;
		paused = false;
		seekBaseSeconds = 0.0;
		activeBoomboxPos = null;
		activeBoomboxDimension = null;
		speakerSystemMode = false;
		history.clear();
	}

	private void onTrackNaturallyFinished() {
		Mp3TrackInfo finishedTrack = this.currentTrack;
		UUID finishedQueueId = this.activeQueueEntryId;
		boolean wasSpeakerMode = isSpeakerSystemMode() || finishedQueueId != null;

		if (wasSpeakerMode && isServerSupporting3D()) {
			stopLocalAndNetwork(false);
			if (waitingSpeakerQueueCount() > 0) {
				this.currentTrack = null;
				this.paused = false;
				this.seekBaseSeconds = 0.0;
				this.activeQueueEntryId = null;
				ClientPlayNetworking.send(new C2SQueueAction(
						QueueActionType.ADVANCE_FINISHED,
						Optional.ofNullable(finishedQueueId),
						buildActiveSpeakerTargets()
				));
				return;
			}
			if (finishedTrack != null && config.loopMode() == LoopMode.ONE) {
				enqueueTrackOnSpeakers(finishedTrack, true);
				return;
			}
			List<Mp3TrackInfo> tracks = library.tracks();
			if (finishedTrack != null && !tracks.isEmpty() && (config.loopMode() == LoopMode.ALL || config.shuffle())) {
				if (config.shuffle() && tracks.size() > 1) {
					Mp3TrackInfo candidate = finishedTrack;
					for (int tries = 0; tries < 6 && candidate != null && candidate.equals(finishedTrack); tries++) {
						candidate = tracks.get(random.nextInt(tracks.size()));
					}
					enqueueTrackOnSpeakers(candidate != null ? candidate : tracks.getFirst(), true);
				} else {
					int idx = findTrackIndex(tracks, finishedTrack);
					int nextIdx = (idx + 1) % tracks.size();
					enqueueTrackOnSpeakers(tracks.get(nextIdx), true);
				}
				return;
			}
			this.currentTrack = null;
			this.paused = false;
			this.seekBaseSeconds = 0.0;
			this.activeQueueEntryId = null;
			ClientPlayNetworking.send(new C2SQueueAction(
					QueueActionType.ADVANCE_FINISHED,
					Optional.ofNullable(finishedQueueId),
					buildActiveSpeakerTargets()
			));
			return;
		}

		if (wasSpeakerMode && !localFallbackQueue.isEmpty()) {
			stopLocalAndNetwork(true);
			Mp3TrackInfo nextQueued = localFallbackQueue.pollFirst();
			if (nextQueued != null) {
				playTrackInternal(nextQueued, 0.0, true);
				return;
			}
		}

		stopLocalAndNetwork(true);

		List<Mp3TrackInfo> tracks = library.tracks();
		if (finishedTrack == null || tracks.isEmpty()) {
			currentTrack = null;
			activeBoomboxPos = null;
			activeBoomboxDimension = null;
			speakerSystemMode = false;
			return;
		}

		if (config.loopMode() == LoopMode.ONE) {
			playTrackInternal(finishedTrack, 0.0, false);
			return;
		}

		if (config.shuffle()) {
			nextTrack();
			return;
		}

		int idx = findTrackIndex(tracks, finishedTrack);
		if (idx + 1 < tracks.size()) {
			playTrack(tracks.get(idx + 1));
		} else if (config.loopMode() == LoopMode.ALL) {
			playTrack(tracks.getFirst());
		} else {
			currentTrack = null;
			paused = false;
			seekBaseSeconds = 0.0;
			activeBoomboxPos = null;
			activeBoomboxDimension = null;
			speakerSystemMode = false;
		}
	}

	private void startOutgoing3dStream(Mp3TrackInfo track) {
		stopOutgoing3dStream(true);
		this.activeStreamId = UUID.randomUUID();
		this.opusBroadcaster = new Opus3dBroadcaster();
		this.streamSequence = 0;
		this.lastSentNetworkVolume = config.volume();

		ClientPlayNetworking.send(new C2SStreamStart(
				activeStreamId,
				track.fullDisplayLabel(),
				config.volume(),
				Optional.ofNullable(activeBoomboxPos),
				shouldEmitFromPlayerBody(),
				buildActiveSpeakerTargetsForStream()
		));
	}

	private void sendNetworkStateNow() {
		if (activeStreamId != null && isServerSupporting3D()) {
			lastSentNetworkVolume = config.volume();
			ClientPlayNetworking.send(new C2SStreamState(
					activeStreamId,
					paused,
					config.volume(),
					Optional.ofNullable(activeBoomboxPos),
					shouldEmitFromPlayerBody(),
					buildActiveSpeakerTargetsForStream()
			));
		}
	}

	private void stopOutgoing3dStream(boolean notifyServer) {
		if (opusBroadcaster != null) {
			opusBroadcaster.close();
			opusBroadcaster = null;
		}
		if (activeStreamId != null) {
			UUID oldId = activeStreamId;
			activeStreamId = null;
			if (notifyServer && isServerSupporting3D()) {
				ClientPlayNetworking.send(new C2SStreamStop(oldId));
			}
		}
	}

	private void stopLocalAndNetwork(boolean notifyServer) {
		stopOutgoing3dStream(notifyServer);
		if (decodeWorker != null) {
			decodeWorker.cancel();
			decodeWorker = null;
		}
		if (alChannel != null) {
			alChannel.close();
			alChannel = null;
		}
		closeAllSpeakerNodes();
	}

	private static int findTrackIndex(List<Mp3TrackInfo> tracks, Mp3TrackInfo target) {
		for (int i = 0; i < tracks.size(); i++) {
			if (tracks.get(i).file().equals(target.file())) {
				return i;
			}
		}
		return -1;
	}
}
