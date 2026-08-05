-- D4 follow-up：存量视频号 play_count=0（不可用被写成 0）→ NULL，与新 null 信号一致。
-- publish_url 含 weixin.qq.com（含 channels.weixin.qq.com / sph 分享链）；抖音真 0 不动。
UPDATE script SET play_count = NULL
WHERE play_count = 0 AND publish_url LIKE '%weixin.qq.com%';
