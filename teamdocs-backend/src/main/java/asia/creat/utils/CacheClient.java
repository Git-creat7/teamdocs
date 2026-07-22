package asia.creat.utils;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /*
    * 用于设置对象
    * */
    public void set(String key, Object value, Duration duration){
        try {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), duration);
        } catch (Exception e) {
            log.warn("写入 Redis 缓存失败,key={}",key,e);
        }
    }

    /*
    * 用于设置空值
    * */
    public void setString(String key, String value, Duration duration){
        try {
            stringRedisTemplate.opsForValue().set(key, value, duration);
        } catch (Exception e) {
            log.warn("写入 Redis 缓存失败,key={}",key,e);
        }
    }

    /*
    * 用于读取缓存
    * */
    public String get(String key){
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("读取 Redis 缓存失败,key={}",key,e);
            return null;
        }
    }

    /*
    * 用于删除键
    * */
    public void delete(String key){
        try{
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("删除 Redis 缓存失败,key={}",key,e);
        }
    }
}
