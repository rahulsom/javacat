package io.github.pulpogato.common.cache;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.support.NoOpCache;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * HTTP caching interceptor that implements conditional request handling.
 *
 * <p>This interceptor intercepts requests and responses to provide HTTP caching based on:
 *
 * <ul>
 *   <li>{@code ETag} / {@code If-None-Match}
 *   <li>{@code Last-Modified} / {@code If-Modified-Since}
 *   <li>{@code Cache-Control max-age}
 * </ul>
 *
 * <p>When a cached response exists and is still fresh (within max-age), it is returned directly.
 * When expired, conditional headers are added and on 304 Not Modified the cached response body
 * is returned. Per <a href="https://www.rfc-editor.org/rfc/rfc9111#section-4.3.4">RFC 9111
 * section 4.3.4</a>, a 304 also refreshes the stored entry: the header fields from the 304 replace
 * the corresponding stored fields and the freshness lifetime restarts, so a validated entry can
 * serve fresh hits again instead of revalidating on every subsequent request. If the server instead
 * returns a fresh 200 for a stale entry, the stored value is invalidated and replaced.
 *
 * <p>Responses larger than {@link #maxCacheableSize} are not cached but are still returned
 * successfully. This prevents memory issues with very large responses.
 *
 * <p>Example usage:
 * <pre>{@code
 * HttpCache cache = new InMemoryHttpCache();
 * RestClient restClient = RestClient.builder()
 *     .requestInterceptor(CachingClientHttpRequestInterceptor.builder().cache(cache).build())
 *     .build();
 * }</pre>
 *
 * <p>This is the {@link org.springframework.web.client.RestClient} equivalent of {@link CachingExchangeFilterFunction}.
 */
@Builder
public class CachingClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private static final String CLIENT_TYPE = "RestClient";

    /**
     * Default maximum size for cacheable responses (2MB).
     */
    public static final int DEFAULT_MAX_CACHEABLE_SIZE = 2 * 1024 * 1024;

    /**
     * The cache instance to store and retrieve cached responses.
     */
    @Builder.Default
    private final Cache cache = new NoOpCache("no-op-cache");

    /**
     * Function to map an {@link HttpRequest} to cache key strings.
     */
    @Builder.Default
    private final HttpRequestCacheKeyMapper cacheKeyMapper = new DefaultHttpRequestCacheKeyMapper();

    /**
     * Clock instance for time-based operations.
     */
    @Builder.Default
    private final Clock clock = Clock.systemUTC();

    /**
     * Maximum size in bytes for responses to be cached.
     * Responses larger than this will be returned but not cached.
     */
    @Builder.Default
    private final int maxCacheableSize = DEFAULT_MAX_CACHEABLE_SIZE;

    /**
     * When true, always send conditional requests to revalidate cached responses,
     * even if they haven't expired according to max-age. This is useful when
     * the data may change more frequently than the cache headers suggest.
     */
    @Builder.Default
    private final boolean alwaysRevalidate = false;

    /**
     * Observation registry for recording cache interaction spans.
     * Defaults to {@link ObservationRegistry#NOOP} which adds no overhead.
     */
    @Builder.Default
    private final ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

    @Getter(lazy = true)
    private final HttpCacheEngine engine = engine();

    private HttpCacheEngine engine() {
        return new HttpCacheEngine(cache, clock, observationRegistry, maxCacheableSize, alwaysRevalidate, CLIENT_TYPE);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        return switch (request.getMethod().name()) {
            case "GET", "QUERY" -> getResponseWithCache(request, body, execution);
            default -> execution.execute(request, body);
        };
    }

    private ClientHttpResponse getResponseWithCache(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        var parent = observationRegistry.getCurrentObservation();
        var cacheKey = cacheKeyMapper.apply(request);
        var uri = request.getURI().toString();
        var cached = getEngine().lookup(cacheKey, uri, parent);

        // If we have a fresh cached response and not forcing revalidation, return it
        if (cached != null && !cached.isExpired(clock.millis()) && !alwaysRevalidate) {
            return new BufferedClientHttpResponse(
                    HttpStatus.OK,
                    headersWith(cached.getHeaders(), HttpCacheEngine.CACHE_HEADER_NAME, HttpCacheEngine.CACHE_HIT),
                    cached.getBody());
        }

        // Add conditional headers if we have a cache entry (stale or forcing revalidation)
        if (cached != null && cached.canRevalidate()) {
            if (cached.getEtag() != null) {
                request.getHeaders().set("If-None-Match", cached.getEtag());
            }
            if (cached.getLastModified() != null) {
                request.getHeaders().set("If-Modified-Since", cached.getLastModified());
            }
        }

        var response = execution.execute(request, body);

        // On 304 the stored representation is still valid: refresh its freshness and merge the
        // updated header fields (RFC 9111 4.3.4), then serve the cached body.
        if (response.getStatusCode().value() == 304 && cached != null) {
            var refreshed =
                    getEngine().refreshFromNotModified(cacheKey, uri, cached, toMap(response.getHeaders()), parent);
            response.close();
            return new BufferedClientHttpResponse(
                    HttpStatus.OK,
                    headersWith(
                            refreshed.getHeaders(),
                            HttpCacheEngine.CACHE_HEADER_NAME,
                            HttpCacheEngine.CACHE_REVALIDATED),
                    refreshed.getBody());
        }

        // Cache the response if it has caching headers
        if (response.getStatusCode().is2xxSuccessful()) {
            return cacheResponse(cacheKey, uri, response, cached != null, parent);
        }

        return response;
    }

    private ClientHttpResponse cacheResponse(
            String cacheKey, String uri, ClientHttpResponse response, boolean wasCached, @Nullable Observation parent)
            throws IOException {
        var headers = response.getHeaders();
        var etag = headers.getETag();
        var lastModified = headers.getFirst("Last-Modified");
        var cacheControl = headers.getFirst("Cache-Control");

        var maxAge = HttpCacheEngine.parseMaxAge(cacheControl);

        // Only cache if there are caching headers, and the (known) length is within the limit
        var skipReason = getEngine().skipReason(etag, lastModified, maxAge, headers.getContentLength());
        if (skipReason != null) {
            getEngine().recordSkip(cacheKey, uri, skipReason, parent);
            return response;
        }

        // Copy headers to a plain Map for serialization.
        var headerMap = HashMap.<String, List<String>>newHashMap(headers.size());
        headers.forEach((key, values) -> headerMap.put(key, new ArrayList<>(values)));

        var statusCode = response.getStatusCode();
        byte[] responseBody;
        try (response;
                InputStream is = response.getBody()) {
            responseBody = is.readAllBytes();
        }

        // If the response is too large, skip caching but still return the data
        if (getEngine().exceedsMaxCacheableSize(responseBody.length)) {
            getEngine().recordSkip(cacheKey, uri, HttpCacheEngine.SKIP_BODY_SIZE, parent);
            return new BufferedClientHttpResponse(
                    statusCode,
                    headersWith(headerMap, HttpCacheEngine.CACHE_HEADER_NAME, HttpCacheEngine.CACHE_SKIP),
                    responseBody);
        }

        // Cache and return. A prior cached entry that reached here (rather than the 304 branch) means
        // the live API returned a fresh 200 for a stale lookup, i.e. it invalidated the stored value.
        var cachedResponse = new CachedResponse(responseBody, headerMap, etag, lastModified, maxAge, clock.millis());
        var putStatus = wasCached ? HttpCacheEngine.CACHE_INVALIDATED : HttpCacheEngine.CACHE_STORED;
        var headerStatus = wasCached ? HttpCacheEngine.CACHE_INVALIDATED : HttpCacheEngine.CACHE_MISS;
        getEngine().recordPut(cacheKey, uri, putStatus, () -> cache.put(cacheKey, cachedResponse), parent);

        return new BufferedClientHttpResponse(
                statusCode, headersWith(headerMap, HttpCacheEngine.CACHE_HEADER_NAME, headerStatus), responseBody);
    }

    private static Map<String, List<String>> toMap(HttpHeaders headers) {
        var map = HashMap.<String, List<String>>newHashMap(headers.size());
        headers.forEach((name, values) -> map.put(name, new ArrayList<>(values)));
        return map;
    }

    private static HttpHeaders headersWith(Map<String, List<String>> source, String extraName, String extraValue) {
        var headers = new HttpHeaders();
        source.forEach(headers::addAll);
        headers.set(extraName, extraValue);
        return headers;
    }

    @RequiredArgsConstructor
    @Getter
    private static final class BufferedClientHttpResponse implements ClientHttpResponse {

        private final HttpStatusCode statusCode;
        private final HttpHeaders headers;
        private final byte[] body;

        @Override
        public String getStatusText() {
            return statusCode instanceof HttpStatus httpStatus ? httpStatus.getReasonPhrase() : "";
        }

        @Override
        public void close() {
            // Nothing to release; the body is already fully buffered in memory.
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }
    }
}
