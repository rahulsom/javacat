package io.github.pulpogato.common.cache;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;

/**
 * Framework-agnostic RFC 9111 conditional-caching engine shared by
 * {@link CachingExchangeFilterFunction} and {@link CachingClientHttpRequestInterceptor}.
 *
 * <p>Holds the cache read/write logic and its {@code pulpogato.cache.get}/{@code pulpogato.cache.put}
 * observations. Callers remain responsible for anything shaped by the underlying HTTP client
 * (building the conditional request, buffering the response body), and pass this engine only
 * plain types (cache key, URI string, header map) so it depends on neither
 * {@code org.springframework.web.reactive.function.client} nor {@code org.springframework.http.client}.
 */
@RequiredArgsConstructor
class HttpCacheEngine {

    static final String CACHE_HEADER_NAME = "X-Pulpogato-Cache";
    static final String CACHE_STATUS = "cache.status";
    static final String CACHE_HIT = "HIT";
    static final String CACHE_REVALIDATED = "REVALIDATED";
    static final String CACHE_STALE = "STALE";
    static final String CACHE_MISS = "MISS";
    static final String CACHE_STORED = "STORED";
    static final String CACHE_INVALIDATED = "INVALIDATED";
    static final String CACHE_SKIP = "SKIP";
    static final String CACHE_UNKNOWN = "UNKNOWN";
    static final String SKIP_NO_CACHE_HEADERS = "NO_CACHE_HEADERS";
    static final String SKIP_CONTENT_LENGTH = "CONTENT_LENGTH";
    static final String SKIP_BODY_SIZE = "BODY_SIZE";
    static final String SKIP_NONE = "NONE";

    private static final Pattern MAX_AGE_PATTERN = Pattern.compile("max-age=(\\d+)");
    private static final String OBSERVATION_CACHE_GET = "pulpogato.cache.get";
    private static final String OBSERVATION_CACHE_PUT = "pulpogato.cache.put";
    private static final String URI_TAG = "uri";
    private static final String CACHE_KEY = "cache.key";
    private static final String CACHE_NAME = "cache.name";
    private static final String CACHE_CLIENT = "cache.client";
    private static final String CACHE_SKIP_REASON = "cache.skip.reason";
    private static final String SERVER_ADDRESS = "server.address";
    private static final String UNKNOWN = "unknown";
    private static final String CONTENT_LENGTH = "Content-Length";
    private static final String ETAG = "ETag";
    private static final String LAST_MODIFIED = "Last-Modified";
    private static final String CACHE_CONTROL = "Cache-Control";

    private final Cache cache;
    private final Clock clock;
    private final ObservationRegistry observationRegistry;
    private final int maxCacheableSize;
    private final boolean alwaysRevalidate;
    private final String clientType;

    /**
     * Reads the cache entry inside a {@code pulpogato.cache.get} span, tagging the outcome
     * ({@link #CACHE_HIT}/{@link #CACHE_STALE}/{@link #CACHE_MISS}, or {@link #CACHE_UNKNOWN} when
     * the backend throws). The span wraps only the read, so it captures the cache backend's lookup
     * latency without enclosing the network exchange.
     */
    @Nullable
    CachedResponse lookup(String cacheKey, String uri, @Nullable Observation parent) {
        var observation = createObservation(OBSERVATION_CACHE_GET, cacheKey, uri, parent)
                .lowCardinalityKeyValue(CACHE_STATUS, CACHE_UNKNOWN);
        return observation.observe(() -> {
            var cached = cache.get(cacheKey, CachedResponse.class);
            observation.lowCardinalityKeyValue(CACHE_STATUS, computeStatus(cached));
            return cached;
        });
    }

    private String computeStatus(@Nullable CachedResponse cached) {
        if (cached == null) {
            return CACHE_MISS;
        } else if (cached.isExpired(clock.millis()) || alwaysRevalidate) {
            return CACHE_STALE;
        } else {
            return CACHE_HIT;
        }
    }

    /**
     * Applies a 304 Not Modified to the stored entry per
     * <a href="https://www.rfc-editor.org/rfc/rfc9111#section-4.3.4">RFC 9111 section 4.3.4</a>:
     * header fields from the 304 replace the corresponding stored fields, and the freshness lifetime
     * restarts from now so the entry can serve fresh hits again instead of revalidating on every
     * subsequent request. The refreshed entry is written back to the cache inside a
     * {@code pulpogato.cache.put} span.
     */
    CachedResponse refreshFromNotModified(
            String cacheKey,
            String uri,
            CachedResponse cached,
            Map<String, List<String>> notModifiedHeaders,
            @Nullable Observation parent) {
        var merged = mergeHeaders(cached.getHeaders(), notModifiedHeaders);

        // Recompute caching metadata from the merged headers so any refreshed ETag, Last-Modified, or
        // Cache-Control the server sent on the 304 is reflected. When the 304 omits a header, the
        // merged headers retain the stored value; if even that is absent, fall back to the entry's
        // existing metadata so revalidation never discards known freshness information. A header is
        // absent exactly when getFirst returns null, so a present-but-updated value still wins.
        var mergedCacheControl = getFirst(merged, CACHE_CONTROL);
        var mergedEtag = getFirst(merged, ETAG);
        var mergedLastModified = getFirst(merged, LAST_MODIFIED);
        var etag = mergedEtag != null ? mergedEtag : cached.getEtag();
        var lastModified = mergedLastModified != null ? mergedLastModified : cached.getLastModified();
        var parsedMaxAge = mergedCacheControl != null ? parseMaxAge(mergedCacheControl) : -1;
        var maxAge = parsedMaxAge >= 0 ? parsedMaxAge : cached.getMaxAgeSeconds();

        var refreshed = new CachedResponse(cached.getBody(), merged, etag, lastModified, maxAge, clock.millis());
        recordPut(cacheKey, uri, CACHE_REVALIDATED, () -> cache.put(cacheKey, refreshed), parent);
        return refreshed;
    }

