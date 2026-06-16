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
import com.google.android.exoplayer2.audio.Av3aUtil;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.extractor.ts.TsPayloadReader.TrackIdGenerator;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.ParsableBitArray;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.TimestampAdjuster;
import java.util.Collections;

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

  // AV3A sync word: 0xFFF (12 bits)
  private static final int SYNC_VALUE = 0xFFF;
  private static final int SYNC_VALUE_SIZE = 3; // bytes to check for sync
  private static final int HEADER_SIZE = 7; // minimum header bytes to parse format

  private static final int STATE_FINDING_SYNC = 0;
  private static final int STATE_READING_HEADER = 1;
  private static final int STATE_READING_SAMPLE = 2;

  @Nullable private final String language;

  private @Nullable TimestampAdjuster timestampAdjuster;
  private @Nullable TrackOutput output;

  // Parser state
  private int state;
  private int bytesRead;
  private long timeUs;

  // Frame info
  private int frameSize;
  private boolean hasOutputFormat;

  // Buffers
  private final ParsableByteArray headerScratch;
  private final ParsableBitArray headerBitArray;

  public Av3aReader(@Nullable String language) {
    this.language = language;
    headerScratch = new ParsableByteArray(new byte[HEADER_SIZE]);
    headerBitArray = new ParsableBitArray();
    state = STATE_FINDING_SYNC;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    state = STATE_FINDING_SYNC;
    bytesRead = 0;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if (pesTimeUs != C.TIME_UNSET) {
      timeUs = timestampAdjuster != null ? timestampAdjuster.adjustTsTimestamp(pesTimeUs) : pesTimeUs;
    }
  }

  @Override
  public void consume(ParsableByteArray data) {
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_FINDING_SYNC:
          if (skipToNextSync(data)) {
            state = STATE_READING_HEADER;
            bytesRead = 0;
          }
          break;
        case STATE_READING_HEADER:
          int bytesToRead = Math.min(data.bytesLeft(), HEADER_SIZE - bytesRead);
          data.readBytes(headerScratch.getData(), bytesRead, bytesToRead);
          bytesRead += bytesToRead;
          if (bytesRead == HEADER_SIZE) {
            headerScratch.setPosition(0);
            headerBitArray.reset(headerScratch.getData());
            frameSize = parseFrameSize(headerBitArray);
            if (frameSize <= 0) {
              // Invalid frame, restart sync search
              state = STATE_FINDING_SYNC;
              break;
            }
            if (!hasOutputFormat) {
              outputFormat();
            }
            // Write header bytes to output
            output.sampleData(new ParsableByteArray(headerScratch.getData(), HEADER_SIZE), HEADER_SIZE);
            bytesRead = HEADER_SIZE;
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
              output.sampleMetadata(
                  timeUs,
                  C.BUFFER_FLAG_KEY_FRAME,
                  frameSize,
                  0,
                  null);
              // Approximate next frame timestamp (assume 2048 samples @ detected sample rate,
              // will be corrected by next PES timestamp)
              timeUs += 46440; // ~2048/44100 * 1_000_000 us, conservative default
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

  @Override
  public void init(
      TimestampAdjuster timestampAdjuster,
      ExtractorOutput output,
      TrackIdGenerator idGenerator) {
    this.timestampAdjuster = timestampAdjuster;
    createTracks(output, idGenerator);
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Advances {@code data} until a potential AV3A sync word (0xFFF) is found.
   * Returns true if sync was found (data positioned just after sync word).
   */
  private boolean skipToNextSync(ParsableByteArray data) {
    byte[] buf = data.getData();
    int pos = data.getPosition();
    int limit = data.limit();
    while (pos < limit - 1) {
      if ((buf[pos] & 0xFF) == 0xFF && (buf[pos + 1] & 0xF0) == 0xF0) {
        // Copy sync bytes into header scratch so we don't lose them
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
   * Parses the frame size from the AV3A frame header bit stream.
   * AVS3 Audio frame header (simplified):
   *   syncword        12 bits  0xFFF
   *   audio_codec_id   4 bits
   *   anc_data_index   3 bits  (codec_id==2: LOSSLESS skip differently)
   *   nn_type          3 bits
   *   coding_profile   2 bits  (0=Base, 1=Object, 2=HOA)
   *   sampling_freq_id 4 bits
   *   ... (channel config etc.)
   *   frame_length    16 bits  (bytes, including header)
   *
   * This is a best-effort parser based on the AVS3 P3 spec and
   * nilaoda/Sourcecodeforplayer reference implementation.
   */
  private int parseFrameSize(ParsableBitArray bits) {
    try {
      // syncword already consumed (first 12 bits = 0xFFF)
      // But our headerScratch starts from byte 0, so skip 12 bits
      bits.skipBits(12); // syncword
      int audioCodecId = bits.readBits(4);
      bits.skipBits(3); // anc_data_index
      bits.skipBits(3); // nn_type
      bits.skipBits(2); // coding_profile
      bits.skipBits(4); // sampling_freq_id
      // Next 16 bits = frame_length in bytes (per AVS3 P3 spec)
      // But we only have 7 bytes = 56 bits total; bits consumed so far: 12+4+3+3+2+4 = 28
      // Remaining: 28 bits. We need 16 more for frame_length.
      // Skip channel_number_index (7 bits) + resolution (2 bits) = 9 bits → 37 bits consumed
      bits.skipBits(7); // channel_number_index
      bits.skipBits(2); // resolution
      // Now at bit 39; read frame_length (16 bits) → need bits 39-54, we have 56 total. OK.
      int frameLength = bits.readBits(16);
      return frameLength > 0 ? frameLength : -1;
    } catch (Exception e) {
      return -1;
    }
  }

  private void outputFormat() {
    if (output == null) {
      return;
    }
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AV3A)
            .setLanguage(language)
            .build();
    output.format(format);
    hasOutputFormat = true;
  }
}
