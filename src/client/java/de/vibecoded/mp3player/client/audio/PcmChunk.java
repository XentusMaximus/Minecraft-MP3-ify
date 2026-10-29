package de.vibecoded.mp3player.client.audio;

public record PcmChunk(
		short[] samples,
		int sampleRate,
		int channels,
		float rmsLevel,
		boolean endOfStream
) {
	public static final PcmChunk EOS = new PcmChunk(new short[0], 44100, 1, 0.0f, true);

	public int frameCount() {
		return channels > 0 ? (samples.length / channels) : 0;
	}
}

