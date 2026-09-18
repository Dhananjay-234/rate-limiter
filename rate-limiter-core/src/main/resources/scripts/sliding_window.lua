--[[
  Sliding Window Counter rate limiter — atomic.

  Keeps one sorted set per key where each member is a request id and the score is the
  request timestamp in milliseconds. On every call we drop entries older than the window,
  count what is left, and only then admit.

  Unlike the token bucket this allows no burst at all: the limit is enforced across a
  continuously moving window, so traffic is smoothed rather than clumped. The cost is
  memory — one sorted set entry per request in the window, versus two hash fields total
  for the token bucket.

  KEYS[1] : window key, e.g. rate_limit:sw:{tenantId}:{route}
  ARGV[1] : limit            (max requests per window)
  ARGV[2] : window_millis    (window length)
  ARGV[3] : now_millis
  ARGV[4] : member_id        (unique per request — uuid; prevents score collisions)
  ARGV[5] : ttl_seconds

  Returns: { allowed(0|1), remaining, reset_epoch_seconds, retry_after_seconds }
]]

local key           = KEYS[1]
local limit         = tonumber(ARGV[1])
local window_millis = tonumber(ARGV[2])
local now_millis    = tonumber(ARGV[3])
local member_id     = ARGV[4]
local ttl_seconds   = tonumber(ARGV[5])

local window_start  = now_millis - window_millis

-- Evict everything that has slid out of the window.
redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

local used = redis.call('ZCARD', key)

if used < limit then
  redis.call('ZADD', key, now_millis, member_id)
  redis.call('EXPIRE', key, ttl_seconds)
  local reset_epoch = math.floor((now_millis + window_millis) / 1000)
  return { 1, limit - used - 1, reset_epoch, 0 }
end

-- At the limit. The next slot frees up when the oldest entry leaves the window.
local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
local oldest_score = now_millis
if oldest[2] ~= nil then
  oldest_score = tonumber(oldest[2])
end

local retry_after_millis = (oldest_score + window_millis) - now_millis
local retry_after = math.max(1, math.ceil(retry_after_millis / 1000))
local reset_epoch = math.floor((oldest_score + window_millis) / 1000)

redis.call('EXPIRE', key, ttl_seconds)

return { 0, 0, reset_epoch, retry_after }
