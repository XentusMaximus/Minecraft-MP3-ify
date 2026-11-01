package de.vibecoded.mp3player.client.audio;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.client.audio.Opus3dStreamCodec.RemoteOpusDecodeWorker;
import de.vibecoded.mp3player.client.config.Mp3Config;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.S2CStreamStop;
import de.vibecoded.mp3player.network.Mp3Payloads.SpeakerTarget;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * Manages incoming 48 kHz Opus 3D positional audio streams from other players,
 * placed Boomboxes, and linked Speaker networks in Multiplayer / Essential worlds
 * (supporting all 6 source audio formats).
 */
public final class RemoteSpeakerManager {
	private static final double ACTIVE_SPEAKER_RANGE_SQR = 80.0 * 80.0;

	private static final class RemoteSpeakerNode implements AutoCloseable {
		final BlockPos pos;
		float volume;
		final Queue<PcmChunk> inbox = new ArrayDeque<>(24);
		OpenAlStreamChannel channel;

		RemoteSpeakerNode(BlockPos pos, float volume) {
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

	public static final class RemoteStream implements AutoCloseable {
		private final UUID speakerUuid;
		private final String speakerName;
		private final UUID streamId;
		private final String trackTitle;
		private final RemoteOpusDecodeWorker decodeWorker;
		private final OpenAlStreamChannel alChannel;
		private final Map<BlockPos, RemoteSpeakerNode> speakerNodes = new LinkedHashMap<>();
		private volatile BlockPos boomboxPos;
		private volatile boolean emitFromPlayer;
		private volatile List<SpeakerTarget> speakers;
		private volatile float speakerVolume;
		private volatile boolean paused;
		private volatile long lastPacketMs;

		RemoteStream(
				UUID speakerUuid,
				String speakerName,
				UUID streamId,
				String trackTitle,
				float volume,
				BlockPos boomboxPos,
				boolean emitFromPlayer,
				List<SpeakerTarget> speakers
		) {
			this.speakerUuid = speakerUuid;
			this.speakerName = speakerName;
			this.streamId = streamId;
			this.trackTitle = trackTitle;
			this.speakerVolume = volume;
			this.boomboxPos = boomboxPos != null ? boomboxPos.immutable() : null;
			this.emitFromPlayer = emitFromPlayer;
			this.speakers = speakers != null ? List.copyOf(speakers) : Collections.emptyList();
			this.paused = false;
			this.lastPacketMs = System.currentTimeMillis();
			this.decodeWorker = new RemoteOpusDecodeWorker(speakerName);
			this.alChannel = new OpenAlStreamChannel(true);
			if (this.boomboxPos != null) {
				this.alChannel.setWorldPosition(
						this.boomboxPos.getX() + 0.5,
						this.boomboxPos.getY() + 0.5,
						this.boomboxPos.getZ() + 0.5
				);
			}
			this.decodeWorker.start();
		}

		public String speakerName() {
			return speakerName;
		}

		public String trackTitle() {
			return trackTitle;
		}

		public boolean paused() {
			return paused;
		}

		public BlockPos boomboxPos() {
			return boomboxPos;
		}

		public List<SpeakerTarget> speakers() {
			return speakers;
		}

		boolean hasSpeakerAt(BlockPos pos) {
			if (pos == null) {
				return false;
			}
			for (SpeakerTarget st : speakers) {
				if (pos.equals(st.pos())) {
					return true;
				}
			}
			return false;
		}

		void pushAudioPacket(byte[] bytes, BlockPos packetBoomboxPos) {
			this.lastPacketMs = System.currentTimeMillis();
			if (packetBoomboxPos != null && this.boomboxPos == null) {
				this.boomboxPos = packetBoomboxPos.immutable();
			}
			this.decodeWorker.pushPacket(bytes);
		}

		void updateState(
				boolean paused,
				float volume,
				BlockPos boomboxPos,
				boolean emitFromPlayer,
				List<SpeakerTarget> speakers
		) {
			this.paused = paused;
			this.speakerVolume = volume;
			this.boomboxPos = boomboxPos != null ? boomboxPos.immutable() : null;
			this.emitFromPlayer = emitFromPlayer;
			if (speakers != null) {
				this.speakers = List.copyOf(speakers);
			}
			this.lastPacketMs = System.currentTimeMillis();
		}

		void onChunkQueued(PcmChunk chunk) {
			if (speakerNodes.isEmpty()) {
				return;
			}
			for (RemoteSpeakerNode node : speakerNodes.values()) {
				node.enqueue(chunk);
			}
		}

		void syncSpeakerNodes(Minecraft client) {
			List<SpeakerTarget> targetList = this.speakers;
			if (targetList.isEmpty()) {
				if (!speakerNodes.isEmpty()) {
					for (RemoteSpeakerNode node : speakerNodes.values()) {
						node.close();
					}
					speakerNodes.clear();
				}
				return;
			}

			Set<BlockPos> validPositions = new HashSet<>(targetList.size());
			for (SpeakerTarget st : targetList) {
				BlockPos pos = st.pos();
				if (client.level != null
						&& client.level.isLoaded(pos)
						&& !client.level.getBlockState(pos).is(Mp3PlayerMod.SPEAKER_BLOCK)) {
					continue;
				}
				validPositions.add(pos);
				RemoteSpeakerNode node = speakerNodes.get(pos);
				if (node == null) {
					speakerNodes.put(pos, new RemoteSpeakerNode(pos, st.volume()));
				} else {
					node.volume = st.volume();
				}
			}

			Iterator<Map.Entry<BlockPos, RemoteSpeakerNode>> it = speakerNodes.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<BlockPos, RemoteSpeakerNode> entry = it.next();
				if (!validPositions.contains(entry.getKey())) {
					entry.getValue().close();
					it.remove();
				}
			}
		}

		@Override
		public void close() {
			decodeWorker.close();
			alChannel.close();
			for (RemoteSpeakerNode node : speakerNodes.values()) {
				node.close();
			}
			speakerNodes.clear();
		}
	}

