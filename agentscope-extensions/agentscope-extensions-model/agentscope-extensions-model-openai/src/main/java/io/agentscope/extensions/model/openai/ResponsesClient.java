/*
 * Copyright 2024-2026 the original author or authors.
 *
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
 */
package io.agentscope.extensions.model.openai;

import io.agentscope.core.Version;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.ResponsesRequest;
import io.agentscope.extensions.model.openai.dto.ResponsesResponse;
import io.agentscope.extensions.model.openai.dto.ResponsesStreamEvent;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Stateless HTTP client for the OpenAI Responses API ({@code /v1/responses}).
 *
 * <p>Mirrors {@link OpenAIClient} but targets the Responses endpoint with its distinct
 * request/response DTOs. The URL-building and header logic is duplicated from
 * {@code OpenAIClient} (which has private methods) to avoid modifying the existing
 * Chat Completions client.
 *
 * <p>Streaming: the transport delivers each SSE {@code data:} line's JSON payload as a
 * String. Each line is deserialized into a {@link ResponsesStreamEvent}; the event type
 * is read from the JSON {@code type} field (the {@code event:} SSE line is discarded by
 * the transport, but the type is duplicated in the payload).
 */
public class ResponsesClient {

    private static final Logger log = LoggerFactory.getLogger(ResponsesClient.class);

    /** SSE sentinel emitted by the OpenAI streaming API to signal end of stream. */
    private static final String SSE_DONE_MARKER = "[DONE]";

    /** Default base URL for OpenAI API. */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /** Default base URL with version. */
    public static final String DEFAULT_BASE_URL_WITH_VERSION = DEFAULT_BASE_URL + "/v1";

    /** Responses API endpoint. */
    public static final String RESPONSES_ENDPOINT = "/v1/responses";

    private final HttpTransport transport;

    private static final Pattern VERSION_PATTERN = Pattern.compile(".*/v\\d+$");

    public ResponsesClient(HttpTransport transport) {
        this.transport = transport;
    }

    public ResponsesClient() {
        this(HttpTransportFactory.getDefault());
    }

