/*-
 * ========================LICENSE_START=================================
 * restheart-commons
 * %%
 * Copyright (C) 2019 - 2020 SoftInstigate
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.restheart.exchange;

import io.undertow.server.HttpServerExchange;
import java.io.IOException;

import org.restheart.utils.ChannelReader;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class StringRequest extends ServiceRequest<String> {
    private StringRequest(HttpServerExchange exchange) {
        super(exchange);
    }

    public static StringRequest init(HttpServerExchange exchange) {
        var ret = new StringRequest(exchange);

        try {
            ret.injectContent();
        } catch (IOException ieo) {
            ret.setInError(true);
        }

        return ret;
    }

    public static StringRequest of(HttpServerExchange exchange) {
        return of(exchange, StringRequest.class);
    }

    public void injectContent() throws IOException {
        setContent(ChannelReader.readString(wrapped));
    }
}
