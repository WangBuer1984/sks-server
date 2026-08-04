-- D4 Task 2：复盘 script 表补 4 指标列（like/comment/share/collect）。
-- track 自动抓真指标（TikHub 经 sks-ai GET /ai/analyze/video/metrics）后由 markMetrics 写入；
-- data_source='tikhub' 区分于旧的 'manual'（用户手填播放量，已废）。
ALTER TABLE script
  ADD COLUMN IF NOT EXISTS like_count    INT,
  ADD COLUMN IF NOT EXISTS comment_count INT,
  ADD COLUMN IF NOT EXISTS share_count   INT,
  ADD COLUMN IF NOT EXISTS collect_count INT;
