package de.vibecoded.mp3player.client.audio;

public final class PcmChunk {
	public static final PcmChunk EOS = new PcmChunk(new short[0], 44100, 1, 0.0f, true);

	private final short[] samples;
	private final int sampleRate;
	private final int channels;
	private final float rmsLevel;
	private final boolean endOfStream;
	private volatile short[] cachedMonoSamples;

	public PcmChunk(
			short[] samples,
			int sampleRate,
			int channels,
			float rmsLevel,
			boolean endOfStream
	) {
		this.samples = samples;
		this.sampleRate = sampleRate;
		this.channels = channels;
		this.rmsLevel = rmsLevel;
		this.endOfStream = endOfStream;
	}

	public short[] samples() {
		return samples;
	}

	public int sampleRate() {
		return sampleRate;
	}

	public int channels() {
		return channels;
	}

	public float rmsLevel() {
		return rmsLevel;
	}

	public boolean endOfStream() {
		return endOfStream;
	}

	public int frameCount() {
		return channels > 0 ? (samples.length / channels) : 0;
	}

	/**
	 * Returns mono-downmixed samples (cached so multiple linked 3D speaker channels
	 * can upload the exact same mono buffer without redundant downmix loops).
	 */
	public short[] monoSamples() {
		if (channels <= 1 || samples.length == 0) {
			return samples;
		}
		short[] mono = cachedMonoSamples;
		if (mono == null) {
			mono = new short[samples.length / 2];
			for (int i = 0, j = 0; i + 1 < samples.length; i += 2, j++) {
				mono[j] = (short) (((int) samples[i] + (int) samples[i + 1]) >> 1);
			}
			cachedMonoSamples = mono;
		}
		return mono;
	}
}


