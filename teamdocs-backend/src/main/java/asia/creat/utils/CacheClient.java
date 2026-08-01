package asia.creat.utils;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

@Component
@Slf4j
@RequiredArgsConstructor
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    /*
    * 用于设置对象
    * */
    public void set(String key, Object value, Duration duration){
        try {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), duration);
        } catch (Exception e) {
            log.warn("写入 Redis 缓存失败, key={}", key, e);
        }
    }

    /*
    * 用于设置空值
    * */
    public void setString(String key, String value, Duration duration){
        try {
            stringRedisTemplate.opsForValue().set(key, value, duration);
        } catch (Exception e) {
            log.warn("写入 Redis 缓存失败, key={}", key, e);
        }
    }

    /*
    * 用于读取缓存
    * */
    public String get(String key){
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读取 Redis 缓存失败, key={}", key, e);
            return null;
        }
    }

    /*
    * 用于删除键
    * */
    public void delete(String key){
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("删除 Redis 缓存失败, key={}", key, e);
        }
    }

    /*
    * 向 ZSet 添加元素并刷新 TTL
    * */
    public void addZSet(String key, String member, double score, Duration timeout){
        try {
            stringRedisTemplate.opsForZSet().add(key, member, score);

            stringRedisTemplate.expire(key, timeout);

        } catch (Exception e) {
            log.warn("添加 ZSet 失败, key: {}, member: {}", key, member, e);
        }
    }

    /*
    * 从 ZSet 中获取指定范围的元素及其分数（逆序）
    * ZREVRANGE key start stop WITHSCORES
    * */
    public Set<TypedTuple<String>> getZSetReverseRangeWithScores(String key, long start, long end){
        try {
            Set<TypedTuple<String>> res = stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
            return res != null ? res : Collections.emptySet();
        } catch (Exception e) {
            log.warn("获取 ZSet 逆序范围失败, key: {}, start: {}, end: {}", key, start, end, e);
            return Collections.emptySet();
        }
    }
    /*
    * 从 ZSet 中移除指定元素
    * */
    public void removeZSetMembers(String key, String... members) {
        if (!StringUtils.hasText(key) || members == null || members.length == 0) {
            return;
        }

        try {
            Long count = stringRedisTemplate.opsForZSet().remove(key, (Object[]) members);
            log.debug("从 ZSet 中移除元素成功, key: {}, 预期移除数: {}, 实际移除数: {}", key, members.length, count);
        } catch (Exception e) {
            log.warn("从 ZSet 中移除元素失败, key: {}, members: {}", key, Arrays.toString(members), e);
        }
    }

    /*
    * 按排名区间批量移除 ZSet 元素（对应 ZREMRANGEBYRANK key start stop）
    * */
    public Long removeZSetRangeByRank(String key, long start, long end) {
        try {
            Long removedCount = stringRedisTemplate.opsForZSet().removeRange(key, start, end);
            return removedCount != null ? removedCount : 0L;
        } catch (Exception e) {
            log.warn("Redis 批量删除失败, key: {}, start: {}, end: {}", key, start, end, e);
            return 0L;
        }
    }
}
