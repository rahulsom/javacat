package io.github.pulpogato.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class CachingExchangeFilterFunctionTest {

    private static final String TEST_URL = "https://api.example.com/data";
    private static final String CACHE_KEY = "GET api.example.com /data";
    private static final long CURRENT_TIME = 1_000_000L;
    private static final byte[] RESPONSE_BODY = "test response".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, List<String>> DEFAULT_HEADERS =
            Map.of("Content-Type", List.of("application/json"));

    @Mock
    private Cache cache;

    @Mock
    private CacheKeyMapper cacheKeyMapper;

    @Mock
    private Clock clock;

    @Mock
    private ExchangeFunction exchangeFunction;

    private CachingExchangeFilterFunction filter;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        filter = CachingExchangeFilterFunction.builder()
                .cache(cache)
                .cacheKeyMapper(cacheKeyMapper)
                .clock(clock)
                .build();
        bufferFactory = new DefaultDataBufferFactory();
    }

    private ClientRequest createGetRequest() {
        return ClientRequest.create(HttpMethod.GET, URI.create(TEST_URL)).build();
    }

    private ClientRequest createPostRequest() {
        return ClientRequest.create(HttpMethod.POST, URI.create(TEST_URL)).build();
    }

    private ClientResponse createResponse(String etag, String lastModified, String cacheControl) {
        var builder = ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        if (etag != null) {
            builder.header("ETag", etag);
        }
        if (lastModified != null) {
            builder.header("Last-Modified", lastModified);
        }
        if (cacheControl != null) {
            builder.header("Cache-Control", cacheControl);
        }
        builder.body(Flux.just(bufferFactory.wrap(RESPONSE_BODY)));
        return builder.build();
    }

    private ClientResponse create304Response() {
        return ClientResponse.create(HttpStatus.NOT_MODIFIED).build();
    }

    @Nested
    @DisplayName("Non-GET requests")
    class NonGetRequests {

        @Test
        @DisplayName("POST requests bypass cache")
        void postRequestsBypassCache() {
            var request = createPostRequest();
            var response = createResponse(null, null, null);
            when(exchangeFunction.exchange(request)).thenReturn(Mono.just(response));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isEqualTo(response);
            verify(cache, never()).get(any(), eq(CachedResponse.class));
            verify(cache, never()).put(any(), any());
        }
    }

    @Nested
    @DisplayName("Cache miss")
    class CacheMiss {

        @Test
        @DisplayName("First request with ETag gets cached and returns MISS header")
        void firstRequestWithEtagGetsCached() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = createResponse("\"abc123\"", null, null);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            assertThat(result.headers().header(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("MISS");

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getEtag()).isEqualTo("\"abc123\"");
        }

        @Test
        @DisplayName("First request with Last-Modified gets cached")
        void firstRequestWithLastModifiedGetsCached() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = createResponse(null, "Wed, 21 Oct 2015 07:28:00 GMT", null);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getLastModified()).isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT");
        }

        @Test
        @DisplayName("First request with Cache-Control max-age gets cached")
        void firstRequestWithMaxAgeGetsCached() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = createResponse(null, null, "private, max-age=60");
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getMaxAgeSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("Cached response preserves Content-Type header")
        void cachedResponsePreservesContentType() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = createResponse("\"abc123\"", null, null);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getHeaders()).containsKey("Content-Type");
            assertThat(captor.getValue().getHeaders().get("Content-Type")).contains("application/json");
        }
    }

    @Nested
    @DisplayName("Cache hit")
    class CacheHit {

        @Test
        @DisplayName("Fresh cached response returns HIT without server request")
        void freshCachedResponseReturnsHit() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var cachedResponse =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(cachedResponse);

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            assertThat(result.headers().header(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("HIT");
            assertThat(result.statusCode()).isEqualTo(HttpStatus.OK);
            verify(exchangeFunction, never()).exchange(any());
        }

        @Test
        @DisplayName("Fresh cached response preserves Content-Type header")
        void freshCachedResponsePreservesContentType() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var headers = Map.of("Content-Type", List.of("application/json"), "X-Custom", List.of("value"));
            var cachedResponse = new CachedResponse(RESPONSE_BODY, headers, "\"abc123\"", null, 60, CURRENT_TIME);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(cachedResponse);

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            assertThat(result.headers().contentType()).hasValue(MediaType.APPLICATION_JSON);
            assertThat(result.headers().header("X-Custom")).containsExactly("value");
        }
    }

    @Nested
    @DisplayName("Cache revalidation")
    class CacheRevalidation {

        @Test
        @DisplayName("Stale entry with ETag sends If-None-Match header")
        void staleEntryWithEtagSendsIfNoneMatch() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var staleCache =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 1, CURRENT_TIME - 10000);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(create304Response()));

            filter.filter(request, exchangeFunction).block();

            var captor = ArgumentCaptor.forClass(ClientRequest.class);
            verify(exchangeFunction).exchange(captor.capture());
            assertThat(captor.getValue().headers().getFirst("If-None-Match")).isEqualTo("\"abc123\"");
        }

        @Test
        @DisplayName("Stale entry with Last-Modified sends If-Modified-Since header")
        void staleEntryWithLastModifiedSendsIfModifiedSince() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var staleCache = new CachedResponse(
                    RESPONSE_BODY, DEFAULT_HEADERS, null, "Wed, 21 Oct 2015 07:28:00 GMT", 1, CURRENT_TIME - 10000);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(create304Response()));

            filter.filter(request, exchangeFunction).block();

            var captor = ArgumentCaptor.forClass(ClientRequest.class);
            verify(exchangeFunction).exchange(captor.capture());
            assertThat(captor.getValue().headers().getFirst("If-Modified-Since"))
                    .isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT");
        }

        @Test
        @DisplayName("304 response returns cached body with REVALIDATED header")
        void notModifiedReturnsCachedBodyWithRevalidatedHeader() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var staleCache =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 1, CURRENT_TIME - 10000);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(create304Response()));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            assertThat(result.headers().header(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("REVALIDATED");
            assertThat(result.statusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("304 response preserves Content-Type from cached entry")
        void notModifiedPreservesContentType() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var headers = Map.of("Content-Type", List.of("application/json"));
            var staleCache = new CachedResponse(RESPONSE_BODY, headers, "\"abc123\"", null, 1, CURRENT_TIME - 10000);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(create304Response()));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            assertThat(result.headers().contentType()).hasValue(MediaType.APPLICATION_JSON);
        }

        @Test
        @DisplayName("200 response after revalidation invalidates and updates cache")
        void okResponseAfterRevalidationUpdatesCache() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var staleCache = new CachedResponse(
                    "old data".getBytes(), DEFAULT_HEADERS, "\"old\"", null, 1, CURRENT_TIME - 10000);
            var newResponse = createResponse("\"new\"", null, null);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(newResponse));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            assertThat(result.headers().header(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("INVALIDATED");

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getEtag()).isEqualTo("\"new\"");
        }

        @Test
        @DisplayName("304 restarts the freshness lifetime and writes the entry back")
        void notModifiedRestartsFreshness() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var staleCache =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME - 100_000);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(create304Response()));

            filter.filter(request, exchangeFunction).block();

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            var refreshed = captor.getValue();
            // Freshness is stamped from now, so the entry is fresh again against its max-age.
            assertThat(refreshed.getCachedAtMillis()).isEqualTo(CURRENT_TIME);
            assertThat(refreshed.isExpired(CURRENT_TIME)).isFalse();
            // Freshness metadata absent from the stored headers is carried forward, not discarded.
            assertThat(refreshed.getEtag()).isEqualTo("\"abc123\"");
            assertThat(refreshed.getMaxAgeSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("304 header fields replace the corresponding stored fields")
        void notModifiedReplacesStoredHeaders() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var storedHeaders = Map.of(
                    "Content-Type", List.of("application/json"),
                    "ETag", List.of("\"v1\""),
                    "Cache-Control", List.of("max-age=30"));
            var staleCache =
                    new CachedResponse(RESPONSE_BODY, storedHeaders, "\"v1\"", null, 30, CURRENT_TIME - 100_000);
            var notModified = ClientResponse.create(HttpStatus.NOT_MODIFIED)
                    .header("ETag", "\"v2\"")
                    .header("Cache-Control", "max-age=120")
                    .build();
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(notModified));

            var result = filter.filter(request, exchangeFunction).block();

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            var refreshed = captor.getValue();
            assertThat(refreshed.getEtag()).isEqualTo("\"v2\"");
            assertThat(refreshed.getMaxAgeSeconds()).isEqualTo(120);
            assertThat(refreshed.getHeaders().get("ETag")).containsExactly("\"v2\"");
            assertThat(refreshed.getHeaders().get("Cache-Control")).containsExactly("max-age=120");
            // Headers not present in the 304 are retained from the stored entry.
            assertThat(refreshed.getHeaders().get("Content-Type")).containsExactly("application/json");
            // The refreshed headers are also what the caller sees.
            assertThat(result).isNotNull();
            assertThat(result.headers().header("ETag")).containsExactly("\"v2\"");
        }

        @Test
        @DisplayName("304 Content-Length does not overwrite the stored representation's length")
        void notModifiedContentLengthDoesNotClobberStored() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var bodyLength = String.valueOf(RESPONSE_BODY.length);
            var storedHeaders = Map.of(
                    "Content-Type", List.of("application/json"),
                    "Content-Length", List.of(bodyLength),
                    "ETag", List.of("\"abc123\""));
            var staleCache =
                    new CachedResponse(RESPONSE_BODY, storedHeaders, "\"abc123\"", null, 60, CURRENT_TIME - 100_000);
            // Some servers send Content-Length: 0 on a bodyless 304; it must not corrupt the entry.
            var notModified = ClientResponse.create(HttpStatus.NOT_MODIFIED)
                    .header("Content-Length", "0")
                    .build();
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(notModified));

            filter.filter(request, exchangeFunction).block();

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getHeaders().get("Content-Length")).containsExactly(bodyLength);
        }
    }

    @Nested
    @DisplayName("Responses not cached")
    class ResponsesNotCached {

        @Test
        @DisplayName("Response without caching headers is not cached")
        void responseWithoutCachingHeadersNotCached() {
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(Flux.just(bufferFactory.wrap(RESPONSE_BODY)))
                    .build();
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            verify(cache, never()).put(any(), any());
        }

        @Test
        @DisplayName("4xx response is not cached")
        void clientErrorNotCached() {
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = ClientResponse.create(HttpStatus.NOT_FOUND)
                    .header("ETag", "\"abc\"")
                    .body(Flux.just(bufferFactory.wrap("not found".getBytes())))
                    .build();
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            assertThat(result.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(cache, never()).put(any(), any());
        }

        @Test
        @DisplayName("5xx response is not cached")
        void serverErrorNotCached() {
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("ETag", "\"abc\"")
                    .body(Flux.just(bufferFactory.wrap("error".getBytes())))
                    .build();
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            assertThat(result.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            verify(cache, never()).put(any(), any());
        }
    }

    @Nested
    @DisplayName("Always revalidate mode")
    class AlwaysRevalidateMode {

        @Test
        @DisplayName("Fresh cached response still triggers revalidation when alwaysRevalidate is true")
        void freshCacheTriggersRevalidationWhenAlwaysRevalidateEnabled() {
            var revalidatingFilter = CachingExchangeFilterFunction.builder()
                    .cache(cache)
                    .cacheKeyMapper(cacheKeyMapper)
                    .clock(clock)
                    .alwaysRevalidate(true)
                    .build();

            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            // Fresh cache entry (not expired)
            var cachedResponse =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(cachedResponse);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(create304Response()));

            var result = revalidatingFilter.filter(request, exchangeFunction).block();

            // Should have made a server request with conditional header
            var captor = ArgumentCaptor.forClass(ClientRequest.class);
            verify(exchangeFunction).exchange(captor.capture());
            assertThat(captor.getValue().headers().getFirst("If-None-Match")).isEqualTo("\"abc123\"");

            // Should return REVALIDATED since server returned 304
            assertThat(result).isNotNull();
            assertThat(result.headers().header(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("REVALIDATED");
        }

        @Test
        @DisplayName("Fresh cached response returns HIT when alwaysRevalidate is false (default)")
        void freshCacheReturnsHitWhenAlwaysRevalidateDisabled() {
            // Using default filter which has alwaysRevalidate=false
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var cachedResponse =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(cachedResponse);

            var result = filter.filter(request, exchangeFunction).block();

            // Should NOT have made a server request
            verify(exchangeFunction, never()).exchange(any());

            // Should return HIT directly
            assertThat(result).isNotNull();
            assertThat(result.headers().header(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("HIT");
        }
    }

    @Nested
    @DisplayName("Large body handling")
    class LargeBodyHandling {

        @Test
        @DisplayName("Response body larger than 256KB but under 2MB limit is cached successfully")
        void largeBodyUnderLimitIsCachedSuccessfully() {
            // Create a body larger than Spring's default 256KB (262144 bytes) buffer limit
            // but under our 2MB default max cacheable size
            var largeBody = new byte[300_000];
            for (int i = 0; i < largeBody.length; i++) {
                largeBody[i] = (byte) (i % 256);
            }

            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("ETag", "\"large-body\"")
                    .body(Flux.just(bufferFactory.wrap(largeBody)))
                    .build();
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            var result = filter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            assertThat(result.headers().header(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("MISS");

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getBody()).hasSize(300_000);
            assertThat(captor.getValue().getEtag()).isEqualTo("\"large-body\"");
        }

        @Test
        @DisplayName("Response body exceeding max cacheable size returns SKIP and is not cached")
        void bodyExceedingLimitReturnsSkip() {
            // Use a small limit for testing
            var smallLimitFilter = CachingExchangeFilterFunction.builder()
                    .cache(cache)
                    .cacheKeyMapper(cacheKeyMapper)
                    .maxCacheableSize(1000)
                    .build();
            var largeBody = new byte[2000];
            for (int i = 0; i < largeBody.length; i++) {
                largeBody[i] = (byte) (i % 256);
            }

            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("ETag", "\"too-large\"")
                    .body(Flux.just(bufferFactory.wrap(largeBody)))
                    .build();
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            var result = smallLimitFilter.filter(request, exchangeFunction).block();

            assertThat(result).isNotNull();
            assertThat(result.headers().header(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("SKIP");
            assertThat(result.statusCode()).isEqualTo(HttpStatus.OK);

            // Verify body is still returned correctly
            var bodyContent = result.bodyToMono(byte[].class).block();
            assertThat(bodyContent).hasSize(2000);

            // Verify cache was NOT called
            verify(cache, never()).put(any(), any());
        }

        @Test
        @DisplayName("Response with Content-Length exceeding limit skips caching immediately")
        void contentLengthExceedingLimitSkipsEarly() {
            var smallLimitFilter = CachingExchangeFilterFunction.builder()
                    .cache(cache)
                    .cacheKeyMapper(cacheKeyMapper)
                    .clock(clock)
                    .maxCacheableSize(1000)
                    .build();
            var largeBody = new byte[2000];

            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Content-Length", "2000")
                    .header("ETag", "\"too-large\"")
                    .body(Flux.just(bufferFactory.wrap(largeBody)))
                    .build();
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            var result = smallLimitFilter.filter(request, exchangeFunction).block();

            // Response is returned as-is (not wrapped), so no X-Pulpogato-Cache header
            assertThat(result).isNotNull();
            assertThat(result.statusCode()).isEqualTo(HttpStatus.OK);
            verify(cache, never()).put(any(), any());
        }

        @Test
        @DisplayName("Custom max cacheable size is respected")
        void customMaxCacheableSizeIsRespected() {
            var customFilter = CachingExchangeFilterFunction.builder()
                    .cache(cache)
                    .cacheKeyMapper(cacheKeyMapper)
                    .clock(clock)
                    .maxCacheableSize(500)
                    .build();
            var smallBody = new byte[400]; // Under limit
            var largeBody = new byte[600]; // Over limit

            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);

            // Test small body - should be cached
            var request1 = createGetRequest();
            var response1 = ClientResponse.create(HttpStatus.OK)
                    .header("ETag", "\"small\"")
                    .body(Flux.just(bufferFactory.wrap(smallBody)))
                    .build();
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response1));

            var result1 = customFilter.filter(request1, exchangeFunction).block();
            assertThat(result1).isNotNull();
            assertThat(result1.headers().header(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("MISS");
            verify(cache).put(eq(CACHE_KEY), any(CachedResponse.class));

            // Reset mocks for second test
            org.mockito.Mockito.reset(cache, exchangeFunction);

            // Test large body - should skip caching
            var request2 = createGetRequest();
            var response2 = ClientResponse.create(HttpStatus.OK)
                    .header("ETag", "\"large\"")
                    .body(Flux.just(bufferFactory.wrap(largeBody)))
                    .build();
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response2));

            var result2 = customFilter.filter(request2, exchangeFunction).block();
            assertThat(result2).isNotNull();
            assertThat(result2.headers().header(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("SKIP");
            verify(cache, never()).put(any(), any());
        }
    }

    @Nested
    @DisplayName("Observations")
    class Observations {

        private static final String CACHE_GET = "pulpogato.cache.get";
        private static final String CACHE_PUT = "pulpogato.cache.put";

        private final TestObservationRegistry observationRegistry = TestObservationRegistry.create();

        private CachingExchangeFilterFunction observedFilter(int maxCacheableSize) {
            return CachingExchangeFilterFunction.builder()
                    .cache(cache)
                    .cacheKeyMapper(cacheKeyMapper)
                    .clock(clock)
                    .maxCacheableSize(maxCacheableSize)
                    .observationRegistry(observationRegistry)
                    .build();
        }

        @Test
        @DisplayName("Cache miss records a cache.get MISS span and a cache.put STORED span")
        void missRecordsLookupAndStore() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class)))
                    .thenReturn(Mono.just(createResponse("\"abc123\"", null, null)));

            observedFilter(CachingExchangeFilterFunction.DEFAULT_MAX_CACHEABLE_SIZE)
                    .filter(createGetRequest(), exchangeFunction)
                    .block();

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_GET)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_MISS)
                    .hasLowCardinalityKeyValue("cache.name", "unknown")
                    .hasLowCardinalityKeyValue("cache.client", "WebClient")
                    .hasLowCardinalityKeyValue("server.address", "api.example.com")
                    .hasHighCardinalityKeyValue("uri", TEST_URL)
                    .hasHighCardinalityKeyValue("cache.key", CACHE_KEY)
                    .backToTestObservationRegistry()
                    .hasObservationWithNameEqualTo(CACHE_PUT)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_STORED)
                    .hasHighCardinalityKeyValue("uri", TEST_URL)
                    .hasHighCardinalityKeyValue("cache.key", CACHE_KEY);
        }

        @Test
        @DisplayName("Fresh cache hit records a cache.get HIT span and no cache.put span")
        void hitRecordsLookupOnly() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class))
                    .thenReturn(
                            new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME));

            observedFilter(CachingExchangeFilterFunction.DEFAULT_MAX_CACHEABLE_SIZE)
                    .filter(createGetRequest(), exchangeFunction)
                    .block();

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_GET)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_HIT)
                    .backToTestObservationRegistry()
                    .hasNumberOfObservationsWithNameEqualTo(CACHE_PUT, 0);
        }

        @Test
        @DisplayName("Stale entry revalidated with 304 records a cache.get STALE span and a cache.put REVALIDATED span")
        void revalidationRecordsStaleLookupAndRefresh() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            // Cached long enough ago to be expired against its 60s max-age.
            when(cache.get(CACHE_KEY, CachedResponse.class))
                    .thenReturn(new CachedResponse(
                            RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME - 100_000));
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(create304Response()));

            observedFilter(CachingExchangeFilterFunction.DEFAULT_MAX_CACHEABLE_SIZE)
                    .filter(createGetRequest(), exchangeFunction)
                    .block();

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_GET)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_STALE)
                    .backToTestObservationRegistry()
                    .hasObservationWithNameEqualTo(CACHE_PUT)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_REVALIDATED)
                    .hasHighCardinalityKeyValue("uri", TEST_URL)
                    .hasHighCardinalityKeyValue("cache.key", CACHE_KEY);
        }

        @Test
        @DisplayName("Stale entry that gets a fresh 200 records a cache.put INVALIDATED span")
        void invalidationRecordsStaleLookupAndReplace() {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            // Cached long enough ago to be expired against its 60s max-age.
            when(cache.get(CACHE_KEY, CachedResponse.class))
                    .thenReturn(new CachedResponse(
                            RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME - 100_000));
            when(exchangeFunction.exchange(any(ClientRequest.class)))
                    .thenReturn(Mono.just(createResponse("\"new-etag\"", null, null)));

            observedFilter(CachingExchangeFilterFunction.DEFAULT_MAX_CACHEABLE_SIZE)
                    .filter(createGetRequest(), exchangeFunction)
                    .block();

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_GET)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_STALE)
                    .backToTestObservationRegistry()
                    .hasObservationWithNameEqualTo(CACHE_PUT)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_INVALIDATED)
                    .hasHighCardinalityKeyValue("uri", TEST_URL)
                    .hasHighCardinalityKeyValue("cache.key", CACHE_KEY);
        }

        @Test
        @DisplayName("Response too large records a cache.put SKIP span")
        void oversizedResponseRecordsSkipStore() {
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            var largeBody = new byte[2000];
            var response = ClientResponse.create(HttpStatus.OK)
                    .header("ETag", "\"too-large\"")
                    .body(Flux.just(bufferFactory.wrap(largeBody)))
                    .build();
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            observedFilter(1000).filter(createGetRequest(), exchangeFunction).block();

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_PUT)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_SKIP)
                    .hasLowCardinalityKeyValue("cache.skip.reason", HttpCacheEngine.SKIP_BODY_SIZE);
        }

        @Test
        @DisplayName("Known oversized response records its cache.put SKIP reason")
        void knownOversizedResponseRecordsSkipStore() {
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            var response = ClientResponse.create(HttpStatus.OK)
                    .header("ETag", "\"too-large\"")
                    .header("Content-Length", "2000")
                    .body(Flux.just(bufferFactory.wrap(new byte[2000])))
                    .build();
            when(exchangeFunction.exchange(any(ClientRequest.class))).thenReturn(Mono.just(response));

            observedFilter(1000).filter(createGetRequest(), exchangeFunction).block();

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_PUT)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_SKIP)
                    .hasLowCardinalityKeyValue("cache.skip.reason", HttpCacheEngine.SKIP_CONTENT_LENGTH);
        }

        @Test
        @DisplayName("Response without cache headers records its cache.put SKIP reason")
        void responseWithoutCacheHeadersRecordsSkipStore() {
            when(cacheKeyMapper.apply(any(ClientRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(exchangeFunction.exchange(any(ClientRequest.class)))
                    .thenReturn(Mono.just(createResponse(null, null, null)));

            observedFilter(CachingExchangeFilterFunction.DEFAULT_MAX_CACHEABLE_SIZE)
                    .filter(createGetRequest(), exchangeFunction)
                    .block();

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_PUT)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_SKIP)
                    .hasLowCardinalityKeyValue("cache.skip.reason", HttpCacheEngine.SKIP_NO_CACHE_HEADERS);
        }
    }
}
