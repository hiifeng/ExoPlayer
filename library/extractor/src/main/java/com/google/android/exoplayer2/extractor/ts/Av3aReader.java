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
import com.google.android.exoplayer2.util.ParsableByteArray;

import java.util.Arrays;
import java.util.List;

/**
 * Parses a continuous AV3A (AVS3-P3 / Audio Vivid) byte stream and extracts individual frames.
 *
 * <p>Header bit layout (AVS3-P3 spec, coding_profile=0 channel-based path):
 * <pre>
 *   sync_word          12 bits  (0xFFF)
 *   audio_codec_id      4 bits  (must be 2 for AV3A)
 *   anc_data_index      1 bit   (must be 0)
 *   nn_type             3 bits
 *   coding_profile      3 bits  (0=channel, 1=object, 2=HOA)
 *   sampling_freq_id    4 bits
 *   crc                 8 bits  (first CRC)
 *   [channel / object / HOA fields — variable by coding_profile]
 *   resolution          2 bits
 *   bitrate_index       4 bits  (coding_profile != 1 only)
 *   crc                 8 bits  (second CRC)
 * </pre>
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3.
 */
@Deprecated
public final class Av3aReader implements ElementaryStreamReader {

  // ── 状态机 ──────────────────────────────────────────────────────────────────
  private static final int STATE_FINDING_HEADER = 0;
  private static final int STATE_READING_HEADER = 1;
  private static final int STATE_READING_FRAME  = 2;

  // 9 字节足够覆盖 coding_profile=0 时的完整 header（56 bit = 7 字节），
  // 留 2 字节余量应对 object/HOA profile 的可变长字段。
  private static final int HEADER_SIZE = 9;

  // AVS3-P3 每帧固定 1024 个采样点
  private static final int FRAME_SAMPLES = 1024;

  // sampling_freq_id (0..8) -> Hz
  // index: 0      1      2      3      4      5      6      7     8
  private static final int[] SAMPLING_FREQ_TABLE = {
    192000, 96000, 48000, 44100, 32000, 24000, 22050, 16000, 8000
  };

  // ── 字段：channel-based (coding_profile=0) ──────────────────────────────────
  // channel_number_index -> { channelCount, bitrateTable[] }
  // idx  0:MONO  1:STEREO  2:5.1  3:7.1  4:10.2  5:22.2  6:4.0
  //      7:5.1.2  8:5.1.4  9:7.1.2  10:7.1.4  11:HOA1  12:HOA2  13:HOA3
  private static final int[] CHANNEL_COUNT_BY_IDX = {
      1,  // 0: MONO
      2,  // 1: STEREO
      6,  // 2: 5.1
      8,  // 3: 7.1
     12,  // 4: 10.2
     24,  // 5: 22.2
      4,  // 6: 4.0
      8,  // 7: 5.1.2
     10,  // 8: 5.1.4
     10,  // 9: 7.1.2
     12,  // 10: 7.1.4
      4,  // 11: HOA order1  (1+1)^2 = 4
      9,  // 12: HOA order2  (2+1)^2 = 9
     16,  // 13: HOA order3  (3+1)^2 = 16
  };

  // bitrate tables per channel_number_index (bps)
  // 与 yaoxieyoulei 实现保持一致
  private static final int[][] BITRATE_TABLE_BY_IDX = {
    // 0: MONO
    {16000,32000,44000,56000,64000,72000,80000,96000,128000,144000,164000,192000},
    // 1: STEREO
    {24000,32000,48000,64000,80000,96000,128000,144000,192000,256000,320000},
    // 2: MC_5_1
    {192000,256000,320000,384000,448000,512000,640000,720000,144000,96000,128000,160000},
    // 3: MC_7_1
    {192000,480000,256000,384000,576000,640000,128000,160000},
    // 4: MC_10_2
    {},
    // 5: MC_22_2
    {},
    // 6: MC_4_0
    {48000,96000,128000,192000,256000},
    // 7: MC_5_1_2
    {152000,320000,480000,576000},
    // 8: MC_5_1_4
    {176000,384000,576000,704000,256000,448000},
    // 9: MC_7_1_2
    {216000,480000,576000,384000,768000},
    // 10: MC_7_1_4
    {240000,608000,384000,512000,832000},
    // 11: HOA_ORDER1
    {48000,96000,128000,192000,256000},
    // 12: HOA_ORDER2
    {192000,256000,320000,384000,480000,512000,640000},
    // 13: HOA_ORDER3
    {256000,320000,384000,512000,640000,896000},
  };

