package io.github.pulpogato.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pulpogato.common.cache.CachingClientHttpRequestInterceptor;
import io.github.pulpogato.common.cache.CachingExchangeFilterFunction;
import io.github.pulpogato.common.client.JwtClientHttpRequestInterceptor;
import io.github.pulpogato.common.client.JwtFactory;
import io.github.pulpogato.common.client.JwtFilter;
import io.github.pulpogato.common.client.MetricsClientHttpRequestInterceptor;
import io.github.pulpogato.common.client.MetricsExchangeFunction;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.StringWriter;
import java.security.KeyPairGenerator;
import java.util.List;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

class DocumentationIntegrationTest {
    @Test
    void setupCache() {
        // Setup cache
        Cache cache = new ConcurrentMapCache("github-http-cache");

        // Setup client
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ...your token here...")
                .build();

        // tag::setup-cache[]
        var cachingFilter = CachingExchangeFilterFunction.builder()
                .cache(cache) // <1>
                .build();

        var cachingClient = webClient
                .mutate()
                .filter(cachingFilter) // <2>
                .build();
        // end::setup-cache[]

        assertThat(cachingClient).isNotNull();
    }

    @Test
    void setupCacheRestClient() {
        // Setup cache
        Cache cache = new ConcurrentMapCache("github-http-cache");

        // tag::setup-cache-restclient[]
        var cachingInterceptor = CachingClientHttpRequestInterceptor.builder()
                .cache(cache) // <1>
                .build();

        var cachingClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ...your token here...")
                .requestInterceptor(cachingInterceptor) // <2>
                .build();
        // end::setup-cache-restclient[]

        assertThat(cachingClient).isNotNull();
    }

    @Test
    void setupCacheMetrics() {
        Cache cache = new ConcurrentMapCache("github-http-cache");

        // tag::setup-cache-metrics[]
        var meterRegistry = new SimpleMeterRegistry();
        var observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));

        var cachingFilter = CachingExchangeFilterFunction.builder()
                .cache(cache)
                .observationRegistry(observationRegistry)
                .build();
        // end::setup-cache-metrics[]

        assertThat(cachingFilter).isNotNull();
    }

    @Test
    void advancedCache() {
        // Setup cache
        Cache cache = new ConcurrentMapCache("github-http-cache");

        // Setup client
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ...your token here...")
                .build();

        // tag::advanced-cache[]
        var cachingFilter = CachingExchangeFilterFunction.builder()
                .cache(cache)
                .cacheKeyMapper(request -> request.url().toASCIIString()) // <1>
                .maxCacheableSize(4 * 1024 * 1024) // <2>
                .alwaysRevalidate(true) // <3>
                .build();

        var cachingClient = webClient.mutate().filter(cachingFilter).build();
        // end::advanced-cache[]

        assertThat(cachingClient).isNotNull();
    }

    @Test
    void advancedCacheRestClient() {
        // Setup cache
        Cache cache = new ConcurrentMapCache("github-http-cache");

        // tag::advanced-cache-restclient[]
        var cachingInterceptor = CachingClientHttpRequestInterceptor.builder()
                .cache(cache)
                .cacheKeyMapper(request -> request.getURI().toASCIIString()) // <1>
                .maxCacheableSize(4 * 1024 * 1024) // <2>
                .alwaysRevalidate(true) // <3>
                .build();

        var cachingClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ...your token here...")
                .requestInterceptor(cachingInterceptor)
                .build();
        // end::advanced-cache-restclient[]

        assertThat(cachingClient).isNotNull();
    }

    @Test
    void setupMetrics() {
        var meterRegistry = new SimpleMeterRegistry();

        // Setup client
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ...your token here...")
                .build();

        // tag::setup-metrics[]
        var metricsFilter = MetricsExchangeFunction.builder()
                .registry(meterRegistry) // <1>
                .build();

        var metricsClient = webClient.mutate().filter(metricsFilter).build();
        // end::setup-metrics[]
        assertThat(metricsClient).isNotNull();
    }

    @Test
    void setupMetricsRestClient() {
        var meterRegistry = new SimpleMeterRegistry();

        // tag::setup-metrics-restclient[]
        var metricsInterceptor = MetricsClientHttpRequestInterceptor.builder()
                .registry(meterRegistry) // <1>
                .build();

        var metricsClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ...your token here...")
                .requestInterceptor(metricsInterceptor)
                .build();
        // end::setup-metrics-restclient[]
        assertThat(metricsClient).isNotNull();
    }

    @Test
    void advancedMetrics() {
        var meterRegistry = new SimpleMeterRegistry();

        // Setup client
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ...your token here...")
                .build();

        // tag::advanced-metrics[]
        var metricsFilter = MetricsExchangeFunction.builder()
                .registry(meterRegistry)
                .prefix("myapp.github.rateLimit") // <1>
                .defaultTags(List.of(Tag.of("env", "production"))) // <2>
                .build();

        var metricsClient = webClient.mutate().filter(metricsFilter).build();
        // end::advanced-metrics[]
        assertThat(metricsClient).isNotNull();
    }

    @Test
    void advancedMetricsRestClient() {
        var meterRegistry = new SimpleMeterRegistry();

        // tag::advanced-metrics-restclient[]
        var metricsInterceptor = MetricsClientHttpRequestInterceptor.builder()
                .registry(meterRegistry)
                .prefix("myapp.github.rateLimit") // <1>
                .defaultTags(List.of(Tag.of("env", "production"))) // <2>
                .build();

        var metricsClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ...your token here...")
                .requestInterceptor(metricsInterceptor)
                .build();
        // end::advanced-metrics-restclient[]
        assertThat(metricsClient).isNotNull();
    }

    @Test
    void setupJwt() throws Exception {
        // Generate a test key (in real code, load from GitHub App settings)
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keyPair = keyGen.generateKeyPair();
        var writer = new StringWriter();
        try (var pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(keyPair);
        }
        var privateKeyPem = writer.toString();

        // tag::setup-jwt[]
        var jwtFactory = new JwtFactory(
                privateKeyPem, // <1>
                12345L // <2>
                );
        var jwtFilter = JwtFilter.builder().jwtFactory(jwtFactory).build();

        var webClient = WebClient.builder()
                .baseUrl("https://api.github.com")
                .filter(jwtFilter) // <3>
                .build();
        // end::setup-jwt[]
        assertThat(webClient).isNotNull();
    }

    @Test
    void setupJwtRestClient() throws Exception {
        // Generate a test key (in real code, load from GitHub App settings)
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        var keyPair = keyGen.generateKeyPair();
        var writer = new StringWriter();
        try (var pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(keyPair);
        }
        var privateKeyPem = writer.toString();

        // tag::setup-jwt-restclient[]
        var jwtFactory = new JwtFactory(
                privateKeyPem, // <1>
                12345L // <2>
                );
        var jwtInterceptor =
                JwtClientHttpRequestInterceptor.builder().jwtFactory(jwtFactory).build();

        var restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestInterceptor(jwtInterceptor) // <3>
                .build();
        // end::setup-jwt-restclient[]
        assertThat(restClient).isNotNull();
    }
}
