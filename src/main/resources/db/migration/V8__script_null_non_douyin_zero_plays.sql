-- D4 follow-up 补丁：V7 只清了 weixin.qq.com 的 0-play；本迁移补清其余非抖音 0-play
-- （空 publish_url + 未知平台域），避免「空 url 存量仍显示 0」。
-- 抖音真 0（publish_url 含 douyin/iesdouyin）不动。V7 已清的 weixin 行 play_count=NULL 不再匹配。
UPDATE script SET play_count = NULL
WHERE play_count = 0
  AND COALESCE(publish_url, '') NOT LIKE '%douyin%'
  AND COALESCE(publish_url, '') NOT LIKE '%iesdouyin%';