  // ── 实例字段 ─────────────────────────────────────────────────────────────────
  @Nullable private final String language;
  private final @C.RoleFlags int roleFlags;
  @Nullable private String formatId;
  @Nullable private TrackOutput output;

  private int  state          = STATE_FINDING_HEADER;
  private boolean lastByteWasFF = false;  // 跨包同步字检测
  private int  frameBytesRead = 0;
  private boolean hasOutputFormat = false;

  private long timeUs        = C.TIME_UNSET;
  private long frameDurationUs = 0L;
  private int  frameSize     = 0;         // 字节，含 header

  private final byte[] headerBuf = new byte[HEADER_SIZE];

  public Av3aReader(@Nullable String language, @C.RoleFlags int roleFlags) {
    this.language  = language;
    this.roleFlags = roleFlags;
  }

  // ── ElementaryStreamReader 接口 ──────────────────────────────────────────────

  @Override
  public void seek() {
    state         = STATE_FINDING_HEADER;
    frameBytesRead = 0;
    lastByteWasFF = false;
    timeUs        = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput,
      PesReader.TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) {
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_FINDING_HEADER:
          findHeader(data);
          break;
        case STATE_READING_HEADER:
          readHeaderRemainder(data);
          break;
        case STATE_READING_FRAME:
          readFrameRemainder(data);
          break;
        default:
          break;
      }
    }
  }

  @Override
  public void packetFinished() {
    // nothing
  }

  // ── 私有方法 ─────────────────────────────────────────────────────────────────

  /**
   * 在字节流中寻找同步字 0xFFF（12 bit）。
   * 使用 lastByteWasFF 跨包保留状态，避免同步字被拆在两个数据包之间时丢失。
   */
  private void findHeader(ParsableByteArray source) {
    byte[] buf   = source.getData();
    int    pos   = source.getPosition();
    int    limit = source.limit();

    for (int i = pos; i < limit; i++) {
      int b = buf[i] & 0xFF;
      if (lastByteWasFF && (b & 0xF0) == 0xF0) {
        // 找到同步字：buf[i-1]=0xFF, buf[i] 高4位=0xF
        headerBuf[0]  = (byte) 0xFF;
        headerBuf[1]  = buf[i];
        frameBytesRead = 2;
        lastByteWasFF = false;
        source.setPosition(i + 1);
        state = STATE_READING_HEADER;
        return;
      }
      lastByteWasFF = (b == 0xFF);
    }
    source.setPosition(limit);
  }

  /**
   * 继续读取 header 剩余字节（从 frameBytesRead=2 累积到 HEADER_SIZE=9）。
   * 凑够 9 字节后解析，失败则回到 STATE_FINDING_HEADER。
   */
  private void readHeaderRemainder(ParsableByteArray source) {
    int need = HEADER_SIZE - frameBytesRead;
    int avail = Math.min(source.bytesLeft(), need);
    source.readBytes(headerBuf, frameBytesRead, avail);
    frameBytesRead += avail;

    if (frameBytesRead < HEADER_SIZE) {
      return; // 还没凑够，等下一个包
    }

    // 解析
    ParsedHeader h = parseHeader(headerBuf);
    if (h == null) {
      // 无效帧头，丢弃已读的 2 字节同步字，回头重新搜索
      state         = STATE_FINDING_HEADER;
      frameBytesRead = 0;
      lastByteWasFF = false;
      return;
    }

    frameSize = h.frameSizeBytes;

    if (!hasOutputFormat) {
      frameDurationUs = (FRAME_SAMPLES * 1_000_000L) / h.sampleRate;
      output.format(new Format.Builder()
          .setId(formatId)
          .setSampleMimeType(MimeTypes.AUDIO_AV3A)
          .setMaxInputSize(4096 * 64)
          .setChannelCount(h.channelCount)
          .setSampleRate(h.sampleRate)
          .setAverageBitrate(h.totalBitrate)
          .setPeakBitrate(h.totalBitrate)
          .setLanguage(language)
          .setRoleFlags(roleFlags)
          .build());
      hasOutputFormat = true;
    }

    // 把已读的 header 字节输出给下游解码器（解码器需要完整帧，含 header）
    // 只有在 timeUs 有效时才写入，与 readFrameRemainder 的策略保持一致
    if (timeUs != C.TIME_UNSET) {
      output.sampleData(new ParsableByteArray(headerBuf, HEADER_SIZE), HEADER_SIZE);
    }
    state = STATE_READING_FRAME;
  }

  /**
   * 读取帧体剩余字节（header 之后的部分），凑满 frameSize 后提交 sampleMetadata。
   */
  private void readFrameRemainder(ParsableByteArray source) {
    int toRead = Math.min(source.bytesLeft(), frameSize - frameBytesRead);
    if (timeUs != C.TIME_UNSET) {
      output.sampleData(source, toRead);
    } else {
      // timeUs 未知时跳过帧体字节，不写入 sampleData，
      // 避免产生没有对应 sampleMetadata 的孤儿数据
      source.skipBytes(toRead);
    }
    frameBytesRead += toRead;

    if (frameBytesRead < frameSize) {
      return; // 帧还没读完
    }

    if (timeUs != C.TIME_UNSET) {
      output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null);
      timeUs += frameDurationUs;
    }

    state         = STATE_FINDING_HEADER;
    frameBytesRead = 0;
    lastByteWasFF = false;
  }

  // ── Header 解析 ──────────────────────────────────────────────────────────────

  /** 解析结果载体 */
  private static final class ParsedHeader {
    int sampleRate;
    int channelCount;
    int totalBitrate;
    int frameSizeBytes;
  }

  /**
   * 解析 9 字节 header，按 AVS3-P3 规范逐 bit 读取。
   *
   * <pre>
   * bit  0-11  sync_word (0xFFF) — 已在 findHeader 中验证
   * bit 12-15  audio_codec_id    — 必须为 2
   * bit    16  anc_data_index    — 必须为 0
   * bit 17-19  nn_type
   * bit 20-22  coding_profile    (0=channel, 1=object, 2=HOA)
   * bit 23-26  sampling_freq_id
   * bit 27-34  crc (第一个)
   * -- 以下随 coding_profile 变化 --
   * coding_profile=0:
   *   bit 35-41  channel_number_index (7 bits)
   *   bit 42-43  resolution
   *   bit 44-47  bitrate_index
   *   bit 48-55  crc (第二个)
   * coding_profile=1 (object):
   *   bit 35-36  sound_bed_type (2 bits)
   *   sound_bed_type=0:
   *     bit 37-43  object_channel_number (7 bits)
   *     bit 44-47  bitrate_index_per_channel
   *   sound_bed_type=1:
   *     bit 37-43  channel_number_index
   *     bit 44-47  bitrate_index
   *     bit 48-54  object_channel_number
   *     bit 55-58  bitrate_index_per_channel
   * coding_profile=2 (HOA):
   *   bit 35-38  hoa_order (4 bits, stored as order-1)
   *   bit 39-40  resolution
   *   bit 41-44  bitrate_index
   *   bit 45-52  crc (第二个)
   * </pre>
   *
   * @return ParsedHeader，或 null 表示帧头无效
   */
  @Nullable
  private static ParsedHeader parseHeader(byte[] buf) {
    BitReader r = new BitReader(buf);

    r.skip(12);                      // sync_word（已验证）
    int audioCodecId  = r.read(4);   // 必须为 2
    if (audioCodecId != 2) return null;

    int ancDataIndex  = r.read(1);   // 必须为 0（1=辅助数据帧，跳过）
    if (ancDataIndex != 0) return null;

    r.skip(3);                       // nn_type
    int codingProfile = r.read(3);   // 0/1/2
    int freqIdx       = r.read(4);   // sampling_freq_id
    r.skip(8);                       // crc1

    if (freqIdx >= SAMPLING_FREQ_TABLE.length) return null;
    int sampleRate = SAMPLING_FREQ_TABLE[freqIdx];
    if (sampleRate == 0) return null;

    int channelCount = 0;
    int totalBitrate = 0;

    if (codingProfile == 0) {
      // ── Channel-based ──
      int chIdx      = r.read(7);
      // resolution 和 bitrateIndex 在可变字段之后统一读取（见下方）

      if (chIdx >= CHANNEL_COUNT_BY_IDX.length) return null;
      channelCount = CHANNEL_COUNT_BY_IDX[chIdx];
      if (channelCount == 0) return null;

      r.skip(2);                     // resolution（所有 profile 统一在此读取）
      int bitrateIdx = r.read(4);
      int[] btable = BITRATE_TABLE_BY_IDX[chIdx];
      if (bitrateIdx >= btable.length) return null;
      totalBitrate = btable[bitrateIdx];

    } else if (codingProfile == 1) {
      // ── Object-based ──
      int soundBedType = r.read(2);
      if (soundBedType == 0) {
        int objCh           = r.read(7) + 1;   // object_channel_number
        int bitrateIdxPerCh = r.read(4);
        channelCount = objCh;
        if (bitrateIdxPerCh < BITRATE_TABLE_BY_IDX[0].length) {
          totalBitrate = objCh * BITRATE_TABLE_BY_IDX[0][bitrateIdxPerCh];
        }
      } else if (soundBedType == 1) {
        int chIdx           = r.read(7);
        int bitrateIdx      = r.read(4);
        int objCh           = r.read(7) + 1;
        int bitrateIdxPerCh = r.read(4);
        int bedCount = (chIdx < CHANNEL_COUNT_BY_IDX.length)
            ? CHANNEL_COUNT_BY_IDX[chIdx] : 0;
        channelCount = bedCount + objCh;
        // 总码率 = 床层总码率 + 对象声道数 × 每对象声道码率
        int bedBitrate = (chIdx < BITRATE_TABLE_BY_IDX.length
            && bitrateIdx < BITRATE_TABLE_BY_IDX[chIdx].length)
            ? BITRATE_TABLE_BY_IDX[chIdx][bitrateIdx] : 0;
        int objBitratePerCh = (bitrateIdxPerCh < BITRATE_TABLE_BY_IDX[0].length)
            ? BITRATE_TABLE_BY_IDX[0][bitrateIdxPerCh] : 0;
        totalBitrate = (int) Math.min(
            (long) bedBitrate + (long) objCh * objBitratePerCh,
            Integer.MAX_VALUE);
      } else {
        return null; // soundBedType 2/3 暂不支持
      }
      r.skip(2);                     // resolution（coding_profile=1 也需要读）
      // coding_profile=1 没有 bitrateIndex

    } else if (codingProfile == 2) {
      // ── HOA ──
      int hoaOrder   = r.read(4) + 1;  // 存储值为 order-1

      // HOA channel_number_index: order1=11, order2=12, order3=13
      int hoaChIdx = 10 + hoaOrder;     // order1->11, order2->12, order3->13
      channelCount = (hoaOrder + 1) * (hoaOrder + 1);

      r.skip(2);                        // resolution（所有 profile 统一在此读取）
      int bitrateIdx = r.read(4);
      if (hoaChIdx < BITRATE_TABLE_BY_IDX.length
          && bitrateIdx < BITRATE_TABLE_BY_IDX[hoaChIdx].length) {
        totalBitrate = BITRATE_TABLE_BY_IDX[hoaChIdx][bitrateIdx];
      }

    } else {
      return null; // 未知 coding_profile
    }

    if (channelCount <= 0 || totalBitrate <= 0) return null;

    // frameSize(字节) = totalBitrate / sampleRate * FRAME_SAMPLES / 8
    // 等价于 totalBitrate * FRAME_SAMPLES / (sampleRate * 8)
    int frameSizeBytes = (int) Math.ceil(
        (double) totalBitrate * FRAME_SAMPLES / (sampleRate * 8.0));
    if (frameSizeBytes <= HEADER_SIZE) return null;

    ParsedHeader h = new ParsedHeader();
    h.sampleRate    = sampleRate;
    h.channelCount  = channelCount;
    h.totalBitrate  = totalBitrate;
    h.frameSizeBytes = frameSizeBytes;
    return h;
  }

  // ── BitReader：纯字节数组的位读取工具（无 Java 9+ 依赖，兼容 API 19）────────

  private static final class BitReader {
    private final byte[] data;
    private int bitPos; // 当前已读 bit 数

    BitReader(byte[] data) {
      this.data   = data;
      this.bitPos = 0;
    }

    /** 读取 n 个 bit，返回无符号整数（n <= 24）。 */
    int read(int n) {
      int result = 0;
      for (int i = 0; i < n; i++) {
        int byteIdx  = bitPos / 8;
        int bitInByte = 7 - (bitPos % 8); // MSB first
        result = (result << 1) | ((data[byteIdx] >> bitInByte) & 1);
        bitPos++;
      }
      return result;
    }

    /** 跳过 n 个 bit。 */
    void skip(int n) {
      bitPos += n;
    }
  }
}
