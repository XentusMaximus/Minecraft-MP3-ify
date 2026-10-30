package de.vibecoded.mp3player.client.audio;

import de.vibecoded.mp3player.Mp3PlayerMod;
import de.vibecoded.mp3player.client.audio.Opus3dStreamCodec.Opus3dBroadcaster;
import de.vibecoded.mp3player.client.audio.decoder.AudioTrackDecoder;
import de.vibecoded.mp3player.client.config.Mp3Config;
import de.vibecoded.mp3player.client.config.Mp3Config.LoopMode;
import de.vibecoded.mp3player.client.library.Mp3LibraryManager;
import de.vibecoded.mp3player.mp3.Mp3TrackInfo;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamChunk;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStart;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamState;
import de.vibecoded.mp3player.network.Mp3Payloads.C2SStreamStop;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * Controls local multi-format audio playback (MP3, M4A, OPUS, WAV, FLAC, OGG),
 * placed Boombox 3D world-positioned playback, playlist navigation (Shuffle / Loop / Seek),
 * and synchronized 48 kHz Opus 3D spatial network streaming (Essential & Multiplayer safe).
 */
public final class Mp3AudioController {
	private static final int MAX_CHUNKS_PER_TICK = 5;

	private final Mp3Config config;
	private final Mp3LibraryManager library;
	private final Random random = new Random();
	private final Deque<Mp3TrackInfo> history = new ArrayDeque<>();

	private Mp3TrackInfo currentTrack;
	private Mp3DecodeWorker decodeWorker;
	private OpenAlStreamChannel alChannel;
	private boolean paused = false;
	private double seekBaseSeconds = 0.0;

	// Placed Boombox target (when non-null, BOTH local OpenAL and network stream emanate in 3D from this BlockPos)
	private BlockPos activeBoomboxPos;
	private ResourceKey<Level> activeBoomboxDimension;

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
		playTrackInternal(track, 0.0, true);
	}

	public void playTrackOnBoombox(Mp3TrackInfo track, ResourceKey<Level> dimension, BlockPos pos) {
		if (dimension != null && pos != null) {
			this.activeBoomboxPos = pos.immutable();
			this.activeBoomboxDimension = dimension;
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
			return;
		}
		this.activeBoomboxPos = pos.immutable();
		this.activeBoomboxDimension = dimension;
		togglePlayPause();
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

			boolean boomboxMode = isBoomboxActive();
			if ((boomboxMode || config.speaker3dMode()) && isServerSupporting3D()) {
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
			return;
		}
		List<Mp3TrackInfo> tracks = library.tracks();
		if (!tracks.isEmpty()) {
			if (config.shuffle()) {
				playTrack(tracks.get(random.nextInt(tracks.size())));
			} else {
				playTrack(tracks.getFirst());
			}
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
	}

	/**
	 * Invoked when the server terminates our active stream (e.g. placed Boombox was broken
	 * or another player took over that Boombox).
	 */
	public boolean onServerStoppedStream(UUID stoppedStreamId) {
		if (activeStreamId == null || !activeStreamId.equals(stoppedStreamId)) {
			return false;
		}
		stopLocalAndNetwork(false);
		currentTrack = null;
		paused = false;
		seekBaseSeconds = 0.0;
		activeBoomboxPos = null;
		activeBoomboxDimension = null;
		return true;
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

		int idx = currentTrack != null ? findTrackIndex(tracks, currentTrack) : -1;
		int nextIdx = (idx + 1) % tracks.size();
		playTrack(tracks.get(nextIdx));
	}

	public void previousTrack() {
		List<Mp3TrackInfo> tracks = library.tracks();
		if (tracks.isEmpty()) {
			return;
		}
		if (currentTrack != null && currentPositionSeconds() > 3) {
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
		if (isBoomboxActive()) {
			config.setSpeaker3dMode(enabled);
			config.save();
			deactivateBoomboxTarget();
			return;
		}
		if (config.speaker3dMode() == enabled) {
			return;
		}
		config.setSpeaker3dMode(enabled);
		config.save();

		if (!enabled) {
			// Seamlessly stop network broadcast while keeping local stereo playback running!
			stopOutgoing3dStream(true);
		} else if (currentTrack != null && alChannel != null && isServerSupporting3D()) {
			startOutgoing3dStream(currentTrack);
			if (paused) {
				sendNetworkStateNow();
			}
		}
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
		if (alChannel != null && client != null) {
			alChannel.updateGainNow(computeEffectiveGain(client));
		}
	}

	private float computeEffectiveGain(Minecraft client) {
		if (client == null || client.options == null) {
			return 0.0f;
		}
		if (isBoomboxActive() && (client.level == null || !client.level.dimension().equals(activeBoomboxDimension))) {
			return 0.0f;
		}
		float masterVol = client.options.getSoundSourceVolume(SoundSource.MASTER);
		float optionsVol = config.optionsVolume();
		return config.volume() * optionsVol * masterVol;
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
			stop();
			return;
		}

		if (volumeSyncCooldown > 0) {
			volumeSyncCooldown--;
		}
		if (activeStreamId != null && Math.abs(lastSentNetworkVolume - config.volume()) > 0.005f && volumeSyncCooldown == 0) {
			sendNetworkStateNow();
		}

		// 1. Pump local OpenAL playback (tapping queued PCM chunks into 3D Opus broadcaster when active)
		boolean finished = false;
		if (alChannel != null && decodeWorker != null) {
			if (isBoomboxActive()) {
				alChannel.setWorldPosition(
						activeBoomboxPos.getX() + 0.5,
						activeBoomboxPos.getY() + 0.5,
						activeBoomboxPos.getZ() + 0.5
				);
			}
			float effectiveGain = computeEffectiveGain(client);
			finished = alChannel.pump(
					decodeWorker.outputQueue(),
					paused,
					effectiveGain,
					2,
					this::onLocalChunkQueued
			);
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
	}

	public void onClientDisconnect() {
		stopLocalAndNetwork(false);
		currentTrack = null;
		paused = false;
		seekBaseSeconds = 0.0;
		activeBoomboxPos = null;
		activeBoomboxDimension = null;
		history.clear();
	}

	private void onTrackNaturallyFinished() {
		Mp3TrackInfo finishedTrack = this.currentTrack;
		stopLocalAndNetwork(true);

		List<Mp3TrackInfo> tracks = library.tracks();
		if (finishedTrack == null || tracks.isEmpty()) {
			currentTrack = null;
			activeBoomboxPos = null;
			activeBoomboxDimension = null;
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
				Optional.ofNullable(activeBoomboxPos)
		));
	}

	private void sendNetworkStateNow() {
		if (activeStreamId != null && isServerSupporting3D()) {
			lastSentNetworkVolume = config.volume();
			ClientPlayNetworking.send(new C2SStreamState(activeStreamId, paused, config.volume()));
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
