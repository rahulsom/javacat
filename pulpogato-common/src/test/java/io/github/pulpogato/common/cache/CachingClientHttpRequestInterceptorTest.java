package io.github.pulpogato.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("resource")
class CachingClientHttpRequestInterceptorTest {

    private static final String TEST_URL = "https://api.example.com/data";
    private static final String CACHE_KEY = "GET api.example.com /data";
    private static final long CURRENT_TIME = 1_000_000L;
    private static final byte[] RESPONSE_BODY = "test response".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, List<String>> DEFAULT_HEADERS =
            Map.of("Content-Type", List.of("application/json"));

    @Mock
    private Cache cache;

    @Mock
    private HttpRequestCacheKeyMapper cacheKeyMapper;

    @Mock
    private Clock clock;

    @Mock
    private ClientHttpRequestExecution execution;

    private CachingClientHttpRequestInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = CachingClientHttpRequestInterceptor.builder()
                .cache(cache)
                .cacheKeyMapper(cacheKeyMapper)
                .clock(clock)
                .build();
    }

    private static TestHttpRequest createGetRequest() {
        return new TestHttpRequest(HttpMethod.GET, URI.create(TEST_URL));
    }

    private static TestHttpRequest createPostRequest() {
        return new TestHttpRequest(HttpMethod.POST, URI.create(TEST_URL));
    }

    private static TestClientHttpResponse createResponse(String etag, String lastModified, String cacheControl) {
        var headers = new HttpHeaders();
        headers.set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        if (etag != null) {
            headers.set("ETag", etag);
        }
        if (lastModified != null) {
            headers.set("Last-Modified", lastModified);
        }
        if (cacheControl != null) {
            headers.set("Cache-Control", cacheControl);
        }
        return new TestClientHttpResponse(HttpStatus.OK, headers, RESPONSE_BODY);
    }

    private static TestClientHttpResponse create304Response() {
        return new TestClientHttpResponse(HttpStatus.NOT_MODIFIED, new HttpHeaders(), new byte[0]);
    }

    private static TestClientHttpResponse create304Response(HttpHeaders headers) {
        return new TestClientHttpResponse(HttpStatus.NOT_MODIFIED, headers, new byte[0]);
    }

    @RequiredArgsConstructor
    @NullMarked
    @Getter
    private static final class TestHttpRequest implements HttpRequest {
        private final HttpMethod method;
        private final HttpHeaders headers = new HttpHeaders();
        private final Map<String, Object> attributes = new HashMap<>();
        private final URI uri;

        @Override
        public URI getURI() {
            return uri;
        }
    }

    @RequiredArgsConstructor
    @NullMarked
    @Getter
    private static final class TestClientHttpResponse implements ClientHttpResponse {
        private final HttpStatusCode statusCode;
        private final HttpHeaders headers;
        private final byte[] body;

        @Override
        public String getStatusText() {
            return statusCode instanceof HttpStatus httpStatus ? httpStatus.getReasonPhrase() : "";
        }

        @Override
        public void close() {
            // no-op
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }
    }

    @Nested
    @DisplayName("Non-GET requests")
    class NonGetRequests {

        @Test
        @DisplayName("POST requests bypass cache")
        void postRequestsBypassCache() throws Exception {
            var request = createPostRequest();
            var response = createResponse(null, null, null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = interceptor.intercept(request, new byte[0], execution);

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
        void firstRequestWithEtagGetsCached() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = createResponse("\"abc123\"", null, null);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            assertThat(result.getHeaders().get(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("MISS");

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getEtag()).isEqualTo("\"abc123\"");
        }

        @Test
        @DisplayName("First request with Last-Modified gets cached")
        void firstRequestWithLastModifiedGetsCached() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = createResponse(null, "Wed, 21 Oct 2015 07:28:00 GMT", null);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getLastModified()).isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT");
        }

        @Test
        @DisplayName("First request with Cache-Control max-age gets cached")
        void firstRequestWithMaxAgeGetsCached() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = createResponse(null, null, "private, max-age=60");
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getMaxAgeSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("Cached response preserves Content-Type header")
        void cachedResponsePreservesContentType() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = createResponse("\"abc123\"", null, null);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = interceptor.intercept(request, new byte[0], execution);

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
        void freshCachedResponseReturnsHit() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var cachedResponse =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(cachedResponse);

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            assertThat(result.getHeaders().get(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("HIT");
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(execution, never()).execute(any(), any());
        }

        @Test
        @DisplayName("Fresh cached response preserves Content-Type header")
        void freshCachedResponsePreservesContentType() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var headers = Map.of("Content-Type", List.of("application/json"), "X-Custom", List.of("value"));
            var cachedResponse = new CachedResponse(RESPONSE_BODY, headers, "\"abc123\"", null, 60, CURRENT_TIME);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(cachedResponse);

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(result.getHeaders().get("X-Custom")).containsExactly("value");
        }
    }

    @Nested
    @DisplayName("Cache revalidation")
    class CacheRevalidation {

        @Test
        @DisplayName("Stale entry with ETag sends If-None-Match header")
        void staleEntryWithEtagSendsIfNoneMatch() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var staleCache =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 1, CURRENT_TIME - 10000);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(execution.execute(any(), any())).thenReturn(create304Response());

            interceptor.intercept(request, new byte[0], execution);

            assertThat(request.getHeaders().getFirst("If-None-Match")).isEqualTo("\"abc123\"");
        }

        @Test
        @DisplayName("Stale entry with Last-Modified sends If-Modified-Since header")
        void staleEntryWithLastModifiedSendsIfModifiedSince() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var staleCache = new CachedResponse(
                    RESPONSE_BODY, DEFAULT_HEADERS, null, "Wed, 21 Oct 2015 07:28:00 GMT", 1, CURRENT_TIME - 10000);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(execution.execute(any(), any())).thenReturn(create304Response());

            interceptor.intercept(request, new byte[0], execution);

            assertThat(request.getHeaders().getFirst("If-Modified-Since")).isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT");
        }

        @Test
        @DisplayName("304 response returns cached body with REVALIDATED header")
        void notModifiedReturnsCachedBodyWithRevalidatedHeader() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var staleCache =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 1, CURRENT_TIME - 10000);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(execution.execute(any(), any())).thenReturn(create304Response());

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            assertThat(result.getHeaders().get(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("REVALIDATED");
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("304 response preserves Content-Type from cached entry")
        void notModifiedPreservesContentType() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var headers = Map.of("Content-Type", List.of("application/json"));
            var staleCache = new CachedResponse(RESPONSE_BODY, headers, "\"abc123\"", null, 1, CURRENT_TIME - 10000);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(execution.execute(any(), any())).thenReturn(create304Response());

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        }

        @Test
        @DisplayName("200 response after revalidation invalidates and updates cache")
        void okResponseAfterRevalidationUpdatesCache() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var staleCache = new CachedResponse(
                    "old data".getBytes(StandardCharsets.UTF_8),
                    DEFAULT_HEADERS,
                    "\"old\"",
                    null,
                    1,
                    CURRENT_TIME - 10000);
            var newResponse = createResponse("\"new\"", null, null);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(execution.execute(any(), any())).thenReturn(newResponse);

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            assertThat(result.getHeaders().get(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("INVALIDATED");

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getEtag()).isEqualTo("\"new\"");
        }

        @Test
        @DisplayName("304 restarts the freshness lifetime and writes the entry back")
        void notModifiedRestartsFreshness() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var staleCache =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME - 100_000);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(execution.execute(any(), any())).thenReturn(create304Response());

            interceptor.intercept(request, new byte[0], execution);

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            var refreshed = captor.getValue();
            assertThat(refreshed.getCachedAtMillis()).isEqualTo(CURRENT_TIME);
            assertThat(refreshed.isExpired(CURRENT_TIME)).isFalse();
            assertThat(refreshed.getEtag()).isEqualTo("\"abc123\"");
            assertThat(refreshed.getMaxAgeSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("304 header fields replace the corresponding stored fields")
        void notModifiedReplacesStoredHeaders() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var storedHeaders = Map.of(
                    "Content-Type", List.of("application/json"),
                    "ETag", List.of("\"v1\""),
                    "Cache-Control", List.of("max-age=30"));
            var staleCache =
                    new CachedResponse(RESPONSE_BODY, storedHeaders, "\"v1\"", null, 30, CURRENT_TIME - 100_000);
            var notModifiedHeaders = new HttpHeaders();
            notModifiedHeaders.set("ETag", "\"v2\"");
            notModifiedHeaders.set("Cache-Control", "max-age=120");
            var notModified = create304Response(notModifiedHeaders);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(execution.execute(any(), any())).thenReturn(notModified);

            var result = interceptor.intercept(request, new byte[0], execution);

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            var refreshed = captor.getValue();
            assertThat(refreshed.getEtag()).isEqualTo("\"v2\"");
            assertThat(refreshed.getMaxAgeSeconds()).isEqualTo(120);
            assertThat(refreshed.getHeaders().get("ETag")).containsExactly("\"v2\"");
            assertThat(refreshed.getHeaders().get("Cache-Control")).containsExactly("max-age=120");
            assertThat(refreshed.getHeaders().get("Content-Type")).containsExactly("application/json");
            assertThat(result).isNotNull();
            assertThat(result.getHeaders().get("ETag")).containsExactly("\"v2\"");
        }

        @Test
        @DisplayName("304 Content-Length does not overwrite the stored representation's length")
        void notModifiedContentLengthDoesNotClobberStored() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var bodyLength = String.valueOf(RESPONSE_BODY.length);
            var storedHeaders = Map.of(
                    "Content-Type", List.of("application/json"),
                    "Content-Length", List.of(bodyLength),
                    "ETag", List.of("\"abc123\""));
            var staleCache =
                    new CachedResponse(RESPONSE_BODY, storedHeaders, "\"abc123\"", null, 60, CURRENT_TIME - 100_000);
            var notModifiedHeaders = new HttpHeaders();
            notModifiedHeaders.set("Content-Length", "0");
            var notModified = create304Response(notModifiedHeaders);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(staleCache);
            when(execution.execute(any(), any())).thenReturn(notModified);

            interceptor.intercept(request, new byte[0], execution);

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
        void responseWithoutCachingHeadersNotCached() throws Exception {
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var response = createResponse(null, null, null);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            verify(cache, never()).put(any(), any());
        }

        @Test
        @DisplayName("4xx response is not cached")
        void clientErrorNotCached() throws Exception {
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var headers = new HttpHeaders();
            headers.set("ETag", "\"abc\"");
            var response = new TestClientHttpResponse(
                    HttpStatus.NOT_FOUND, headers, "not found".getBytes(StandardCharsets.UTF_8));
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(cache, never()).put(any(), any());
        }

        @Test
        @DisplayName("5xx response is not cached")
        void serverErrorNotCached() throws Exception {
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var headers = new HttpHeaders();
            headers.set("ETag", "\"abc\"");
            var response = new TestClientHttpResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR, headers, "error".getBytes(StandardCharsets.UTF_8));
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            verify(cache, never()).put(any(), any());
        }
    }

    @Nested
    @DisplayName("Always revalidate mode")
    class AlwaysRevalidateMode {

        @Test
        @DisplayName("Fresh cached response still triggers revalidation when alwaysRevalidate is true")
        void freshCacheTriggersRevalidationWhenAlwaysRevalidateEnabled() throws Exception {
            var revalidatingInterceptor = CachingClientHttpRequestInterceptor.builder()
                    .cache(cache)
                    .cacheKeyMapper(cacheKeyMapper)
                    .clock(clock)
                    .alwaysRevalidate(true)
                    .build();

            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var cachedResponse =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(cachedResponse);
            when(execution.execute(any(), any())).thenReturn(create304Response());

            var result = revalidatingInterceptor.intercept(request, new byte[0], execution);

            assertThat(request.getHeaders().getFirst("If-None-Match")).isEqualTo("\"abc123\"");
            assertThat(result).isNotNull();
            assertThat(result.getHeaders().get(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("REVALIDATED");
        }

        @Test
        @DisplayName("Fresh cached response returns HIT when alwaysRevalidate is false (default)")
        void freshCacheReturnsHitWhenAlwaysRevalidateDisabled() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var cachedResponse =
                    new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(cachedResponse);

            var result = interceptor.intercept(request, new byte[0], execution);

            verify(execution, never()).execute(any(), any());
            assertThat(result).isNotNull();
            assertThat(result.getHeaders().get(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("HIT");
        }
    }

    @Nested
    @DisplayName("Large body handling")
    class LargeBodyHandling {

        @Test
        @DisplayName("Response body larger than 256KB but under 2MB limit is cached successfully")
        void largeBodyUnderLimitIsCachedSuccessfully() throws Exception {
            var largeBody = new byte[300_000];
            for (int i = 0; i < largeBody.length; i++) {
                largeBody[i] = (byte) (i % 256);
            }

            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var headers = new HttpHeaders();
            headers.set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            headers.set("ETag", "\"large-body\"");
            var response = new TestClientHttpResponse(HttpStatus.OK, headers, largeBody);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = interceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            assertThat(result.getHeaders().get(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("MISS");

            var captor = ArgumentCaptor.forClass(CachedResponse.class);
            verify(cache).put(eq(CACHE_KEY), captor.capture());
            assertThat(captor.getValue().getBody()).hasSize(300_000);
            assertThat(captor.getValue().getEtag()).isEqualTo("\"large-body\"");
        }

        @Test
        @DisplayName("Response body exceeding max cacheable size returns SKIP and is not cached")
        void bodyExceedingLimitReturnsSkip() throws Exception {
            var smallLimitInterceptor = CachingClientHttpRequestInterceptor.builder()
                    .cache(cache)
                    .cacheKeyMapper(cacheKeyMapper)
                    .maxCacheableSize(1000)
                    .build();
            var largeBody = new byte[2000];
            for (int i = 0; i < largeBody.length; i++) {
                largeBody[i] = (byte) (i % 256);
            }

            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var headers = new HttpHeaders();
            headers.set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            headers.set("ETag", "\"too-large\"");
            var response = new TestClientHttpResponse(HttpStatus.OK, headers, largeBody);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = smallLimitInterceptor.intercept(request, new byte[0], execution);

            assertThat(result).isNotNull();
            assertThat(result.getHeaders().get(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("SKIP");
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);

            var bodyContent = result.getBody().readAllBytes();
            assertThat(bodyContent).hasSize(2000);

            verify(cache, never()).put(any(), any());
        }

        @Test
        @DisplayName("Response with Content-Length exceeding limit skips caching immediately")
        void contentLengthExceedingLimitSkipsEarly() throws Exception {
            var smallLimitInterceptor = CachingClientHttpRequestInterceptor.builder()
                    .cache(cache)
                    .cacheKeyMapper(cacheKeyMapper)
                    .clock(clock)
                    .maxCacheableSize(1000)
                    .build();
            var largeBody = new byte[2000];

            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            var request = createGetRequest();
            var headers = new HttpHeaders();
            headers.set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            headers.set("Content-Length", "2000");
            headers.set("ETag", "\"too-large\"");
            var response = new TestClientHttpResponse(HttpStatus.OK, headers, largeBody);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response);

            var result = smallLimitInterceptor.intercept(request, new byte[0], execution);

            assertThat(result).isEqualTo(response);
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(cache, never()).put(any(), any());
        }

        @Test
        @DisplayName("Custom max cacheable size is respected")
        void customMaxCacheableSizeIsRespected() throws Exception {
            var customInterceptor = CachingClientHttpRequestInterceptor.builder()
                    .cache(cache)
                    .cacheKeyMapper(cacheKeyMapper)
                    .clock(clock)
                    .maxCacheableSize(500)
                    .build();
            var smallBody = new byte[400];
            var largeBody = new byte[600];

            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);

            var request1 = createGetRequest();
            var headers1 = new HttpHeaders();
            headers1.set("ETag", "\"small\"");
            var response1 = new TestClientHttpResponse(HttpStatus.OK, headers1, smallBody);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response1);

            var result1 = customInterceptor.intercept(request1, new byte[0], execution);
            assertThat(result1).isNotNull();
            assertThat(result1.getHeaders().get(HttpCacheEngine.CACHE_HEADER_NAME))
                    .containsExactly("MISS");
            verify(cache).put(eq(CACHE_KEY), any(CachedResponse.class));

            reset(cache, execution);

            var request2 = createGetRequest();
            var headers2 = new HttpHeaders();
            headers2.set("ETag", "\"large\"");
            var response2 = new TestClientHttpResponse(HttpStatus.OK, headers2, largeBody);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(response2);

            var result2 = customInterceptor.intercept(request2, new byte[0], execution);
            assertThat(result2).isNotNull();
            assertThat(result2.getHeaders().get(HttpCacheEngine.CACHE_HEADER_NAME))
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

        private CachingClientHttpRequestInterceptor observedInterceptor(int maxCacheableSize) {
            return CachingClientHttpRequestInterceptor.builder()
                    .cache(cache)
                    .cacheKeyMapper(cacheKeyMapper)
                    .clock(clock)
                    .maxCacheableSize(maxCacheableSize)
                    .observationRegistry(observationRegistry)
                    .build();
        }

        @Test
        @DisplayName("Cache miss records a cache.get MISS span and a cache.put STORED span")
        void missRecordsLookupAndStore() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(createResponse("\"abc123\"", null, null));

            observedInterceptor(CachingClientHttpRequestInterceptor.DEFAULT_MAX_CACHEABLE_SIZE)
                    .intercept(createGetRequest(), new byte[0], execution);

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_GET)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_MISS)
                    .hasLowCardinalityKeyValue("cache.name", "unknown")
                    .hasLowCardinalityKeyValue("cache.client", "RestClient")
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
        void hitRecordsLookupOnly() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class))
                    .thenReturn(
                            new CachedResponse(RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME));

            observedInterceptor(CachingClientHttpRequestInterceptor.DEFAULT_MAX_CACHEABLE_SIZE)
                    .intercept(createGetRequest(), new byte[0], execution);

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_GET)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_HIT)
                    .backToTestObservationRegistry()
                    .hasNumberOfObservationsWithNameEqualTo(CACHE_PUT, 0);
        }

        @Test
        @DisplayName("Stale entry revalidated with 304 records a cache.get STALE span and a cache.put REVALIDATED span")
        void revalidationRecordsStaleLookupAndRefresh() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class))
                    .thenReturn(new CachedResponse(
                            RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME - 100_000));
            when(execution.execute(any(), any())).thenReturn(create304Response());

            observedInterceptor(CachingClientHttpRequestInterceptor.DEFAULT_MAX_CACHEABLE_SIZE)
                    .intercept(createGetRequest(), new byte[0], execution);

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
        void invalidationRecordsStaleLookupAndReplace() throws Exception {
            when(clock.millis()).thenReturn(CURRENT_TIME);
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class))
                    .thenReturn(new CachedResponse(
                            RESPONSE_BODY, DEFAULT_HEADERS, "\"abc123\"", null, 60, CURRENT_TIME - 100_000));
            when(execution.execute(any(), any())).thenReturn(createResponse("\"new-etag\"", null, null));

            observedInterceptor(CachingClientHttpRequestInterceptor.DEFAULT_MAX_CACHEABLE_SIZE)
                    .intercept(createGetRequest(), new byte[0], execution);

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
        void oversizedResponseRecordsSkipStore() throws Exception {
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            var largeBody = new byte[2000];
            var headers = new HttpHeaders();
            headers.set("ETag", "\"too-large\"");
            var response = new TestClientHttpResponse(HttpStatus.OK, headers, largeBody);
            when(execution.execute(any(), any())).thenReturn(response);

            observedInterceptor(1000).intercept(createGetRequest(), new byte[0], execution);

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_PUT)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_SKIP)
                    .hasLowCardinalityKeyValue("cache.skip.reason", HttpCacheEngine.SKIP_BODY_SIZE);
        }

        @Test
        @DisplayName("Known oversized response records its cache.put SKIP reason")
        void knownOversizedResponseRecordsSkipStore() throws Exception {
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            var headers = new HttpHeaders();
            headers.set("ETag", "\"too-large\"");
            headers.setContentLength(2000);
            var response = new TestClientHttpResponse(HttpStatus.OK, headers, new byte[2000]);
            when(execution.execute(any(), any())).thenReturn(response);

            observedInterceptor(1000).intercept(createGetRequest(), new byte[0], execution);

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_PUT)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_SKIP)
                    .hasLowCardinalityKeyValue("cache.skip.reason", HttpCacheEngine.SKIP_CONTENT_LENGTH);
        }

        @Test
        @DisplayName("Response without cache headers records its cache.put SKIP reason")
        void responseWithoutCacheHeadersRecordsSkipStore() throws Exception {
            when(cacheKeyMapper.apply(any(HttpRequest.class))).thenReturn(CACHE_KEY);
            when(cache.get(CACHE_KEY, CachedResponse.class)).thenReturn(null);
            when(execution.execute(any(), any())).thenReturn(createResponse(null, null, null));

            observedInterceptor(CachingClientHttpRequestInterceptor.DEFAULT_MAX_CACHEABLE_SIZE)
                    .intercept(createGetRequest(), new byte[0], execution);

            TestObservationRegistryAssert.assertThat(observationRegistry)
                    .hasObservationWithNameEqualTo(CACHE_PUT)
                    .that()
                    .hasLowCardinalityKeyValue(HttpCacheEngine.CACHE_STATUS, HttpCacheEngine.CACHE_SKIP)
                    .hasLowCardinalityKeyValue("cache.skip.reason", HttpCacheEngine.SKIP_NO_CACHE_HEADERS);
        }
    }
}
