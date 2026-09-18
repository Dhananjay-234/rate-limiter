--[[
  Token Bucket rate limiter — atomic.

  WHY LUA:
  A naive implementation does GET -> compute -> SET from the application. Between the
  GET and the SET another thread (or another gateway instance) can read the same token
  count, so two callers both see "1 token left" and both proceed. That is a lost update,
  and under load it admits far more traffic than the configured quota.

  Redis executes a Lua script atomically: no other command runs while it executes, so the
  read-modify-write below cannot interleave. This is the single most important design
  decision in this project.

  KEYS[1] : bucket key, e.g. rate_limit:tb:{tenantId}:{route}
  ARGV[1] : capacity        (max tokens the bucket holds)
  ARGV[2] : refill_rate     (tokens added per second)
  ARGV[3] : now_millis      (caller-supplied clock, keeps the script deterministic)
  ARGV[4] : ttl_seconds     (idle bucket eviction)

  Returns: { allowed(0|1), remaining, reset_epoch_seconds, retry_after_seconds }
]]

local key          = KEYS[1]
local capacity     = tonumber(ARGV[1])
local refill_rate  = tonumber(ARGV[2])
local now_millis   = tonumber(ARGV[3])
local ttl_seconds  = tonumber(ARGV[4])

local state        = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens       = tonumber(state[1])
local last_refill  = tonumber(state[2])

-- First ever request for this key: start with a full bucket.
if tokens == nil then
  tokens = capacity
  last_refill = now_millis
end

-- Lazy refill. We do not run a background timer; we compute how many tokens
-- *would* have been added since the last request and cap at capacity.
local elapsed_seconds = math.max(0, (now_millis - last_refill) / 1000.0)
tokens = math.min(capacity, tokens + (elapsed_seconds * refill_rate))

local allowed = 0
local retry_after = 0

if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  -- Seconds until at least one whole token is available again.
  retry_after = math.ceil((1 - tokens) / refill_rate)
end

redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now_millis)
redis.call('EXPIRE', key, ttl_seconds)

-- Reset = when the bucket would be completely full again.
local seconds_to_full = 0
if tokens < capacity then
  seconds_to_full = math.ceil((capacity - tokens) / refill_rate)
end
local reset_epoch = math.floor(now_millis / 1000) + seconds_to_full

return { allowed, math.floor(tokens), reset_epoch, retry_after }
