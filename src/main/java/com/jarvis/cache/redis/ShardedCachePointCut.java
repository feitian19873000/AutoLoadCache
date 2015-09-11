package com.jarvis.cache.redis;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.apache.log4j.Logger;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.ShardedJedis;
import redis.clients.jedis.ShardedJedisPool;

import com.jarvis.cache.AbstractCacheManager;
import com.jarvis.cache.AutoLoadHandler;
import com.jarvis.cache.CacheUtil;
import com.jarvis.cache.to.AutoLoadConfig;
import com.jarvis.cache.to.CacheWrapper;

/**
 * 缓存切面，用于拦截数据并调用Redis进行缓存
 * @author jiayu.qiu
 */
public class ShardedCachePointCut extends AbstractCacheManager<Serializable> {

    private static final Logger logger=Logger.getLogger(ShardedCachePointCut.class);

    private static final StringRedisSerializer keySerializer=new StringRedisSerializer();

    private static final JdkSerializationRedisSerializer valueSerializer=new JdkSerializationRedisSerializer();

    private ShardedJedisPool shardedJedisPool;

    private String namespace;

    public ShardedCachePointCut(AutoLoadConfig config) {
        super(config);
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace=namespace;
    }

    private void returnResource(ShardedJedis shardedJedis) {
        shardedJedis.close();
    }

    private String appendNamespace(String cacheKey) {
        if(null != namespace && namespace.length() > 0) {
            return namespace + ":" + cacheKey;
        }
        return cacheKey;
    }

    @Override
    public void setCache(String cacheKey, final CacheWrapper<Serializable> result, final int expire) {
        if(null == shardedJedisPool || null == cacheKey) {
            return;
        }
        if(cacheKey.indexOf("*") != -1 || cacheKey.indexOf("?") != -1) {
            throw new java.lang.RuntimeException("cacheKey:" + cacheKey + "; has '*' or '?'");
        }
        cacheKey=appendNamespace(cacheKey);
        ShardedJedis shardedJedis=null;
        try {
            result.setLastLoadTime(System.currentTimeMillis());
            shardedJedis=shardedJedisPool.getResource();
            Jedis jedis=shardedJedis.getShard(cacheKey);
            jedis.setex(keySerializer.serialize(cacheKey), expire, valueSerializer.serialize(result));
        } catch(Exception ex) {
            logger.error(ex.getMessage(), ex);
        } finally {
            returnResource(shardedJedis);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public CacheWrapper<Serializable> get(String cacheKey) {
        if(null == shardedJedisPool || null == cacheKey) {
            return null;
        }
        CacheWrapper<Serializable> res=null;
        ShardedJedis shardedJedis=null;
        try {
            cacheKey=appendNamespace(cacheKey);
            shardedJedis=shardedJedisPool.getResource();
            Jedis jedis=shardedJedis.getShard(cacheKey);
            byte bytes[]=jedis.get(keySerializer.serialize(cacheKey));
            res=(CacheWrapper<Serializable>)valueSerializer.deserialize(bytes);
        } catch(Exception ex) {
            logger.error(ex.getMessage(), ex);
        } finally {
            returnResource(shardedJedis);
        }
        return res;
    }

    /**
     * 根据默认缓存Key删除缓存
     * @param cs Class
     * @param method
     * @param arguments
     * @param subKeySpEL
     * @param deleteByPrefixKey 是否批量删除
     */
    public void deleteByDefaultCacheKey(@SuppressWarnings("rawtypes") Class cs, String method, Object[] arguments,
        String subKeySpEL, boolean deleteByPrefixKey) {
        try {
            String cacheKey;
            if(deleteByPrefixKey) {
                cacheKey=CacheUtil.getDefaultCacheKeyPrefix(cs.getName(), method, arguments, subKeySpEL) + "*";
            } else {
                cacheKey=CacheUtil.getDefaultCacheKey(cs.getName(), method, arguments, subKeySpEL);
            }
            delete(cacheKey);
        } catch(Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    /**
     * 通过Spring EL 表达式，删除缓存
     * @param keySpEL Spring EL表达式
     * @param arguments 参数
     */
    public void deleteDefinedCacheKey(String keySpEL, Object[] arguments) {
        String cacheKey=CacheUtil.getDefinedCacheKey(keySpEL, arguments);
        this.delete(cacheKey);
    }

    /**
     * 根据缓存Key删除缓存
     * @param cacheKey 如果传进来的值中 带有 * 或 ? 号，则会使用批量删除（遍历所有Redis服务器）
     */
    @Override
    public void delete(String cacheKey) {
        if(null == shardedJedisPool || null == cacheKey) {
            return;
        }
        cacheKey=appendNamespace(cacheKey);
        final AutoLoadHandler<Serializable> autoLoadHandler=this.getAutoLoadHandler();
        ShardedJedis shardedJedis=null;
        if(cacheKey.indexOf("*") != -1 || cacheKey.indexOf("?") != -1) {// 如果是批量删除缓存，则要遍历所有redis，避免遗漏。
            try {
                shardedJedis=shardedJedisPool.getResource();
                Collection<Jedis> list=shardedJedis.getAllShards();
                StringBuilder script=new StringBuilder();
                script.append("local keys = redis.call('keys', KEYS[1]);\n");
                script.append("if(not keys or #keys == 0) then \n return nil; \n end \n");
                script.append("redis.call('del', unpack(keys)); \n return keys;");
                for(Jedis jedis: list) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<String> keys=(List<String>)jedis.eval(script.toString(), 1, cacheKey);
                        if(null != keys && keys.size() > 0) {
                            for(String tmpKey: keys) {
                                autoLoadHandler.resetAutoLoadLastLoadTime(tmpKey);
                            }
                        }
                    } catch(Exception ex) {
                        logger.error(ex.getMessage(), ex);
                    }
                }
            } catch(Exception ex) {
                logger.error(ex.getMessage(), ex);
            } finally {
                returnResource(shardedJedis);
            }
        } else {
            try {
                shardedJedis=shardedJedisPool.getResource();
                Jedis jedis=shardedJedis.getShard(cacheKey);
                jedis.del(keySerializer.serialize(cacheKey));
                autoLoadHandler.resetAutoLoadLastLoadTime(cacheKey);
            } catch(Exception ex) {
                logger.error(ex.getMessage(), ex);
            } finally {
                returnResource(shardedJedis);
            }
        }
    }

    public ShardedJedisPool getShardedJedisPool() {
        return shardedJedisPool;
    }

    public void setShardedJedisPool(ShardedJedisPool shardedJedisPool) {
        this.shardedJedisPool=shardedJedisPool;
    }

}