    /**
     * Make a streaming API call to the Responses endpoint.
     *
     * @param apiKey  the API key for authentication
     * @param baseUrl the base URL (null for default)
     * @param request the Responses API request
     * @param options generation options (may override apiKey, baseUrl, endpointPath)
     * @return a Flux of streaming events
     */
    public Flux<ResponsesStreamEvent> stream(
            String apiKey, String baseUrl, ResponsesRequest request, GenerateOptions options) {
        Objects.requireNonNull(request, "Request cannot be null");

        String effectiveBaseUrl = getEffectiveBaseUrl(baseUrl);
        String effectiveApiKey = apiKey;

        if (options != null) {
            if (options.getApiKey() != null) {
                effectiveApiKey = options.getApiKey();
            }
            if (options.getBaseUrl() != null) {
                effectiveBaseUrl = getEffectiveBaseUrl(options.getBaseUrl());
            }
        }

        String endpointPath = RESPONSES_ENDPOINT;
        if (options != null && options.getEndpointPath() != null) {
            endpointPath = options.getEndpointPath();
        }

        String apiUrl = buildApiUrl(effectiveBaseUrl, endpointPath);
        String url = buildUrl(apiUrl, options);

        try {
            request.setStream(true);
            String requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("Responses streaming request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(effectiveApiKey, options))
                            .body(requestBody)
                            .build();

            return transport.stream(httpRequest)
                    // The SSE `[DONE]` sentinel terminates the stream: complete the Flux here
                    // rather than merely filtering it out, otherwise completion is deferred until
                    // the underlying connection closes (keep-alive gateways may stall this for the
                    // full idle timeout even though the model has finished responding).
                    .takeWhile(data -> !SSE_DONE_MARKER.equals(data))
                    .<ResponsesStreamEvent>handle(
                            (data, sink) -> {
                                ResponsesStreamEvent event = parseStreamData(data);
                                if (event != null) {
                                    sink.next(event);
                                }
                            })
                    .onErrorMap(
                            ex -> {
                                if (ex instanceof HttpTransportException hte) {
                                    Integer code = hte.getStatusCode();
                                    String msg =
                                            "HTTP transport error during streaming: "
                                                    + ex.getMessage();
                                    if (code != null) {
                                        return OpenAIException.create(
                                                code, msg, null, hte.getResponseBody());
                                    }
                                    return new OpenAIException(msg, ex);
                                }
                                return ex;
                            });
        } catch (JsonException | HttpTransportException e) {
            return Flux.error(
                    new OpenAIException("Failed to initialize request: " + e.getMessage(), e));
        }
    }

    /**
     * Make a synchronous (non-streaming) API call to the Responses endpoint.
     *
     * @param apiKey  the API key for authentication
     * @param baseUrl the base URL (null for default)
     * @param request the Responses API request
     * @param options generation options (may override apiKey, baseUrl, endpointPath)
     * @return the full Responses API response
     */
    public ResponsesResponse call(
            String apiKey, String baseUrl, ResponsesRequest request, GenerateOptions options) {
        Objects.requireNonNull(request, "Request cannot be null");

        String effectiveBaseUrl = getEffectiveBaseUrl(baseUrl);
        String effectiveApiKey = apiKey;

        if (options != null) {
            if (options.getApiKey() != null) {
                effectiveApiKey = options.getApiKey();
            }
            if (options.getBaseUrl() != null) {
                effectiveBaseUrl = getEffectiveBaseUrl(options.getBaseUrl());
            }
        }

        String endpointPath = RESPONSES_ENDPOINT;
        if (options != null && options.getEndpointPath() != null) {
            endpointPath = options.getEndpointPath();
        }

        String apiUrl = buildApiUrl(effectiveBaseUrl, endpointPath);
        String url = buildUrl(apiUrl, options);

        try {
            request.setStream(false);
            String requestBody = JsonUtils.getJsonCodec().toJson(request);
            log.debug("Responses request to {}: {}", url, requestBody);

            HttpRequest httpRequest =
                    HttpRequest.builder()
                            .url(url)
                            .method("POST")
                            .headers(buildHeaders(effectiveApiKey, options))
                            .body(requestBody)
                            .build();

            HttpResponse httpResponse = transport.execute(httpRequest);

            if (!httpResponse.isSuccessful()) {
                int statusCode = httpResponse.getStatusCode();
                String responseBody = httpResponse.getBody();
                throw OpenAIException.create(
                        statusCode,
                        "Responses API request failed with status "
                                + statusCode
                                + " | "
                                + responseBody,
                        null,
                        responseBody);
            }

            String responseBody = httpResponse.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                throw new OpenAIException(
                        "Responses API returned empty response body",
                        httpResponse.getStatusCode(),
                        null);
            }

            ResponsesResponse response =
                    JsonUtils.getJsonCodec().fromJson(responseBody, ResponsesResponse.class);
            if (response == null) {
                throw new OpenAIException(
                        "Responses API returned null response after deserialization",
                        httpResponse.getStatusCode(),
                        responseBody);
            }

            if (response.isError()) {
                String errorMessage =
                        response.getError() != null
                                ? response.getError().getMessage()
                                : "Unknown error";
                String errorCode =
                        response.getError() != null ? response.getError().getCode() : null;
                throw OpenAIException.create(
                        400, "Responses API error: " + errorMessage, errorCode, responseBody);
            }

            return response;
        } catch (JsonException e) {
            throw new OpenAIException(
                    "Failed to parse Responses API response: " + e.getMessage(), e);
        } catch (HttpTransportException e) {
            throw new OpenAIException("HTTP transport error: " + e.getMessage(), e);
        }
    }

