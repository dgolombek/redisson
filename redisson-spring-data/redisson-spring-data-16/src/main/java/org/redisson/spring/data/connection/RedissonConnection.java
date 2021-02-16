/**
 * Copyright (c) 2013-2020 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.spring.data.connection;

import org.redisson.Redisson;
import org.redisson.SlotCallback;
import org.redisson.api.BatchOptions;
import org.redisson.api.BatchOptions.ExecutionMode;
import org.redisson.api.BatchResult;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.*;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.RedisStrictCommand;
import org.redisson.client.protocol.convertor.BooleanReplayConvertor;
import org.redisson.client.protocol.convertor.DoubleReplayConvertor;
import org.redisson.client.protocol.convertor.VoidReplayConvertor;
import org.redisson.client.protocol.decoder.*;
import org.redisson.command.CommandAsyncService;
import org.redisson.command.CommandBatchService;
import org.redisson.connection.MasterSlaveEntry;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.types.RedisClientInfo;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.redisson.client.protocol.RedisCommands.LRANGE;

/**
 * Redisson connection
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonConnection extends AbstractRedisConnection {

    private boolean closed;
    protected final Redisson redisson;
    
    CommandAsyncService executorService;
    private RedissonSubscription subscription;
    
    public RedissonConnection(RedissonClient redisson) {
        super();
        this.redisson = (Redisson) redisson;
        executorService = (CommandAsyncService) this.redisson.getCommandExecutor();
    }

    @Override
    public void close() throws DataAccessException {
        super.close();

        if (isQueueing()) {
            CommandBatchService es = (CommandBatchService) executorService;
            if (!es.isExecuted()) {
                discard();
            }
        }
        closed = true;
    }
    
    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public Object getNativeConnection() {
        return redisson;
    }

    @Override
    public boolean isQueueing() {
        if (executorService instanceof CommandBatchService) {
            CommandBatchService es = (CommandBatchService) executorService;
            return es.getOptions().getExecutionMode() == ExecutionMode.REDIS_WRITE_ATOMIC;
        }
        return false;
    }

    @Override
    public boolean isPipelined() {
        if (executorService instanceof CommandBatchService) {
            CommandBatchService es = (CommandBatchService) executorService;
            return es.getOptions().getExecutionMode() == ExecutionMode.IN_MEMORY || es.getOptions().getExecutionMode() == ExecutionMode.IN_MEMORY_ATOMIC;
        }
        return false;
    }
    
    public boolean isPipelinedAtomic() {
        if (executorService instanceof CommandBatchService) {
            CommandBatchService es = (CommandBatchService) executorService;
            return es.getOptions().getExecutionMode() == ExecutionMode.IN_MEMORY_ATOMIC;
        }
        return false;
    }

    @Override
    public void openPipeline() {
        BatchOptions options = BatchOptions.defaults()
                .executionMode(ExecutionMode.IN_MEMORY);
        this.executorService = new CommandBatchService(redisson.getConnectionManager(), options);
    }

    @Override
    public List<Object> closePipeline() throws RedisPipelineException {
        if (isPipelined()) {
            CommandBatchService es = (CommandBatchService) executorService;
            try {
                BatchResult<?> result = es.execute();
                filterResults(result);
                if (isPipelinedAtomic()) {
                    return Arrays.<Object>asList((List<Object>) result.getResponses());
                }
                return (List<Object>) result.getResponses();
            } catch (Exception ex) {
                throw new RedisPipelineException(ex);
            } finally {
                resetConnection();
            }
        }
        return Collections.emptyList();
    }

    @Override
    public Object execute(String command, byte[]... args) {
        for (Method method : this.getClass().getDeclaredMethods()) {
            if (method.getName().equalsIgnoreCase(command) && Modifier.isPublic(method.getModifiers())) {
                try {
                    Object t = execute(method, args);
                    if (t instanceof String) {
                        return ((String) t).getBytes();
                    }
                    return t;
                } catch (IllegalArgumentException e) {
                    if (isPipelined()) {
                        throw new RedisPipelineException(e);
                    }

                    throw new InvalidDataAccessApiUsageException(e.getMessage(), e);
                }
            }
        }
        throw new UnsupportedOperationException();
    }

    private Object execute(Method method, byte[]... args) {
        if (method.getParameterTypes().length > 0 && method.getParameterTypes()[0] == byte[][].class) {
            return ReflectionUtils.invokeMethod(method, this, args);
        }
        if (args == null) {
            return ReflectionUtils.invokeMethod(method, this);
        }
        return ReflectionUtils.invokeMethod(method, this, Arrays.asList(args).toArray());
    }
    
    <V> V syncFuture(RFuture<V> future) {
        try {
            return executorService.get(future);
        } catch (Exception ex) {
            throw transform(ex);
        }
    }

    protected RuntimeException transform(Exception ex) {
        DataAccessException exception = RedissonConnectionFactory.EXCEPTION_TRANSLATION.translate(ex);
        if (exception != null) {
            return exception;
        }
        return new RedisSystemException(ex.getMessage(), ex);
    }
    
    @Override
    public Boolean exists(byte[] key) {
        return read(key, StringCodec.INSTANCE, RedisCommands.EXISTS, key);
    }
    
    @Override
    public Long del(byte[]... keys) {
        return write(keys[0], LongCodec.INSTANCE, RedisCommands.DEL, Arrays.asList(keys).toArray());
    }
    
    private static final RedisStrictCommand<DataType> TYPE = new RedisStrictCommand<DataType>("TYPE", new DataTypeConvertor());

    @Override
    public DataType type(byte[] key) {
        return read(key, StringCodec.INSTANCE, TYPE, key);
    }

    private static final RedisStrictCommand<Set<byte[]>> KEYS = new RedisStrictCommand<Set<byte[]>>("KEYS", new SetReplayDecoder<byte[]>(ByteArrayCodec.INSTANCE.getValueDecoder()));
    
    @Override
    public Set<byte[]> keys(byte[] pattern) {
        if (isQueueing()) {
            return read(null, ByteArrayCodec.INSTANCE, KEYS, pattern);
        }

        Set<byte[]> results = new HashSet<byte[]>();
        RFuture<Set<byte[]>> f = (RFuture<Set<byte[]>>)(Object)(executorService.readAllAsync(results, ByteArrayCodec.INSTANCE, KEYS, pattern));
        return sync(f);
    }

    @Override
    public Cursor<byte[]> scan(ScanOptions options) {
        return new ScanCursor<byte[]>(0, options) {

            private RedisClient client;
            private Iterator<MasterSlaveEntry> entries = redisson.getConnectionManager().getEntrySet().iterator();
            private MasterSlaveEntry entry = entries.next();
            
            @Override
            protected ScanIteration<byte[]> doScan(long cursorId, ScanOptions options) {
                if (isQueueing() || isPipelined()) {
                    throw new UnsupportedOperationException("'SSCAN' cannot be called in pipeline / transaction mode.");
                }

                if (entry == null) {
                    return null;
                }
                
                List<Object> args = new ArrayList<Object>();
                // to avoid negative value
                cursorId = Math.max(cursorId, 0);
                args.add(cursorId);
                if (options.getPattern() != null) {
                    args.add("MATCH");
                    args.add(options.getPattern());
                }
                if (options.getCount() != null) {
                    args.add("COUNT");
                    args.add(options.getCount());
                }
                
                RFuture<ListScanResult<byte[]>> f = executorService.readAsync(client, entry, ByteArrayCodec.INSTANCE, RedisCommands.SCAN, args.toArray());
                ListScanResult<byte[]> res = syncFuture(f);
                long pos = res.getPos();
                client = res.getRedisClient();
                if (pos == 0) {
                    if (entries.hasNext()) {
                        pos = -1;
                        entry = entries.next();
                    } else {
                        entry = null;
                    }
                }
                
                return new ScanIteration<byte[]>(pos, res.getValues());
            }
        }.open();
    }

    @Override
    public byte[] randomKey() {
        if (isQueueing()) {
            return read(null, ByteArrayCodec.INSTANCE, RedisCommands.RANDOM_KEY);
        }
        
        RFuture<byte[]> f = executorService.readRandomAsync(ByteArrayCodec.INSTANCE, RedisCommands.RANDOM_KEY);
        return sync(f);
    }

    @Override
    public void rename(byte[] oldName, byte[] newName) {
        write(oldName, StringCodec.INSTANCE, RedisCommands.RENAME, oldName, newName);
    }

    @Override
    public Boolean renameNX(byte[] oldName, byte[] newName) {
        return write(oldName, StringCodec.INSTANCE, RedisCommands.RENAMENX, oldName, newName);
    }

    private static final RedisStrictCommand<Boolean> EXPIRE = new RedisStrictCommand<Boolean>("EXPIRE", new BooleanReplayConvertor());
    
    @Override
    public Boolean expire(byte[] key, long seconds) {
        return write(key, StringCodec.INSTANCE, EXPIRE, key, seconds);
    }

    @Override
    public Boolean pExpire(byte[] key, long millis) {
        return write(key, StringCodec.INSTANCE, RedisCommands.PEXPIRE, key, millis);
    }

    private static final RedisStrictCommand<Boolean> EXPIREAT = new RedisStrictCommand<Boolean>("EXPIREAT", new BooleanReplayConvertor());
    
    @Override
    public Boolean expireAt(byte[] key, long unixTime) {
        return write(key, StringCodec.INSTANCE, EXPIREAT, key, unixTime);
    }

    @Override
    public Boolean pExpireAt(byte[] key, long unixTimeInMillis) {
        return write(key, StringCodec.INSTANCE, RedisCommands.PEXPIREAT, key, unixTimeInMillis);
    }

    @Override
    public Boolean persist(byte[] key) {
        return write(key, StringCodec.INSTANCE, RedisCommands.PERSIST, key);
    }

    @Override
    public Boolean move(byte[] key, int dbIndex) {
        return write(key, StringCodec.INSTANCE, RedisCommands.MOVE, key, dbIndex);
    }

    private static final RedisStrictCommand<Long> TTL = new RedisStrictCommand<Long>("TTL");
    
    @Override
    public Long ttl(byte[] key) {
        return read(key, StringCodec.INSTANCE, TTL, key);
    }

    protected <T> T sync(RFuture<T> f) {
        if (isPipelined()) {
            return null;
        }
        if (isQueueing()) {
            f.syncUninterruptibly();
            return null;
        }

        return syncFuture(f);
    }

    @Override
    public Long pTtl(byte[] key) {
        return read(key, StringCodec.INSTANCE, RedisCommands.PTTL, key);
    }

    @Override
    public List<byte[]> sort(byte[] key, SortParameters sortParams) {
        List<Object> params = new ArrayList<Object>();
        params.add(key);
        if (sortParams != null) {
            if (sortParams.getByPattern() != null) {
                params.add("BY");
                params.add(sortParams.getByPattern());
            }
            
            if (sortParams.getLimit() != null) {
                params.add("LIMIT");
                if (sortParams.getLimit().getStart() != -1) {
                    params.add(sortParams.getLimit().getStart());
                }
                if (sortParams.getLimit().getCount() != -1) {
                    params.add(sortParams.getLimit().getCount());
                }
            }
            
            if (sortParams.getGetPattern() != null) {
                for (byte[] pattern : sortParams.getGetPattern()) {
                    params.add("GET");
                    params.add(pattern);
                }
            }
            
            if (sortParams.getOrder() != null) {
                params.add(sortParams.getOrder());
            }
            
            Boolean isAlpha = sortParams.isAlphabetic();
            if (isAlpha != null && isAlpha) {
                params.add("ALPHA");
            }
        }
        
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.SORT_LIST, params.toArray());
    }
    
    private static final RedisCommand<Long> SORT_TO = new RedisCommand<Long>("SORT");

    @Override
    public Long sort(byte[] key, SortParameters sortParams, byte[] storeKey) {
        List<Object> params = new ArrayList<Object>();
        params.add(key);
        if (sortParams != null) {
            if (sortParams.getByPattern() != null) {
                params.add("BY");
                params.add(sortParams.getByPattern());
            }
            
            if (sortParams.getLimit() != null) {
                params.add("LIMIT");
                if (sortParams.getLimit().getStart() != -1) {
                    params.add(sortParams.getLimit().getStart());
                }
                if (sortParams.getLimit().getCount() != -1) {
                    params.add(sortParams.getLimit().getCount());
                }
            }
            
            if (sortParams.getGetPattern() != null) {
                for (byte[] pattern : sortParams.getGetPattern()) {
                    params.add("GET");
                    params.add(pattern);
                }
            }
            
            if (sortParams.getOrder() != null) {
                params.add(sortParams.getOrder());
            }
            
            Boolean isAlpha = sortParams.isAlphabetic();
            if (isAlpha != null && isAlpha) {
                params.add("ALPHA");
            }
        }
        
        params.add("STORE");
        params.add(storeKey);
        
        return read(key, ByteArrayCodec.INSTANCE, SORT_TO, params.toArray());
    }

    @Override
    public byte[] dump(byte[] key) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.DUMP, key);
    }

    @Override
    public void restore(byte[] key, long ttlInMillis, byte[] serializedValue) {
        write(key, StringCodec.INSTANCE, RedisCommands.RESTORE, key, ttlInMillis, serializedValue);
    }

    @Override
    public byte[] get(byte[] key) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.GET, key);
    }

    @Override
    public byte[] getSet(byte[] key, byte[] value) {
        return write(key, ByteArrayCodec.INSTANCE, RedisCommands.GETSET, key, value);
    }

    private static final RedisCommand<List<Object>> MGET = new RedisCommand<List<Object>>("MGET", new ObjectListReplayDecoder<Object>());
    
    @Override
    public List<byte[]> mGet(byte[]... keys) {
        return read(keys[0], ByteArrayCodec.INSTANCE, MGET, Arrays.asList(keys).toArray());
    }

    @Override
    public void set(byte[] key, byte[] value) {
        write(key, StringCodec.INSTANCE, RedisCommands.SET, key, value);
    }

    @Override
    public Boolean setNX(byte[] key, byte[] value) {
        return write(key, StringCodec.INSTANCE, RedisCommands.SETNX, key, value);
    }

    RedisCommand<Void> SETEX = new RedisCommand<Void>("SETEX", new VoidReplayConvertor());
    
    @Override
    public void setEx(byte[] key, long seconds, byte[] value) {
        write(key, StringCodec.INSTANCE, SETEX, key, seconds, value);
    }

    @Override
    public void pSetEx(byte[] key, long milliseconds, byte[] value) {
        write(key, StringCodec.INSTANCE, RedisCommands.PSETEX, key, milliseconds, value);
    }

    @Override
    public void mSet(Map<byte[], byte[]> tuple) {
        List<byte[]> params = convert(tuple);
        write(tuple.keySet().iterator().next(), StringCodec.INSTANCE, RedisCommands.MSET, params.toArray());
    }

    protected List<byte[]> convert(Map<byte[], byte[]> tuple) {
        List<byte[]> params = new ArrayList<byte[]>(tuple.size()*2);
        for (Entry<byte[], byte[]> entry : tuple.entrySet()) {
            params.add(entry.getKey());
            params.add(entry.getValue());
        }
        return params;
    }

    @Override
    public Boolean mSetNX(Map<byte[], byte[]> tuple) {
        List<byte[]> params = convert(tuple);
        return write(tuple.keySet().iterator().next(), StringCodec.INSTANCE, RedisCommands.MSETNX, params.toArray());
    }

    @Override
    public Long incr(byte[] key) {
        return write(key, StringCodec.INSTANCE, RedisCommands.INCR, key);
    }

    @Override
    public Long incrBy(byte[] key, long value) {
        return write(key, StringCodec.INSTANCE, RedisCommands.INCRBY, key, value);
    }

    @Override
    public Double incrBy(byte[] key, double value) {
        return write(key, StringCodec.INSTANCE, RedisCommands.INCRBYFLOAT, key, BigDecimal.valueOf(value).toPlainString());
    }

    @Override
    public Long decr(byte[] key) {
        return write(key, StringCodec.INSTANCE, RedisCommands.DECR, key);
    }

    private static final RedisStrictCommand<Long> DECRBY = new RedisStrictCommand<Long>("DECRBY");
    
    @Override
    public Long decrBy(byte[] key, long value) {
        return write(key, StringCodec.INSTANCE, DECRBY, key, value);
    }

    private static final RedisStrictCommand<Long> APPEND = new RedisStrictCommand<Long>("APPEND");
    
    @Override
    public Long append(byte[] key, byte[] value) {
        return write(key, StringCodec.INSTANCE, APPEND, key, value);
    }
    
    private static final RedisCommand<Object> GETRANGE = new RedisCommand<Object>("GETRANGE");

    @Override
    public byte[] getRange(byte[] key, long begin, long end) {
        return read(key, ByteArrayCodec.INSTANCE, GETRANGE, key, begin, end);
    }

    private static final RedisCommand<Void> SETRANGE = new RedisCommand<Void>("SETRANGE", new VoidReplayConvertor());

    @Override
    public void setRange(byte[] key, byte[] value, long offset) {
        write(key, ByteArrayCodec.INSTANCE, SETRANGE, key, offset, value);
    }

    @Override
    public Boolean getBit(byte[] key, long offset) {
        return read(key, StringCodec.INSTANCE, RedisCommands.GETBIT, key, offset);
    }

    @Override
    public Boolean setBit(byte[] key, long offset, boolean value) {
        return write(key, StringCodec.INSTANCE, RedisCommands.SETBIT, key, offset, value ? 1 : 0);
    }

    @Override
    public Long bitCount(byte[] key) {
        return read(key, StringCodec.INSTANCE, RedisCommands.BITCOUNT, key);
    }

    @Override
    public Long bitCount(byte[] key, long begin, long end) {
        return read(key, StringCodec.INSTANCE, RedisCommands.BITCOUNT, key, begin, end);
    }

    private static final RedisStrictCommand<Long> BITOP = new RedisStrictCommand<Long>("BITOP");
    
    @Override
    public Long bitOp(BitOperation op, byte[] destination, byte[]... keys) {
        if (op == BitOperation.NOT && keys.length > 1) {
            throw new UnsupportedOperationException("NOT operation doesn't support more than single source key");
        }

        List<Object> params = new ArrayList<Object>(keys.length + 2);
        params.add(op);
        params.add(destination);
        params.addAll(Arrays.asList(keys));
        return write(keys[0], StringCodec.INSTANCE, BITOP, params.toArray());
    }

    @Override
    public Long strLen(byte[] key) {
        return read(key, StringCodec.INSTANCE, RedisCommands.STRLEN, key);
    }

    private static final RedisStrictCommand<Long> RPUSH = new RedisStrictCommand<Long>("RPUSH");
    
    @Override
    public Long rPush(byte[] key, byte[]... values) {
        List<Object> args = new ArrayList<Object>(values.length + 1);
        args.add(key);
        args.addAll(Arrays.asList(values));
        return write(key, StringCodec.INSTANCE, RPUSH, args.toArray());
    }

    private static final RedisStrictCommand<Integer> LPUSH = new RedisStrictCommand<Integer>("LPUSH");
    
    @Override
    public Long lPush(byte[] key, byte[]... values) {
        List<Object> args = new ArrayList<Object>(values.length + 1);
        args.add(key);
        args.addAll(Arrays.asList(values));
        return write(key, StringCodec.INSTANCE, LPUSH, args.toArray());
    }

    private static final RedisStrictCommand<Integer> RPUSHX = new RedisStrictCommand<Integer>("RPUSHX");
    
    @Override
    public Long rPushX(byte[] key, byte[] value) {
        return write(key, StringCodec.INSTANCE, RPUSHX, key, value);
    }

    private static final RedisStrictCommand<Integer> LPUSHX = new RedisStrictCommand<Integer>("LPUSHX");
    
    @Override
    public Long lPushX(byte[] key, byte[] value) {
        return write(key, StringCodec.INSTANCE, LPUSHX, key, value);
    }

    private static final RedisStrictCommand<Long> LLEN = new RedisStrictCommand<Long>("LLEN");
    
    @Override
    public Long lLen(byte[] key) {
        return read(key, StringCodec.INSTANCE, LLEN, key);
    }

    @Override
    public List<byte[]> lRange(byte[] key, long start, long end) {
        return read(key, ByteArrayCodec.INSTANCE, LRANGE, key, start, end);
    }

    @Override
    public void lTrim(byte[] key, long start, long end) {
        write(key, StringCodec.INSTANCE, RedisCommands.LTRIM, key, start, end);
    }

    @Override
    public byte[] lIndex(byte[] key, long index) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.LINDEX, key, index);
    }

    private static final RedisStrictCommand<Long> LINSERT = new RedisStrictCommand<Long>("LINSERT");
    
    @Override
    public Long lInsert(byte[] key, Position where, byte[] pivot, byte[] value) {
        return write(key, StringCodec.INSTANCE, LINSERT, key, where, pivot, value);
    }

    private final List<String> commandsToRemove = Arrays.asList("SET", 
            "RESTORE", "LTRIM", "SETEX", "SETRANGE", "FLUSHDB", "LSET", "MSET", "HMSET", "RENAME");
    private final List<Integer> indexToRemove = new ArrayList<Integer>();
    private int index = -1;
    
    <T> T write(byte[] key, Codec codec, RedisCommand<?> command, Object... params) {
        RFuture<T> f = executorService.writeAsync(key, codec, command, params);
        indexCommand(command);
        return sync(f);
    }

    protected void indexCommand(RedisCommand<?> command) {
        if (isQueueing() || isPipelined()) {
            index++;
            if (commandsToRemove.contains(command.getName())) {
                indexToRemove.add(index);
            }
        }
    }
    
    <T> T read(byte[] key, Codec codec, RedisCommand<?> command, Object... params) {
        RFuture<T> f = executorService.readAsync(key, codec, command, params);
        indexCommand(command);
        return sync(f);
    }
    
    @Override
    public void lSet(byte[] key, long index, byte[] value) {
        write(key, StringCodec.INSTANCE, RedisCommands.LSET, key, index, value);
    }

    private static final RedisStrictCommand<Long> LREM = new RedisStrictCommand<Long>("LREM");
    
    @Override
    public Long lRem(byte[] key, long count, byte[] value) {
        return write(key, StringCodec.INSTANCE, LREM, key, count, value);
    }
    
    @Override
    public byte[] lPop(byte[] key) {
        return write(key, ByteArrayCodec.INSTANCE, RedisCommands.LPOP, key);
    }

    @Override
    public byte[] rPop(byte[] key) {
        return write(key, ByteArrayCodec.INSTANCE, RedisCommands.RPOP, key);
    }

    @Override
    public List<byte[]> bLPop(int timeout, byte[]... keys) {
        List<Object> params = new ArrayList<Object>(keys.length + 1);
        params.addAll(Arrays.asList(keys));
        params.add(timeout);
        return write(keys[0], ByteArrayCodec.INSTANCE, RedisCommands.BLPOP, params.toArray());
    }

    @Override
    public List<byte[]> bRPop(int timeout, byte[]... keys) {
        List<Object> params = new ArrayList<Object>(keys.length + 1);
        params.addAll(Arrays.asList(keys));
        params.add(timeout);
        return write(keys[0], ByteArrayCodec.INSTANCE, RedisCommands.BRPOP, params.toArray());
    }

    @Override
    public byte[] rPopLPush(byte[] srcKey, byte[] dstKey) {
        return write(srcKey, ByteArrayCodec.INSTANCE, RedisCommands.RPOPLPUSH, srcKey, dstKey);
    }

    @Override
    public byte[] bRPopLPush(int timeout, byte[] srcKey, byte[] dstKey) {
        return write(srcKey, ByteArrayCodec.INSTANCE, RedisCommands.BRPOPLPUSH, srcKey, dstKey, timeout);
    }

    private static final RedisCommand<Long> SADD = new RedisCommand<Long>("SADD");
    
    @Override
    public Long sAdd(byte[] key, byte[]... values) {
        List<Object> args = new ArrayList<Object>(values.length + 1);
        args.add(key);
        args.addAll(Arrays.asList(values));
        
        return write(key, StringCodec.INSTANCE, SADD, args.toArray());
    }

    private static final RedisStrictCommand<Long> SREM = new RedisStrictCommand<Long>("SREM");
    
    @Override
    public Long sRem(byte[] key, byte[]... values) {
        List<Object> args = new ArrayList<Object>(values.length + 1);
        args.add(key);
        args.addAll(Arrays.asList(values));
        
        return write(key, StringCodec.INSTANCE, SREM, args.toArray());
    }

    @Override
    public byte[] sPop(byte[] key) {
        return write(key, ByteArrayCodec.INSTANCE, RedisCommands.SPOP, key);
    }

    @Override
    public Boolean sMove(byte[] srcKey, byte[] destKey, byte[] value) {
        return write(srcKey, StringCodec.INSTANCE, RedisCommands.SMOVE, srcKey, destKey, value);
    }

    private static final RedisStrictCommand<Long> SCARD = new RedisStrictCommand<Long>("SCARD");
    
    @Override
    public Long sCard(byte[] key) {
        return read(key, StringCodec.INSTANCE, SCARD, key);
    }

    @Override
    public Boolean sIsMember(byte[] key, byte[] value) {
        return read(key, StringCodec.INSTANCE, RedisCommands.SISMEMBER, key, value);
    }

    @Override
    public Set<byte[]> sInter(byte[]... keys) {
        return write(keys[0], ByteArrayCodec.INSTANCE, RedisCommands.SINTER, Arrays.asList(keys).toArray());
    }

    @Override
    public Long sInterStore(byte[] destKey, byte[]... keys) {
        List<Object> args = new ArrayList<Object>(keys.length + 1);
        args.add(destKey);
        args.addAll(Arrays.asList(keys));
        return write(keys[0], StringCodec.INSTANCE, RedisCommands.SINTERSTORE, args.toArray());
    }

    @Override
    public Set<byte[]> sUnion(byte[]... keys) {
        return write(keys[0], ByteArrayCodec.INSTANCE, RedisCommands.SUNION, Arrays.asList(keys).toArray());
    }

    @Override
    public Long sUnionStore(byte[] destKey, byte[]... keys) {
        List<Object> args = new ArrayList<Object>(keys.length + 1);
        args.add(destKey);
        args.addAll(Arrays.asList(keys));
        return write(keys[0], StringCodec.INSTANCE, RedisCommands.SUNIONSTORE, args.toArray());
    }

    @Override
    public Set<byte[]> sDiff(byte[]... keys) {
        return write(keys[0], ByteArrayCodec.INSTANCE, RedisCommands.SDIFF, Arrays.asList(keys).toArray());
    }

    @Override
    public Long sDiffStore(byte[] destKey, byte[]... keys) {
        List<Object> args = new ArrayList<Object>(keys.length + 1);
        args.add(destKey);
        args.addAll(Arrays.asList(keys));
        return write(keys[0], StringCodec.INSTANCE, RedisCommands.SDIFFSTORE, args.toArray());
    }

    @Override
    public Set<byte[]> sMembers(byte[] key) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.SMEMBERS, key);
    }

    @Override
    public byte[] sRandMember(byte[] key) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.SRANDMEMBER_SINGLE, key);
    }

    private static final RedisCommand<List<Object>> SRANDMEMBER = new RedisCommand<>("SRANDMEMBER", new ObjectListReplayDecoder<>());

    @Override
    public List<byte[]> sRandMember(byte[] key, long count) {
        return read(key, ByteArrayCodec.INSTANCE, SRANDMEMBER, key, count);
    }

    @Override
    public Cursor<byte[]> sScan(byte[] key, ScanOptions options) {
        return new KeyBoundCursor<byte[]>(key, 0, options) {

            private RedisClient client;

            @Override
            protected ScanIteration<byte[]> doScan(byte[] key, long cursorId, ScanOptions options) {
                if (isQueueing() || isPipelined()) {
                    throw new UnsupportedOperationException("'SSCAN' cannot be called in pipeline / transaction mode.");
                }

                List<Object> args = new ArrayList<Object>();
                args.add(key);
                args.add(cursorId);
                if (options.getPattern() != null) {
                    args.add("MATCH");
                    args.add(options.getPattern());
                }
                if (options.getCount() != null) {
                    args.add("COUNT");
                    args.add(options.getCount());
                }
                
                RFuture<ListScanResult<byte[]>> f = executorService.readAsync(client, key, ByteArrayCodec.INSTANCE, RedisCommands.SSCAN, args.toArray());
                ListScanResult<byte[]> res = syncFuture(f);
                client = res.getRedisClient();
                return new ScanIteration<byte[]>(res.getPos(), res.getValues());
            }
        }.open();
    }

    @Override
    public Boolean zAdd(byte[] key, double score, byte[] value) {
        return write(key, StringCodec.INSTANCE, RedisCommands.ZADD_BOOL, key, BigDecimal.valueOf(score).toPlainString(), value);
    }

    @Override
    public Long zAdd(byte[] key, Set<Tuple> tuples) {
        List<Object> params = new ArrayList<Object>(tuples.size()*2+1);
        params.add(key);
        for (Tuple entry : tuples) {
            params.add(BigDecimal.valueOf(entry.getScore()).toPlainString());
            params.add(entry.getValue());
        }
        return write(key, StringCodec.INSTANCE, RedisCommands.ZADD, params.toArray());
    }

    @Override
    public Long zRem(byte[] key, byte[]... values) {
        List<Object> params = new ArrayList<Object>(values.length+1);
        params.add(key);
        params.addAll(Arrays.asList(values));

        return write(key, StringCodec.INSTANCE, RedisCommands.ZREM_LONG, params.toArray());
    }

    @Override
    public Double zIncrBy(byte[] key, double increment, byte[] value) {
        return write(key, DoubleCodec.INSTANCE, RedisCommands.ZINCRBY,
                            key, new BigDecimal(increment).toPlainString(), value);
    }

    @Override
    public Long zRank(byte[] key, byte[] value) {
        return read(key, StringCodec.INSTANCE, RedisCommands.ZRANK, key, value);
    }

    @Override
    public Long zRevRank(byte[] key, byte[] value) {
        return read(key, StringCodec.INSTANCE, RedisCommands.ZREVRANK, key, value);
    }

    private static final RedisCommand<Set<Object>> ZRANGE = new RedisCommand<Set<Object>>("ZRANGE", new ObjectSetReplayDecoder<Object>());
    
    @Override
    public Set<byte[]> zRange(byte[] key, long start, long end) {
        return read(key, ByteArrayCodec.INSTANCE, ZRANGE, key, start, end);
    }

    private static final RedisCommand<Set<Tuple>> ZRANGE_ENTRY = new RedisCommand<Set<Tuple>>("ZRANGE", new ScoredSortedSetReplayDecoder());
    
    @Override
    public Set<Tuple> zRangeWithScores(byte[] key, long start, long end) {
        return read(key, ByteArrayCodec.INSTANCE, ZRANGE_ENTRY, key, start, end, "WITHSCORES");
    }

    private String value(Range.Boundary boundary, String defaultValue) {
        if (boundary == null) {
            return defaultValue;
        }
        Object score = boundary.getValue();
        if (score == null) {
            return defaultValue;
        }
        StringBuilder element = new StringBuilder();
        if (!boundary.isIncluding()) {
            element.append("(");
        } else {
            if (!(score instanceof Double)) {
                element.append("[");
            }
        }
        if (score instanceof Double) {
            if (Double.isInfinite((Double) score)) {
                element.append((Double)score > 0 ? "+inf" : "-inf");
            } else {
                element.append(BigDecimal.valueOf((Double)score).toPlainString());
            }
        } else {
            element.append(score);
        }
        return element.toString();
    }
    
    @Override
    public Set<byte[]> zRangeByScore(byte[] key, double min, double max) {
        return zRangeByScore(key, new Range().gte(min).lte(max));
    }

    @Override
    public Set<Tuple> zRangeByScoreWithScores(byte[] key, Range range) {
        return zRangeByScoreWithScores(key, range, null);
    }

    @Override
    public Set<Tuple> zRangeByScoreWithScores(byte[] key, double min, double max) {
        return zRangeByScoreWithScores(key, new Range().gte(min).lte(max));
    }

    @Override
    public Set<byte[]> zRangeByScore(byte[] key, double min, double max, long offset, long count) {
        return zRangeByScore(key, new Range().gte(min).lte(max),
                new Limit().offset(Long.valueOf(offset).intValue()).count(Long.valueOf(count).intValue()));
    }

    @Override
    public Set<Tuple> zRangeByScoreWithScores(byte[] key, double min, double max, long offset, long count) {
        return zRangeByScoreWithScores(key, new Range().gte(min).lte(max),
                new Limit().offset(Long.valueOf(offset).intValue()).count(Long.valueOf(count).intValue()));
    }
    
    private static final RedisCommand<Set<Tuple>> ZRANGEBYSCORE = new RedisCommand<Set<Tuple>>("ZRANGEBYSCORE", new ScoredSortedSetReplayDecoder());

    @Override
    public Set<Tuple> zRangeByScoreWithScores(byte[] key, Range range, Limit limit) {
        String min = value(range.getMin(), "-inf");
        String max = value(range.getMax(), "+inf");
        
        List<Object> args = new ArrayList<Object>();
        args.add(key);
        args.add(min);
        args.add(max);
        args.add("WITHSCORES");
        
        if (limit != null) {
            args.add("LIMIT");
            args.add(limit.getOffset());
            args.add(limit.getCount());
        }
        
        return read(key, ByteArrayCodec.INSTANCE, ZRANGEBYSCORE, args.toArray());
    }

    private static final RedisCommand<Set<Object>> ZREVRANGE = new RedisCommand<Set<Object>>("ZREVRANGE", new ObjectSetReplayDecoder<Object>());
    
    @Override
    public Set<byte[]> zRevRange(byte[] key, long start, long end) {
        return read(key, ByteArrayCodec.INSTANCE, ZREVRANGE, key, start, end);
    }

    private static final RedisCommand<Set<Tuple>> ZREVRANGE_ENTRY = new RedisCommand<Set<Tuple>>("ZREVRANGE", new ScoredSortedSetReplayDecoder());
    
    @Override
    public Set<Tuple> zRevRangeWithScores(byte[] key, long start, long end) {
        return read(key, ByteArrayCodec.INSTANCE, ZREVRANGE_ENTRY, key, start, end, "WITHSCORES");
    }

    @Override
    public Set<byte[]> zRevRangeByScore(byte[] key, double min, double max) {
        return zRevRangeByScore(key, new Range().gte(min).lte(max));
    }
    
    private static final RedisCommand<Set<byte[]>> ZREVRANGEBYSCORE = new RedisCommand<Set<byte[]>>("ZREVRANGEBYSCORE", new ObjectSetReplayDecoder<byte[]>());
    private static final RedisCommand<Set<Tuple>> ZREVRANGEBYSCOREWITHSCORES = new RedisCommand<Set<Tuple>>("ZREVRANGEBYSCORE", new ScoredSortedSetReplayDecoder());

    @Override
    public Set<byte[]> zRevRangeByScore(byte[] key, Range range) {
        return zRevRangeByScore(key, range, null);
    }

    @Override
    public Set<Tuple> zRevRangeByScoreWithScores(byte[] key, double min, double max) {
        return zRevRangeByScoreWithScores(key, new Range().gte(min).lte(max));
    }

    @Override
    public Set<byte[]> zRevRangeByScore(byte[] key, double min, double max, long offset, long count) {
        return zRevRangeByScore(key, new Range().gte(min).lte(max),
                new Limit().offset(Long.valueOf(offset).intValue()).count(Long.valueOf(count).intValue()));
    }

    @Override
    public Set<byte[]> zRevRangeByScore(byte[] key, Range range, Limit limit) {
        String min = value(range.getMin(), "-inf");
        String max = value(range.getMax(), "+inf");
        
        List<Object> args = new ArrayList<Object>();
        args.add(key);
        args.add(max);
        args.add(min);
        
        if (limit != null) {
            args.add("LIMIT");
            args.add(limit.getOffset());
            args.add(limit.getCount());
        }
        
        return read(key, ByteArrayCodec.INSTANCE, ZREVRANGEBYSCORE, args.toArray());
    }

    @Override
    public Set<Tuple> zRevRangeByScoreWithScores(byte[] key, double min, double max, long offset, long count) {
        return zRevRangeByScoreWithScores(key, new Range().gte(min).lte(max),
                new Limit().offset(Long.valueOf(offset).intValue()).count(Long.valueOf(count).intValue()));
    }

    @Override
    public Set<Tuple> zRevRangeByScoreWithScores(byte[] key, Range range) {
        return zRevRangeByScoreWithScores(key, range, null);
    }

    @Override
    public Set<Tuple> zRevRangeByScoreWithScores(byte[] key, Range range, Limit limit) {
        String min = value(range.getMin(), "-inf");
        String max = value(range.getMax(), "+inf");
        
        List<Object> args = new ArrayList<Object>();
        args.add(key);
        args.add(max);
        args.add(min);
        args.add("WITHSCORES");
        
        if (limit != null) {
            args.add("LIMIT");
            args.add(limit.getOffset());
            args.add(limit.getCount());
        }
        
        return read(key, ByteArrayCodec.INSTANCE, ZREVRANGEBYSCOREWITHSCORES, args.toArray());
    }

    @Override
    public Long zCount(byte[] key, double min, double max) {
        return zCount(key, new Range().gte(min).lte(max));
    }

    @Override
    public Long zCount(byte[] key, Range range) {
        String min = value(range.getMin(), "-inf");
        String max = value(range.getMax(), "+inf");
        return read(key, StringCodec.INSTANCE, RedisCommands.ZCOUNT, key, min, max);
    }

    @Override
    public Long zCard(byte[] key) {
        return read(key, StringCodec.INSTANCE, RedisCommands.ZCARD, key);
    }

    @Override
    public Double zScore(byte[] key, byte[] value) {
        return read(key, StringCodec.INSTANCE, RedisCommands.ZSCORE, key, value);
    }

    private static final RedisStrictCommand<Long> ZREMRANGEBYRANK = new RedisStrictCommand<Long>("ZREMRANGEBYRANK");
    private static final RedisStrictCommand<Long> ZREMRANGEBYSCORE = new RedisStrictCommand<Long>("ZREMRANGEBYSCORE");
    
    @Override
    public Long zRemRange(byte[] key, long start, long end) {
        return write(key, StringCodec.INSTANCE, ZREMRANGEBYRANK, key, start, end);
    }

    @Override
    public Long zRemRangeByScore(byte[] key, double min, double max) {
        return zRemRangeByScore(key, new Range().gte(min).lte(max));
    }

    @Override
    public Long zRemRangeByScore(byte[] key, Range range) {
        String min = value(range.getMin(), "-inf");
        String max = value(range.getMax(), "+inf");
        return write(key, StringCodec.INSTANCE, ZREMRANGEBYSCORE, key, min, max);
    }

    @Override
    public Long zUnionStore(byte[] destKey, byte[]... sets) {
        return zUnionStore(destKey, null, null, sets);
    }
    
    private static final RedisStrictCommand<Long> ZUNIONSTORE = new RedisStrictCommand<Long>("ZUNIONSTORE");

    @Override
    public Long zUnionStore(byte[] destKey, Aggregate aggregate, int[] weights, byte[]... sets) {
        List<Object> args = new ArrayList<Object>(sets.length*2 + 5);
        args.add(destKey);
        args.add(sets.length);
        args.addAll(Arrays.asList(sets));
        if (weights != null) {
            args.add("WEIGHTS");
            for (int weight : weights) {
                args.add(weight);
            }
        }
        if (aggregate != null) {
            args.add("AGGREGATE");
            args.add(aggregate.name());
        }
        return write(destKey, LongCodec.INSTANCE, ZUNIONSTORE, args.toArray());
    }

    private static final RedisStrictCommand<Long> ZINTERSTORE = new RedisStrictCommand<Long>("ZINTERSTORE");
    
    @Override
    public Long zInterStore(byte[] destKey, byte[]... sets) {
        return zInterStore(destKey, null, null, sets);
    }

    @Override
    public Long zInterStore(byte[] destKey, Aggregate aggregate, int[] weights, byte[]... sets) {
        List<Object> args = new ArrayList<Object>(sets.length*2 + 5);
        args.add(destKey);
        args.add(sets.length);
        args.addAll(Arrays.asList(sets));
        if (weights != null) {
            args.add("WEIGHTS");
            for (int weight : weights) {
                args.add(weight);
            }
        }
        if (aggregate != null) {
            args.add("AGGREGATE");
            args.add(aggregate.name());
        }
        return write(destKey, StringCodec.INSTANCE, ZINTERSTORE, args.toArray());
    }

    private static final RedisCommand<ListScanResult<Object>> ZSCAN = new RedisCommand<>("ZSCAN", new ListMultiDecoder2(new ListScanResultReplayDecoder(), new ScoredSortedListReplayDecoder()));
    
    @Override
    public Cursor<Tuple> zScan(byte[] key, ScanOptions options) {
        return new KeyBoundCursor<Tuple>(key, 0, options) {

            private RedisClient client;

            @Override
            protected ScanIteration<Tuple> doScan(byte[] key, long cursorId, ScanOptions options) {
                if (isQueueing() || isPipelined()) {
                    throw new UnsupportedOperationException("'ZSCAN' cannot be called in pipeline / transaction mode.");
                }

                List<Object> args = new ArrayList<Object>();
                args.add(key);
                args.add(cursorId);
                if (options.getPattern() != null) {
                    args.add("MATCH");
                    args.add(options.getPattern());
                }
                if (options.getCount() != null) {
                    args.add("COUNT");
                    args.add(options.getCount());
                }
                
                RFuture<ListScanResult<Tuple>> f = executorService.readAsync(client, key, ByteArrayCodec.INSTANCE, ZSCAN, args.toArray());
                ListScanResult<Tuple> res = syncFuture(f);
                client = res.getRedisClient();
                return new ScanIteration<Tuple>(res.getPos(), res.getValues());
            }
        }.open();
    }

    @Override
    public Set<byte[]> zRangeByScore(byte[] key, String min, String max) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.ZRANGEBYSCORE, key, min, max);
    }

    @Override
    public Set<byte[]> zRangeByScore(byte[] key, Range range) {
        String min = value(range.getMin(), "-inf");
        String max = value(range.getMax(), "+inf");
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.ZRANGEBYSCORE, key, min, max);
    }

    @Override
    public Set<byte[]> zRangeByScore(byte[] key, String min, String max, long offset, long count) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.ZRANGEBYSCORE, key, min, max, "LIMIT", offset, count);
    }

    @Override
    public Set<byte[]> zRangeByScore(byte[] key, Range range, Limit limit) {
        String min = value(range.getMin(), "-inf");
        String max = value(range.getMax(), "+inf");
        
        List<Object> args = new ArrayList<Object>();
        args.add(key);
        args.add(min);
        args.add(max);
        
        if (limit != null) {
            args.add("LIMIT");
            args.add(limit.getOffset());
            args.add(limit.getCount());
        }
        
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.ZRANGEBYSCORE, args.toArray());
    }

    @Override
    public Set<byte[]> zRangeByLex(byte[] key) {
        return zRangeByLex(key, Range.unbounded());
    }
    
    private static final RedisCommand<Set<Object>> ZRANGEBYLEX = new RedisCommand<Set<Object>>("ZRANGEBYLEX", new ObjectSetReplayDecoder<Object>());

    @Override
    public Set<byte[]> zRangeByLex(byte[] key, Range range) {
        List<Object> params = new ArrayList<Object>();
        params.add(key);
        if (range.getMin() != null) {
            String min = value(range.getMin(), "-");
            params.add(min);
        } else {
            params.add("-");
        }
        if (range.getMax() != null) {
            String max = value(range.getMax(), "+");
            params.add(max);
        } else {
            params.add("+");
        }
        return read(key, ByteArrayCodec.INSTANCE, ZRANGEBYLEX, params.toArray());
    }

    @Override
    public Set<byte[]> zRangeByLex(byte[] key, Range range, Limit limit) {
        String min = value(range.getMin(), "-");
        String max = value(range.getMax(), "+");
        
        List<Object> args = new ArrayList<Object>();
        args.add(key);
        args.add(min);
        args.add(max);
        
        if (limit != null) {
            args.add("LIMIT");
            args.add(limit.getOffset());
            args.add(limit.getCount());
        }
        
        return read(key, ByteArrayCodec.INSTANCE, ZRANGEBYLEX, args.toArray());
    }

    @Override
    public Boolean hSet(byte[] key, byte[] field, byte[] value) {
        return write(key, StringCodec.INSTANCE, RedisCommands.HSET, key, field, value);
    }

    @Override
    public Boolean hSetNX(byte[] key, byte[] field, byte[] value) {
        return write(key, StringCodec.INSTANCE, RedisCommands.HSETNX, key, field, value);
    }

    @Override
    public byte[] hGet(byte[] key, byte[] field) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.HGET, key, field);
    }

    private static final RedisCommand<List<Object>> HMGET = new RedisCommand<List<Object>>("HMGET", new ObjectListReplayDecoder<Object>());
    
    @Override
    public List<byte[]> hMGet(byte[] key, byte[]... fields) {
        List<Object> args = new ArrayList<Object>(fields.length + 1);
        args.add(key);
        args.addAll(Arrays.asList(fields));
        return read(key, ByteArrayCodec.INSTANCE, HMGET, args.toArray());
    }

    @Override
    public void hMSet(byte[] key, Map<byte[], byte[]> hashes) {
        List<Object> params = new ArrayList<Object>(hashes.size()*2 + 1);
        params.add(key);
        for (Map.Entry<byte[], byte[]> entry : hashes.entrySet()) {
            params.add(entry.getKey());
            params.add(entry.getValue());
        }

        write(key, StringCodec.INSTANCE, RedisCommands.HMSET, params.toArray());
    }
    
    private static final RedisCommand<Long> HINCRBY = new RedisCommand<Long>("HINCRBY");

    @Override
    public Long hIncrBy(byte[] key, byte[] field, long delta) {
        return write(key, StringCodec.INSTANCE, HINCRBY, key, field, delta);
    }
    
    private static final RedisCommand<Double> HINCRBYFLOAT = new RedisCommand<Double>("HINCRBYFLOAT", new DoubleReplayConvertor());

    @Override
    public Double hIncrBy(byte[] key, byte[] field, double delta) {
        return write(key, StringCodec.INSTANCE, HINCRBYFLOAT, key, field, BigDecimal.valueOf(delta).toPlainString());
    }

    @Override
    public Boolean hExists(byte[] key, byte[] field) {
        return read(key, StringCodec.INSTANCE, RedisCommands.HEXISTS, key, field);
    }

    @Override
    public Long hDel(byte[] key, byte[]... fields) {
        List<Object> args = new ArrayList<Object>(fields.length + 1);
        args.add(key);
        args.addAll(Arrays.asList(fields));
        return write(key, StringCodec.INSTANCE, RedisCommands.HDEL, args.toArray());
    }
    
    private static final RedisStrictCommand<Long> HLEN = new RedisStrictCommand<Long>("HLEN");

    @Override
    public Long hLen(byte[] key) {
        return read(key, StringCodec.INSTANCE, HLEN, key);
    }

    @Override
    public Set<byte[]> hKeys(byte[] key) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.HKEYS, key);
    }

    @Override
    public List<byte[]> hVals(byte[] key) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.HVALS, key);
    }

    @Override
    public Map<byte[], byte[]> hGetAll(byte[] key) {
        return read(key, ByteArrayCodec.INSTANCE, RedisCommands.HGETALL, key);
    }

    @Override
    public Cursor<Entry<byte[], byte[]>> hScan(byte[] key, ScanOptions options) {
        return new KeyBoundCursor<Entry<byte[], byte[]>>(key, 0, options) {

            private RedisClient client;

            @Override
            protected ScanIteration<Entry<byte[], byte[]>> doScan(byte[] key, long cursorId, ScanOptions options) {
                if (isQueueing() || isPipelined()) {
                    throw new UnsupportedOperationException("'HSCAN' cannot be called in pipeline / transaction mode.");
                }

                List<Object> args = new ArrayList<Object>();
                args.add(key);
                args.add(cursorId);
                if (options.getPattern() != null) {
                    args.add("MATCH");
                    args.add(options.getPattern());
                }
                if (options.getCount() != null) {
                    args.add("COUNT");
                    args.add(options.getCount());
                }
                
                RFuture<MapScanResult<byte[], byte[]>> f = executorService.readAsync(client, key, ByteArrayCodec.INSTANCE, RedisCommands.HSCAN, args.toArray());
                MapScanResult<byte[], byte[]> res = syncFuture(f);
                client = res.getRedisClient();
                return new ScanIteration<Entry<byte[], byte[]>>(res.getPos(), res.getValues());
            }
        }.open();
    }

    @Override
    public void multi() {
        if (isQueueing()) {
            return;
        }

        if (isPipelined()) {
            BatchOptions options = BatchOptions.defaults()
                    .executionMode(ExecutionMode.IN_MEMORY_ATOMIC);
            this.executorService = new CommandBatchService(redisson.getConnectionManager(), options);
            return;
        }
        
        BatchOptions options = BatchOptions.defaults()
            .executionMode(ExecutionMode.REDIS_WRITE_ATOMIC);
        this.executorService = new CommandBatchService(redisson.getConnectionManager(), options);
    }

    @Override
    public List<Object> exec() {
        if (isPipelinedAtomic()) {
            return null;
        }
        if (isQueueing()) {
            try {
                BatchResult<?> result = ((CommandBatchService)executorService).execute();
                filterResults(result);
                return (List<Object>) result.getResponses();
            } catch (Exception ex) {
                throw transform(ex);
            } finally {
                resetConnection();
            }
        } else {
            throw new InvalidDataAccessApiUsageException("Not in transaction mode. Please invoke multi method");
        }
    }

    protected void filterResults(BatchResult<?> result) {
        if (result.getResponses().isEmpty()) {
            return;
        }

        int t = 0;
        for (Integer index : indexToRemove) {
            index -= t;
            result.getResponses().remove((int)index);
            t++;
        }
        for (ListIterator<Object> iterator = (ListIterator<Object>) result.getResponses().listIterator(); iterator.hasNext();) {
            Object object = iterator.next();
            if (object instanceof String) {
                iterator.set(((String) object).getBytes());
            }
        }
    }

    protected void resetConnection() {
        executorService = (CommandAsyncService) this.redisson.getCommandExecutor();
        index = -1;
        indexToRemove.clear();
    }

    @Override
    public void discard() {
        if (isQueueing()) {
            syncFuture(executorService.writeAsync(null, RedisCommands.DISCARD));
            resetConnection();
        } else {
            throw new InvalidDataAccessApiUsageException("Not in transaction mode. Please invoke multi method");
        }
    }

    @Override
    public void watch(byte[]... keys) {
        if (isQueueing()) {
            throw new UnsupportedOperationException();
        }

        syncFuture(executorService.writeAsync(null, RedisCommands.WATCH, keys));
    }

    @Override
    public void unwatch() {
        syncFuture(executorService.writeAsync(null, RedisCommands.UNWATCH));
    }

    @Override
    public boolean isSubscribed() {
        return subscription != null && subscription.isAlive();
    }

    @Override
    public Subscription getSubscription() {
        return subscription;
    }

    @Override
    public Long publish(byte[] channel, byte[] message) {
        return write(channel, StringCodec.INSTANCE, RedisCommands.PUBLISH, channel, message);
    }

    @Override
    public void subscribe(MessageListener listener, byte[]... channels) {
        checkSubscription();
        
        subscription = new RedissonSubscription(redisson.getConnectionManager(), redisson.getConnectionManager().getSubscribeService(), listener);
        subscription.subscribe(channels);
    }

    private void checkSubscription() {
        if (subscription != null) {
            throw new RedisSubscribedConnectionException("Connection already subscribed");
        }
        
        if (isQueueing()) {
            throw new UnsupportedOperationException("Not supported in queueing mode");
        }
        if (isPipelined()) {
            throw new UnsupportedOperationException("Not supported in pipelined mode");
        }
    }

    @Override
    public void pSubscribe(MessageListener listener, byte[]... patterns) {
        checkSubscription();
        
        subscription = new RedissonSubscription(redisson.getConnectionManager(), redisson.getConnectionManager().getSubscribeService(), listener);
        subscription.pSubscribe(patterns);
    }

    @Override
    public void select(int dbIndex) {
        throw new UnsupportedOperationException();
    }

    private static final RedisCommand<Object> ECHO = new RedisCommand<Object>("ECHO");
    
    @Override
    public byte[] echo(byte[] message) {
        return read(null, ByteArrayCodec.INSTANCE, ECHO, message);
    }

    @Override
    public String ping() {
        return read(null, StringCodec.INSTANCE, RedisCommands.PING);
    }
    
    @Override
    public void bgWriteAof() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void bgReWriteAof() {
        write(null, StringCodec.INSTANCE, RedisCommands.BGREWRITEAOF);
    }
    
    @Override
    public void bgSave() {
        write(null, StringCodec.INSTANCE, RedisCommands.BGSAVE);
    }
    
    @Override
    public Long lastSave() {
        return write(null, StringCodec.INSTANCE, RedisCommands.LASTSAVE);
    }
    
    private static final RedisStrictCommand<Void> SAVE = new RedisStrictCommand<Void>("SAVE", new VoidReplayConvertor());

    @Override
    public void save() {
        write(null, StringCodec.INSTANCE, SAVE);
    }

    @Override
    public Long dbSize() {
        if (isQueueing()) {
            return read(null, StringCodec.INSTANCE, RedisCommands.DBSIZE);
        }
        
        RFuture<Long> f = executorService.readAllAsync(RedisCommands.DBSIZE, new SlotCallback<Long, Long>() {
            AtomicLong results = new AtomicLong();
            @Override
            public void onSlotResult(Long result) {
                results.addAndGet(result);
            }

            @Override
            public Long onFinish() {
                return results.get();
            }
        });
        return sync(f);
    }

    @Override
    public void flushDb() {
        if (isQueueing() || isPipelined()) {
            write(null, StringCodec.INSTANCE, RedisCommands.FLUSHDB);
            return;
        }
        
        RFuture<Void> f = executorService.writeAllAsync(RedisCommands.FLUSHDB);
        sync(f);
    }

    @Override
    public void flushAll() {
        RFuture<Void> f = executorService.writeAllAsync(RedisCommands.FLUSHALL);
        sync(f);
    }

    private static final RedisStrictCommand<Properties> INFO_DEFAULT = new RedisStrictCommand<Properties>("INFO", "DEFAULT", new ObjectDecoder(new PropertiesDecoder()));
    private static final RedisStrictCommand<Properties> INFO = new RedisStrictCommand<Properties>("INFO", new ObjectDecoder(new PropertiesDecoder()));
    
    @Override
    public Properties info() {
        return read(null, StringCodec.INSTANCE, INFO_DEFAULT);
    }

    @Override
    public Properties info(String section) {
        return read(null, StringCodec.INSTANCE, INFO, section);
    }

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown(ShutdownOption option) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> getConfig(String pattern) {
        return read(null, StringCodec.INSTANCE, RedisCommands.CONFIG_GET, pattern);
    }

    @Override
    public void setConfig(String param, String value) {
        write(null, StringCodec.INSTANCE, RedisCommands.CONFIG_SET, param, value);
    }

    @Override
    public void resetConfigStats() {
        write(null, StringCodec.INSTANCE, RedisCommands.CONFIG_RESETSTAT);
    }

    private static final RedisStrictCommand<Long> TIME = new RedisStrictCommand<Long>("TIME", new TimeLongObjectDecoder());
    
    @Override
    public Long time() {
        return read(null, LongCodec.INSTANCE, TIME);
    }

    @Override
    public void killClient(String host, int port) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setClientName(byte[] name) {
        throw new UnsupportedOperationException("Should be defined through Redisson Config object");
    }

    @Override
    public String getClientName() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<RedisClientInfo> getClientList() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void slaveOf(String host, int port) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void slaveOfNoOne() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void scriptFlush() {
        if (isQueueing() || isPipelined()) {
            throw new UnsupportedOperationException();
        }

        RFuture<Void> f = executorService.writeAllAsync(RedisCommands.SCRIPT_FLUSH);
        sync(f);
    }

    @Override
    public void scriptKill() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String scriptLoad(byte[] script) {
        if (isQueueing()) {
            throw new UnsupportedOperationException();
        }
        if (isPipelined()) {
            throw new UnsupportedOperationException();
        }

        RFuture<String> f = executorService.writeAllAsync(StringCodec.INSTANCE, RedisCommands.SCRIPT_LOAD, new SlotCallback<String, String>() {
            volatile String result;
            @Override
            public void onSlotResult(String result) {
                this.result = result;
            }

            @Override
            public String onFinish() {
                return result;
            }
        }, script);
        return sync(f);
    }

    @Override
    public List<Boolean> scriptExists(final String... scriptShas) {
        if (isQueueing() || isPipelined()) {
            throw new UnsupportedOperationException();
        }

        RFuture<List<Boolean>> f = executorService.writeAllAsync(RedisCommands.SCRIPT_EXISTS, new SlotCallback<List<Boolean>, List<Boolean>>() {
            
            List<Boolean> result = new ArrayList<Boolean>(scriptShas.length);
            
            @Override
            public synchronized void onSlotResult(List<Boolean> result) {
                for (int i = 0; i < result.size(); i++) {
                    if (this.result.size() == i) {
                        this.result.add(false);
                    }
                    this.result.set(i, this.result.get(i) | result.get(i));
                }
            }

            @Override
            public List<Boolean> onFinish() {
                return result;
            }
        }, (Object[])scriptShas);
        return sync(f);
    }

    @Override
    public <T> T eval(byte[] script, ReturnType returnType, int numKeys, byte[]... keysAndArgs) {
        if (isQueueing()) {
            throw new UnsupportedOperationException();
        }
        if (isPipelined()) {
            throw new UnsupportedOperationException();
        }

        RedisCommand<?> c = toCommand(returnType, "EVAL");
        List<Object> params = new ArrayList<Object>();
        params.add(script);
        params.add(numKeys);
        params.addAll(Arrays.asList(keysAndArgs));
        return write(null, StringCodec.INSTANCE, c, params.toArray());
    }

    protected RedisCommand<?> toCommand(ReturnType returnType, String name) {
        RedisCommand<?> c = null; 
        if (returnType == ReturnType.BOOLEAN) {
            c = org.redisson.api.RScript.ReturnType.BOOLEAN.getCommand();
        } else if (returnType == ReturnType.INTEGER) {
            c = org.redisson.api.RScript.ReturnType.INTEGER.getCommand();
        } else if (returnType == ReturnType.MULTI) {
            c = org.redisson.api.RScript.ReturnType.MULTI.getCommand();
            return new RedisCommand(c, name, new BinaryConvertor());
        } else if (returnType == ReturnType.STATUS) {
            c = org.redisson.api.RScript.ReturnType.STATUS.getCommand();
        } else if (returnType == ReturnType.VALUE) {
            c = org.redisson.api.RScript.ReturnType.VALUE.getCommand();
            return new RedisCommand(c, name, new BinaryConvertor());
        }
        return new RedisCommand(c, name);
    }

    @Override
    public <T> T evalSha(String scriptSha, ReturnType returnType, int numKeys, byte[]... keysAndArgs) {
        if (isQueueing()) {
            throw new UnsupportedOperationException();
        }
        if (isPipelined()) {
            throw new UnsupportedOperationException();
        }

        RedisCommand<?> c = toCommand(returnType, "EVALSHA");
        List<Object> params = new ArrayList<Object>();
        params.add(scriptSha);
        params.add(numKeys);
        params.addAll(Arrays.asList(keysAndArgs));
        return write(null, ByteArrayCodec.INSTANCE, c, params.toArray());
    }

    @Override
    public <T> T evalSha(byte[] scriptSha, ReturnType returnType, int numKeys, byte[]... keysAndArgs) {
        RedisCommand<?> c = toCommand(returnType, "EVALSHA");
        List<Object> params = new ArrayList<Object>();
        params.add(scriptSha);
        params.add(numKeys);
        params.addAll(Arrays.asList(keysAndArgs));
        return write(null, ByteArrayCodec.INSTANCE, c, params.toArray());
    }

    private static final RedisCommand<Long> PFADD = new RedisCommand<Long>("PFADD");

    @Override
    public Long pfAdd(byte[] key, byte[]... values) {
        List<Object> params = new ArrayList<Object>(values.length + 1);
        params.add(key);
        for (byte[] member : values) {
            params.add(member);
        }

        return write(key, StringCodec.INSTANCE, PFADD, params.toArray());
    }

    @Override
    public Long pfCount(byte[]... keys) {
        Assert.notEmpty(keys, "PFCOUNT requires at least one non 'null' key.");
        Assert.noNullElements(keys, "Keys for PFOUNT must not contain 'null'.");

        return write(keys[0], StringCodec.INSTANCE, RedisCommands.PFCOUNT, Arrays.asList(keys).toArray());
    }

    @Override
    public void pfMerge(byte[] destinationKey, byte[]... sourceKeys) {
        List<Object> args = new ArrayList<Object>(sourceKeys.length + 1);
        args.add(destinationKey);
        args.addAll(Arrays.asList(sourceKeys));
        write(destinationKey, StringCodec.INSTANCE, RedisCommands.PFMERGE, args.toArray());
    }

}