	private final Mp3Config config;
	private final Map<UUID, RemoteStream> activeStreams = new ConcurrentHashMap<>();

	public RemoteSpeakerManager(Mp3Config config) {
		this.config = config;
	}

	public Collection<RemoteStream> activeStreams() {
		return activeStreams.values();
	}

	public int activeCount() {
		return activeStreams.size();
	}

	public RemoteStream findStreamAtBoombox(BlockPos pos) {
		if (pos == null) {
			return null;
		}
		for (RemoteStream stream : activeStreams.values()) {
			if (pos.equals(stream.boomboxPos)) {
				return stream;
			}
		}
		return null;
	}

	public RemoteStream findStreamAtSpeaker(BlockPos pos) {
		if (pos == null) {
			return null;
		}
		for (RemoteStream stream : activeStreams.values()) {
			if (stream.hasSpeakerAt(pos)) {
				return stream;
			}
		}
		return null;
	}

	public void onStreamStart(S2CStreamStart payload) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.getUUID().equals(payload.speakerUuid())) {
			return;
		}
		RemoteStream old = activeStreams.remove(payload.speakerUuid());
		if (old != null) {
			old.close();
		}
		BlockPos boomboxPos = payload.boomboxPos() != null ? payload.boomboxPos().orElse(null) : null;
		RemoteStream stream = new RemoteStream(
				payload.speakerUuid(),
				payload.speakerName(),
				payload.streamId(),
				payload.trackTitle(),
				payload.volume(),
				boomboxPos,
				payload.emitFromPlayer(),
				payload.speakers()
		);
		activeStreams.put(payload.speakerUuid(), stream);
	}

	public void onStreamChunk(S2CStreamChunk payload) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.getUUID().equals(payload.speakerUuid())) {
			return;
		}
		BlockPos boomboxPos = payload.boomboxPos() != null ? payload.boomboxPos().orElse(null) : null;
		RemoteStream stream = activeStreams.get(payload.speakerUuid());
		if (stream == null || !stream.streamId.equals(payload.streamId())) {
			if (stream != null) {
				stream.close();
			}
			stream = new RemoteStream(
					payload.speakerUuid(),
					"Player",
					payload.streamId(),
					"3D-Stream",
					0.8f,
					boomboxPos,
					boomboxPos == null,
					Collections.emptyList()
			);
			activeStreams.put(payload.speakerUuid(), stream);
		}
		stream.pushAudioPacket(payload.mp3Frames(), boomboxPos);
	}

	public void onStreamState(S2CStreamState payload) {
		RemoteStream stream = activeStreams.get(payload.speakerUuid());
		if (stream != null && stream.streamId.equals(payload.streamId())) {
			BlockPos boomboxPos = payload.boomboxPos() != null ? payload.boomboxPos().orElse(null) : null;
			stream.updateState(
					payload.paused(),
					payload.volume(),
					boomboxPos,
					payload.emitFromPlayer(),
					payload.speakers()
			);
		}
	}

	public void onStreamStop(S2CStreamStop payload) {
		RemoteStream stream = activeStreams.get(payload.speakerUuid());
		if (stream != null && stream.streamId.equals(payload.streamId())) {
			activeStreams.remove(payload.speakerUuid(), stream);
			stream.close();
		}
	}

	public void refreshImmediateGain(Minecraft client) {
		if (client == null || client.options == null || activeStreams.isEmpty()) {
			return;
		}
		float masterVol = client.options.getSoundSourceVolume(SoundSource.MASTER);
		float optionsVol = config.optionsVolume();
		for (RemoteStream stream : activeStreams.values()) {
			float baseGain = config.muteOthers() ? 0.0f : (stream.speakerVolume * optionsVol * masterVol);
			boolean primaryAudible = stream.boomboxPos != null || stream.emitFromPlayer;
			stream.alChannel.updateGainNow(primaryAudible ? baseGain : 0.0f);
			for (RemoteSpeakerNode node : stream.speakerNodes.values()) {
				node.updateGainNow(baseGain);
			}
		}
	}

	public void tick(Minecraft client) {
		if (activeStreams.isEmpty()) {
			return;
		}
		if (!Mp3AudioController.hasActiveWorldConnection(client)) {
			stopAll();
			return;
		}

		float masterVol = client.options.getSoundSourceVolume(SoundSource.MASTER);
		float optionsVol = config.optionsVolume();
		long now = System.currentTimeMillis();

		Iterator<Map.Entry<UUID, RemoteStream>> it = activeStreams.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, RemoteStream> entry = it.next();
			RemoteStream stream = entry.getValue();

			long timeout = stream.paused ? 300_000L : 15_000L;
			if (now - stream.lastPacketMs > timeout) {
				it.remove();
				stream.close();
				continue;
			}

			stream.syncSpeakerNodes(client);

			float baseGain = config.muteOthers() ? 0.0f : (stream.speakerVolume * optionsVol * masterVol);
			float primaryGain = 0.0f;

			BlockPos bPos = stream.boomboxPos;
			if (bPos != null) {
				if (client.level != null
						&& client.level.isLoaded(bPos)
						&& !client.level.getBlockState(bPos).is(Mp3PlayerMod.BOOMBOX_BLOCK)) {
					if (stream.speakerNodes.isEmpty()) {
						it.remove();
						stream.close();
						continue;
					}
					stream.boomboxPos = null;
				} else {
					stream.alChannel.setWorldPosition(
							bPos.getX() + 0.5,
							bPos.getY() + 0.5,
							bPos.getZ() + 0.5
					);
					primaryGain = baseGain;
				}
			} else if (stream.emitFromPlayer) {
				Player speakerEntity = findPlayer(client, stream.speakerUuid);
				if (speakerEntity != null) {
					stream.alChannel.setWorldPosition(
							speakerEntity.getX(),
							speakerEntity.getY() + 1.15,
							speakerEntity.getZ()
					);
					primaryGain = baseGain;
				}
			}

			boolean finished = stream.alChannel.pump(
					stream.decodeWorker.outputQueue(),
					stream.paused,
					primaryGain,
					3,
					stream::onChunkQueued
			);

			for (RemoteSpeakerNode node : stream.speakerNodes.values()) {
				boolean inRange = client.player != null
						&& node.pos.distToCenterSqr(client.player.position()) <= ACTIVE_SPEAKER_RANGE_SQR;
				node.pump(stream.alChannel, stream.paused, baseGain * node.volume, inRange);
			}

			if (finished) {
				it.remove();
				stream.close();
			}
		}
	}

	private static Player findPlayer(Minecraft client, UUID uuid) {
		if (client.level == null) {
			return null;
		}
		for (Player p : client.level.players()) {
			if (p.getUUID().equals(uuid)) {
				return p;
			}
		}
		return null;
	}

	public void stopAll() {
		for (RemoteStream stream : activeStreams.values()) {
			stream.close();
		}
		activeStreams.clear();
	}
}