    private ResponsesStreamEvent parseStreamData(String data) {
        try {
            if (data == null || data.isEmpty()) {
                return null;
            }
            return JsonUtils.getJsonCodec().fromJson(data, ResponsesStreamEvent.class);
        } catch (JsonException e) {
            log.warn(
                    "Failed to parse Responses SSE data: {}",
                    data.length() > 100 ? data.substring(0, 100) + "..." : data);
            return null;
        } catch (Exception e) {
            log.warn("Unexpected error parsing Responses SSE data: {}", e.getMessage());
            return null;
        }
    }

    // --- URL building (duplicated from OpenAIClient, which has private methods) ---

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String buildApiUrl(String baseUrl, String endpointPath) {
        try {
            URI baseUri = URI.create(baseUrl);
            String basePath = baseUri.getPath();

            String adjustedEndpoint = endpointPath;
            if (basePath != null
                    && VERSION_PATTERN
                            .matcher(
                                    basePath.endsWith("/")
                                            ? basePath.substring(0, basePath.length() - 1)
                                            : basePath)
                            .matches()) {
                adjustedEndpoint =
                        endpointPath.startsWith("/v1/")
                                ? endpointPath.substring(3)
                                : (endpointPath.equals("/v1") ? "" : endpointPath);
            }

            String finalPath = joinPaths(basePath, adjustedEndpoint);

            URI finalUri =
                    new URI(
                            baseUri.getScheme(),
                            baseUri.getAuthority(),
                            finalPath,
                            baseUri.getQuery(),
                            baseUri.getFragment());

            return finalUri.toString();
        } catch (Exception e) {
            log.warn(
                    "Failed to parse base URL as URI, using simple concatenation: {}",
                    e.getMessage());
            String normalizedBase = VERSION_PATTERN.matcher(baseUrl).replaceFirst("");
            String normalizedEndpoint =
                    endpointPath.startsWith("/v1/") ? endpointPath.substring(3) : endpointPath;
            String separator = normalizedBase.endsWith("/") ? "" : "/";
            return normalizedBase
                    + separator
                    + (normalizedEndpoint.startsWith("/")
                            ? normalizedEndpoint.substring(1)
                            : normalizedEndpoint);
        }
    }

    private String joinPaths(String path1, String path2) {
        if (path2 == null || path2.isEmpty()) {
            return path1 != null ? path1 : "";
        }
        if (path1 == null || path1.isEmpty()) {
            return path2.startsWith("/") ? path2 : "/" + path2;
        }
        String p1 = path1.endsWith("/") ? path1.substring(0, path1.length() - 1) : path1;
        String p2 = path2.startsWith("/") ? path2.substring(1) : path2;
        return p1.isEmpty() ? "/" + p2 : p1 + "/" + p2;
    }

    private String getEffectiveBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isEmpty()) {
            return normalizeBaseUrl(baseUrl);
        }
        return DEFAULT_BASE_URL_WITH_VERSION;
    }

    private Map<String, String> buildHeaders(String apiKey, GenerateOptions options) {
        Map<String, String> headers = new HashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", Version.getUserAgent());

        if (options != null) {
            Map<String, String> additionalHeaders = options.getAdditionalHeaders();
            if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
                headers.putAll(additionalHeaders);
            }
        }
        return headers;
    }

    private String buildUrl(String baseUrl, GenerateOptions options) {
        if (options == null) {
            return baseUrl;
        }
        Map<String, String> queryParams = options.getAdditionalQueryParams();
        if (queryParams == null || queryParams.isEmpty()) {
            return baseUrl;
        }
        StringBuilder url = new StringBuilder(baseUrl);
        boolean first = !baseUrl.contains("?");
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            url.append(first ? "?" : "&");
            first = false;
            if (entry.getKey() != null && entry.getValue() != null) {
                url.append(
                                java.net.URLEncoder.encode(
                                        entry.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                        .append("=")
                        .append(
                                java.net.URLEncoder.encode(
                                        entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return url.toString();
    }

    /**
     * Get the HTTP transport used by this client.
     *
     * @return the HTTP transport
     */
    public HttpTransport getTransport() {
        return transport;
    }
}
