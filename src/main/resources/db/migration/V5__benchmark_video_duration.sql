-- 视频时长（秒）；上游无则 NULL，不编造。
ALTER TABLE benchmark_video
  ADD COLUMN IF NOT EXISTS duration_sec BIGINT;

COMMENT ON COLUMN benchmark_video.duration_sec IS '视频时长（秒）；抖音 video.duration 毫秒归一，视频号 media.duration 多为秒';