    /**
     * Records a {@code pulpogato.cache.put} span for the store phase, tagging it with the outcome
     * ({@link #CACHE_STORED} for a cold miss, {@link #CACHE_REVALIDATED} when a stale entry's 304
     * confirmed it was still valid, {@link #CACHE_INVALIDATED} when a stale entry's revalidation
     * request instead got back a fresh 200, or {@link #CACHE_SKIP}). When {@code store} is non-null
     * it runs inside the span so the span captures the write latency of the cache backend; a null
     * {@code store} records a zero-work span documenting that the response was not cached. Every put
     * has a {@code cache.skip.reason}; writes use {@link #SKIP_NONE}, while skipped writes identify why.
     */
    void recordPut(String cacheKey, String uri, String status, @Nullable Runnable store, @Nullable Observation parent) {
        recordPut(cacheKey, uri, status, null, store, parent);
    }

    void recordSkip(String cacheKey, String uri, String reason, @Nullable Observation parent) {
        recordPut(cacheKey, uri, CACHE_SKIP, reason, null, parent);
    }

    private void recordPut(
            String cacheKey,
            String uri,
            String status,
            @Nullable String skipReason,
            @Nullable Runnable store,
            @Nullable Observation parent) {
        var observation = createObservation(OBSERVATION_CACHE_PUT, cacheKey, uri, parent)
                .lowCardinalityKeyValue(CACHE_STATUS, status)
                .lowCardinalityKeyValue(CACHE_SKIP_REASON, skipReason != null ? skipReason : SKIP_NONE);
        observation.observe(store != null ? store : () -> {});
    }

    /**
     * Whether a response with the given caching headers and content length is eligible to be
     * cached: it must carry at least one of ETag, Last-Modified, or a max-age directive, and its
     * (known) length must not exceed {@link #maxCacheableSize}. An unknown content length (-1)
     * never disqualifies a response on its own; the buffered body length is checked separately via
     * {@link #exceedsMaxCacheableSize(long)} once it's known.
     */
    boolean shouldCache(@Nullable String etag, @Nullable String lastModified, long maxAge, long contentLength) {
        return skipReason(etag, lastModified, maxAge, contentLength) == null;
    }

    @Nullable
    String skipReason(@Nullable String etag, @Nullable String lastModified, long maxAge, long contentLength) {
        if (etag == null && lastModified == null && maxAge < 0) {
            return SKIP_NO_CACHE_HEADERS;
        }
        return exceedsMaxCacheableSize(contentLength) ? SKIP_CONTENT_LENGTH : null;
    }

    boolean exceedsMaxCacheableSize(long size) {
        return size > maxCacheableSize;
    }

    private static Map<String, List<String>> mergeHeaders(
            Map<String, List<String>> stored, Map<String, List<String>> update) {
        // A case-insensitive TreeMap replicates the overlay behavior org.springframework.http.HttpHeaders
        // gave us for free, without depending on that type.
        var merged = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        stored.forEach((name, values) -> merged.put(name, new ArrayList<>(values)));
        update.forEach((name, values) -> {
            // A 304 Not Modified response doesn't carry a body, so a Content-Length it sends
            // (often 0) must not overwrite the length of the stored representation we are about
            // to serve.
            if (!CONTENT_LENGTH.equalsIgnoreCase(name)) {
                merged.put(name, new ArrayList<>(values));
            }
        });
        return merged;
    }

    private static @Nullable String getFirst(Map<String, List<String>> headers, String name) {
        var values = headers.get(name);
        return values != null && !values.isEmpty() ? values.getFirst() : null;
    }

    static long parseMaxAge(@Nullable String cacheControl) {
        if (cacheControl != null) {
            var matcher = MAX_AGE_PATTERN.matcher(cacheControl);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }
        }
        return -1;
    }

    private String cacheName() {
        var name = cache.getName();
        return name != null ? name : UNKNOWN;
    }

    private static String serverAddress(String uri) {
        var host = URI.create(uri).getHost();
        return host != null ? host : UNKNOWN;
    }

    private Observation createObservation(String name, String cacheKey, String uri, @Nullable Observation parent) {
        var observation =
                Observation.createNotStarted(name, observationRegistry).parentObservation(parent);
        if (observation.isNoop()) {
            return observation;
        }
        return observation
                .lowCardinalityKeyValue(CACHE_NAME, cacheName())
                .lowCardinalityKeyValue(CACHE_CLIENT, clientType)
                .lowCardinalityKeyValue(SERVER_ADDRESS, serverAddress(uri))
                .highCardinalityKeyValue(URI_TAG, uri)
                .highCardinalityKeyValue(CACHE_KEY, cacheKey);
    }
}
