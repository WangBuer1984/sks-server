-- TOP20 明细扩展：描述/标签/发布时间 + 点赞/评论/分享/收藏。
-- fav_count 历史列语义固定为「收藏」(= collect_count)；like_count = 点赞(digg)。

ALTER TABLE benchmark_video
  ADD COLUMN IF NOT EXISTS description TEXT,
  ADD COLUMN IF NOT EXISTS tags TEXT,
  ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS like_count BIGINT,
  ADD COLUMN IF NOT EXISTS comment_count BIGINT,
  ADD COLUMN IF NOT EXISTS share_count BIGINT,
  ADD COLUMN IF NOT EXISTS collect_count BIGINT;

COMMENT ON COLUMN benchmark_video.fav_count IS '收藏数（历史列；与 collect_count 同义）';
COMMENT ON COLUMN benchmark_video.like_count IS '点赞数（抖音 digg / 视频号 like）';
COMMENT ON COLUMN benchmark_video.collect_count IS '收藏数（与 fav_count 对齐）';
COMMENT ON COLUMN benchmark_video.tags IS 'JSON 数组字符串，如 ["验收","避坑"]';
