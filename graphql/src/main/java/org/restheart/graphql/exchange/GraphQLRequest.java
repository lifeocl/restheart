package org.restheart.graphql.exchange;


import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.undertow.server.HttpServerExchange;
import org.restheart.exchange.ServiceRequest;
import org.restheart.graphql.models.GraphQLApp;
import org.restheart.utils.ChannelReader;

import java.io.IOException;
public class GraphQLRequest extends ServiceRequest<JsonElement> {

    private static final String GRAPHQL_CONTENT_TYPE = "application/graphql";
    private static final String QUERY_FIELD = "query";
    private static final String OPERATION_NAME_FIELD = "operationName";
    private static final String VARIABLES_FIELD = "variables";

    private final String appUri;
    private final GraphQLApp appDefinition;


    private GraphQLRequest(HttpServerExchange exchange, String appUri, GraphQLApp appDefinition) {
        super(exchange);
        this.appUri = appUri;
        this.appDefinition = appDefinition;
    }

    public static GraphQLRequest init(HttpServerExchange exchange, String appUri, GraphQLApp appDefinition) {
        var ret = new GraphQLRequest(exchange, appUri, appDefinition);

        try{

            if (isContentTypeGraphQL(exchange)){
                ret.injectContentGraphQL();
            }
            else if (isContentTypeJson(exchange)){
                ret.injectContentJson();
            }
            else ret.setInError(true);

        } catch (IOException ioe){
            ret.setInError(true);
        }

        return ret;
    }

    public static GraphQLRequest of(HttpServerExchange exchange) {
        return of(exchange, GraphQLRequest.class);
    }

    public void injectContentJson() throws IOException {
        String body = ChannelReader.read(wrapped.getRequestChannel());
        var json = JsonParser.parseString(body);

        setContent(json);
    }

    public void injectContentGraphQL() throws IOException {

        String body = ChannelReader.read(wrapped.getRequestChannel());
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(QUERY_FIELD,body);

        setContent(jsonObject);

    }

    public String getQuery(){
        if (this.getContent().getAsJsonObject().has(QUERY_FIELD)){
            return this.getContent().getAsJsonObject().get(QUERY_FIELD).getAsString();
        }
        else return null;
    }

    public String getOperationName(){
        if (this.getContent().getAsJsonObject().has(OPERATION_NAME_FIELD)) {
            return this.getContent().getAsJsonObject().get(OPERATION_NAME_FIELD).getAsString();
        }
        else return null;
    }

    public JsonObject getVariables(){
        if (this.getContent().getAsJsonObject().has(VARIABLES_FIELD)) {
            return this.getContent().getAsJsonObject().get(VARIABLES_FIELD).getAsJsonObject();
        }
        else return null;
    }

    public String getGraphQLAppURI(){
       return this.appUri;
    }

    public GraphQLApp getAppDefinition(){
        return this.appDefinition;
    }

    public boolean hasVariables(){
        return this.getContent().getAsJsonObject().has(VARIABLES_FIELD);
    }

    private static boolean isContentTypeGraphQL(HttpServerExchange exchange){

        String contentType = getContentType(exchange);

        return GRAPHQL_CONTENT_TYPE.equals(contentType) || (contentType != null
                && contentType.startsWith("application/graphql;"));

    }

}