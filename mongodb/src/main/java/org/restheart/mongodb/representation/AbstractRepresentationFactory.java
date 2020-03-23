/*-
 * ========================LICENSE_START=================================
 * restheart-mongodb
 * %%
 * Copyright (C) 2014 - 2020 SoftInstigate
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * =========================LICENSE_END==================================
 */
package org.restheart.mongodb.representation;

import io.undertow.server.HttpServerExchange;
import static java.lang.Math.toIntExact;
import java.util.List;
import java.util.TreeMap;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.restheart.handlers.exchange.BsonRequest;
import org.restheart.mongodb.handlers.IllegalQueryParamenterException;
import org.restheart.mongodb.utils.URLUtils;

/**
 *
 * @author Maurizio Turatti {@literal <maurizio@softinstigate.com>}
 */
public abstract class AbstractRepresentationFactory {

    /**
     *
     * @param exchange
     * @param embeddedData
     * @param size
     * @return the resource HAL representation
     * @throws IllegalQueryParamenterException
     */
    public abstract Resource getRepresentation(
            HttpServerExchange exchange,
            List<BsonDocument> embeddedData,
            long size)
            throws IllegalQueryParamenterException;

    /**
     *
     * @param size
     * @param request
     * @param rep
     */
    protected void addSizeAndTotalPagesProperties(
            final long size,
            BsonRequest request,
            final Resource rep) {

        if (size == 0) {
            rep.addProperty("_size", new BsonInt32(0));

            if (request.getPagesize() > 0) {
                rep.addProperty("_total_pages", new BsonInt32(0));
            }
        }

        if (size > 0) {
            float _size = size + 0f;
            float _pagesize = request.getPagesize() + 0f;

            rep.addProperty("_size", new BsonInt32(toIntExact(size)));

            if (request.getPagesize() > 0) {
                rep.addProperty("_total_pages", new BsonInt32(
                        toIntExact(
                                Math.max(1,
                                        Math.round(
                                                Math.ceil(_size / _pagesize)
                                        )))));
            }
        }
    }

    /**
     *
     * @param embeddedData
     * @param rep
     */
    protected void addReturnedProperty(
            final List<BsonDocument> embeddedData,
            final Resource rep) {
        long count = embeddedData == null ? 0 : embeddedData.size();

        rep.addProperty("_returned", new BsonInt32(toIntExact(count)));
    }

    /**
     *
     * @param exchange
     * @param requestPath
     * @return
     */
    protected Resource createRepresentation(
            final HttpServerExchange exchange,
            final String requestPath) {
        var request = BsonRequest.wrap(exchange);

        String queryString
                = exchange.getQueryString() == null
                || exchange.getQueryString().isEmpty()
                ? ""
                : "?" + URLUtils.decodeQueryString(
                        exchange.getQueryString());

        Resource rep;

        if (requestPath != null || request.isFullHalMode()) {
            rep = new Resource(requestPath + queryString);
        } else {
            rep = new Resource();
        }

        return rep;
    }

    /**
     *
     * @param exchange
     * @return
     */
    protected String buildRequestPath(final HttpServerExchange exchange) {
        String requestPath = URLUtils.removeTrailingSlashes(
                exchange.getRequestPath());
        return requestPath;
    }

    /**
     *
     * @param exchange
     * @param size
     * @param rep
     * @throws IllegalQueryParamenterException
     */
    protected void addPaginationLinks(
            HttpServerExchange exchange,
            long size,
            final Resource rep)
            throws IllegalQueryParamenterException {
        var request = BsonRequest.wrap(exchange);
        if (request.getPagesize() > 0) {
            TreeMap<String, String> links;
            links = RepUtils.getPaginationLinks(exchange, size);
            if (links != null) {
                links.keySet().stream().forEach((k) -> {
                    rep.addLink(new Link(k, links.get(k)));
                });
            }
        }
    }

    /**
     *
     * @param requestPath
     * @return
     */
    protected boolean hasTrailingSlash(final String requestPath) {
        return requestPath.substring(requestPath.length() > 0
                ? requestPath.length() - 1
                : 0).equals("/");
    }
}
