/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.extractor.ts;

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.ParsableBitArray;
import com.google.android.exoplayer2.util.ParsableByteArray;

/**
 * Parses a continuous AV3A byte stream and extracts individual audio frames.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See 
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public final class Av3aReader implements ElementaryStreamReader {

  // AV3A sampling_freq_id -> sample rate (AVS3 P3 spec Table)
  private static final int[] SAMPLE_RATE_TABLE = {
    192000, 96000, 48000, 44100, 32000, 22050, 16000, 11025, 8000, 0, 0, 0, 0, 0, 0, 0
  };

  // AV3A channel_number_index -> channel count (AVS3 P3 spec Table)
  private static final int[] CHANNEL_COUNT_TABLE = {
    0,  // 0: reserved
    1,  // 1: Mono
    2,  // 2: Stereo
    3,  // 3: 3.0
    4,  // 4: Quad
    5,  // 5: 5.0
    6,  // 6: 5.1
    7,  // 7: 7.0
    8,  // 8: 7.1
    10, // 9: 5.1.4 (10ch)
    10, // 10: 7.1.2 (10ch)
    12, // 11: 7.1.4 (12ch)
    16, // 12: HOA order3 (16ch)
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

  // Samples per frame for AV3A (fixed at 2048 per AVS3 spec)
  private static final int SAMPLES_PER_FRAME = 2048;

  private static final int HEADER_SIZE = 7;

  private static final int STATE_FINDING_SYNC = 0;
  private static final int STATE_READING_HEADER = 1;
  private static final int STATE_READING_SAMPLE = 2;

  @Nullable private final String language;
  @Nullable private TrackOutput output;

  private int state;
  private int bytesRead;
  private long timeUs;
  private int frameSize;
  private boolean hasOutputFormat;

  // Parsed from frame header
  private int sampleRate;
  private int channelCount;
  private long frameDurationUs;

  private final ParsableByteArray headerScratch;
  private final ParsableBitArray headerBitArray;

  public Av3aReader(@Nullable String language) {
    this.language = language;
    headerScratch = new ParsableByteArray(new byte[HEADER_SIZE]);
    headerBitArray = new ParsableBitArray();
    state = STATE_FINDING_SYNC;
    timeUs = C.TIME_UNSET;
    sampleRate = 44100; // safe default
    channelCount = 2;   // safe default
    frameDurationUs = (SAMPLES_PER_FRAME * 1_000_000L) / sampleRate;
  }

  @Override
  public void seek() {
    state = STATE_FINDING_SYNC;
    bytesRead = 0;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, PesReader.TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if (pesTimeUs != C.TIME_UNSET) {
      timeUs = pesTimeUs;
    }
  }

  @Override
  public void consume(ParsableByteArray data) {
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_FINDING_SYNC:
          if (skipToNextSync(data)) {
            state = STATE_READING_HEADER;
          }
          break;
        case STATE_READING_HEADER:
          int bytesToRead = Math.min(data.bytesLeft(), HEADER_SIZE - bytesRead);
          data.readBytes(headerScratch.getData(), bytesRead, bytesToRead);
          bytesRead += bytesToRead;
          if (bytesRead == HEADER_SIZE) {
            headerBitArray.reset(headerScratch.getData());
            if (!parseHeader(headerBitArray)) {
              // Invalid header, restart sync search
              state = STATE_FINDING_SYNC;
              bytesRead = 0;
              break;
            }
            if (!hasOutputFormat) {
              outputFormat();
            }
            // Write header bytes to output (downstream decoder needs full frame incl. header)
            output.sampleData(
                new ParsableByteArray(headerScratch.getData(), HEADER_SIZE), HEADER_SIZE);
            state = STATE_READING_SAMPLE;
          }
          break;
        case STATE_READING_SAMPLE:
          int bytesLeft = frameSize - bytesRead;
          int bytesAvailable = Math.min(data.bytesLeft(), bytesLeft);
          output.sampleData(data, bytesAvailable);
          bytesRead += bytesAvailable;
          if (bytesRead == frameSize) {
            if (timeUs != C.TIME_UNSET) {
              output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null);
              // Dynamically advance timestamp based on actual sample rate
              timeUs += frameDurationUs;
            }
            state = STATE_FINDING_SYNC;
            bytesRead = 0;
          }
          break;
        default:
          break;
      }
    }
  }

  @Override
  public void packetFinished() {
    // do nothing
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Advances data until a potential AV3A sync word (0xFFF) is found.
   * Returns true if sync found; headerScratch[0..1] will contain the sync bytes.
   */
  private boolean skipToNextSync(ParsableByteArray data) {
    byte[] buf = data.getData();
    int pos = data.getPosition();
    int limit = data.limit();
    while (pos < limit - 1) {
      if ((buf[pos] & 0xFF) == 0xFF && (buf[pos + 1] & 0xF0) == 0xF0) {
        headerScratch.getData()[0] = buf[pos];
        headerScratch.getData()[1] = buf[pos + 1];
        data.setPosition(pos + 2);
        bytesRead = 2;
        return true;
      }
      pos++;
    }
    data.setPosition(limit);
    return false;
  }

  /**
   * Parses AV3A frame header from the 7-byte scratch buffer.
   * Populates frameSize, sampleRate, channelCount, frameDurationUs.
   *
   * AVS3 Audio (P3) frame header bit layout:
   *   syncword         12 bits  (0xFFF, already matched)
   *   audio_codec_id    4 bits
   *   anc_data_index    3 bits
   *   nn_type           3 bits
   *   coding_profile    2 bits
   *   sampling_freq_id  4 bits
   *   channel_number_index 7 bits
   *   resolution        2 bits
   *   frame_length     16 bits  (total frame bytes incl. header)
   *
   * Total bits consumed: 12+4+3+3+2+4+7+2+16 = 53 bits (< 56 bits = 7 bytes) ✓
   *
   * @return true if header is valid, false otherwise.
   */
  private boolean parseHeader(ParsableBitArray bits) {
    // Validate we have enough bits: 7 bytes = 56 bits, we need 53
    if (bits.bitsLeft() < 53) {
      return false;
    }

    bits.skipBits(12); // syncword (already validated in skipToNextSync)
    bits.skipBits(4);  // audio_codec_id
    bits.skipBits(3);  // anc_data_index
    bits.skipBits(3);  // nn_type
    bits.skipBits(2);  // coding_profile

    int samplingFreqId = bits.readBits(4);
    int channelNumberIndex = bits.readBits(7);
    bits.skipBits(2);  // resolution
    int frameLength = bits.readBits(16);

    if (frameLength <= 0) {
      return false;
    }

    // Map sampling_freq_id to sample rate
    if (samplingFreqId >= SAMPLE_RATE_TABLE.length || SAMPLE_RATE_TABLE[samplingFreqId] == 0) {
      return false;
    }
    sampleRate = SAMPLE_RATE_TABLE[samplingFreqId];

    // Map channel_number_index to channel count
    if (channelNumberIndex >= CHANNEL_COUNT_TABLE.length
        || CHANNEL_COUNT_TABLE[channelNumberIndex] == 0) {
      return false;
    }
    channelCount = CHANNEL_COUNT_TABLE[channelNumberIndex];

    // Dynamic frame duration based on actual sample rate
    frameDurationUs = (SAMPLES_PER_FRAME * 1_000_000L) / sampleRate;
    frameSize = frameLength;
    return true;
  }

  private void outputFormat() {
    if (output == null) {
      return;
    }
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AV3A)
            .setLanguage(language)
            .setSampleRate(sampleRate)
            .setChannelCount(channelCount)
            .build();
    output.format(format);
    hasOutputFormat = true;
  }
}
