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
package org.redisson.client.protocol.decoder;

import org.redisson.client.codec.Codec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;

import java.util.List;

/**
 * @author Nikita Koksharov
 */
public class MapValueDecoder<T> implements MultiDecoder<Object> {

    private final MultiDecoder<Object> decoder;

    public MapValueDecoder(MultiDecoder<Object> decoder) {
        this.decoder = decoder;
    }

    public MapValueDecoder() {
        this(null);
    }

    @Override
    public Decoder<Object> getDecoder(Codec codec, int paramNum, State state) {
        return codec.getMapValueDecoder();
    }

    @Override
    public T decode(List<Object> parts, State state) {
        return (T) decoder.decode(parts, state);
    }

}
