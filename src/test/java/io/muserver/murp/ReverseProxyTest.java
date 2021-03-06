package io.muserver.murp;

import io.muserver.*;
import io.muserver.handlers.ResourceHandlerBuilder;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.RawClient;
import scaffolding.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static io.muserver.Http2ConfigBuilder.http2EnabledIfAvailable;
import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.murp.HttpClientBuilder.httpClient;
import static io.muserver.murp.ReverseProxyBuilder.reverseProxy;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class ReverseProxyTest {

    private static final HttpClient client = HttpClientBuilder.httpClient()
        .withMaxRequestHeadersSize(32768)
        .build();


    @Test
    public void itCanProxyEverythingToATargetDomain() throws Exception {

        MuServer targetServer = httpServer()
            .addHandler(Method.POST, "/some-text",
                (request, response, pathParams) -> {
                    response.status(201);
                    response.headers().set("X-Something", "a header value");
                    response.headers().set("X-Received", "Foo: " + request.headers().getAll("Foo"));
                    response.write("Hello: " + request.readBodyAsString());
                })
            .start();

        List<String> notifications = new ArrayList<>();
        CountDownLatch notificationAddedLatch = new CountDownLatch(1);

        MuServer reverseProxyServer = httpsServer()
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .addProxyCompleteListener((clientRequest, clientResponse, target, durationMillis) -> {
                    notifications.add("Did " + clientRequest.method() + " " + clientRequest.uri().getPath() + " and returned a " + clientResponse.status() + " from " + target);
                    notificationAddedLatch.countDown();
                })
            )
            .start();

        String requestBody = largeRandomString();

        ContentResponse someText = client.POST(reverseProxyServer.uri().resolve("/some-text"))
            .header("Connection", "Keep-Alive, Foo, Bar")
            .header("foo", "abc")
            .header("Foo", "def")
            .header("Keep-Alive", "timeout=30")
            .content(new StringContentProvider(requestBody))
            .send();
        assertThat(someText.getStatus(), is(201));
        HttpFields headers = someText.getHeaders();
        assertThat(headers.get("X-Something"), is("a header value"));
        assertThat(headers.get("X-Received"), is("Foo: []"));
        assertThat(headers.get("Content-Length"), is(notNullValue()));
        assertThat(headers.get("Via"), is("HTTP/1.1 private"));
        assertThat(headers.get("Forwarded"), is(nullValue()));
        assertThat(someText.getContentAsString(), is("Hello: " + requestBody));
        assertThat("Timed out waiting for notification",
            notificationAddedLatch.await(10, TimeUnit.SECONDS), is(true));
        assertThat("Actual: " + notifications, notifications, contains("Did POST /some-text and returned a 201 from " + targetServer.uri().resolve("/some-text")));
    }

    @Test
    public void gzipGetsProxiedAsGzip() throws Exception {
        MuServer targetServer = httpServer()
            .withHttp2Config(http2EnabledIfAvailable())
            .addHandler(ResourceHandlerBuilder.fileHandler("."))
            .start();

        MuServer reverseProxyServer = httpsServer()
            .withHttp2Config(http2EnabledIfAvailable())
            .addHandler(reverseProxy().withUriMapper(UriMapper.toDomain(targetServer.uri())))
            .start();

        try (okhttp3.Response resp = call(request(reverseProxyServer.uri().resolve("/pom.xml"))
            .header("Accept-Encoding", "hmm, gzip, deflate"))) { // custom header stops okhttpclient from hiding gzip
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-encoding"), is("gzip"));
            String expected = new String(Files.readAllBytes(Paths.get("pom.xml")), UTF_8);
            String unzipped;
            try (ByteArrayOutputStream boas = new ByteArrayOutputStream();
                 InputStream is = new GZIPInputStream(resp.body().byteStream())) {
                Mutils.copy(is, boas, 8192);
                unzipped = boas.toString("UTF-8");
            }
            assertThat(unzipped, equalTo(expected));
        }
    }


    @Test
    public void viaHeadersCanBeSet() throws Exception {
        MuServer targetServer = httpsServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write(
                "The host header is " + req.headers().get("Host") +
                    " and the Via header is "
                    + req.headers().getAll("via") + " and forwarded is " + ForwardedHeader.toString(req.headers().forwarded())))
            .start();

        MuServer reverseProxyServer = httpServer()
            .addHandler(reverseProxy()
                .withViaName("blardorph")
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
            )
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri().resolve("/"));
        assertThat(resp.getHeaders().getCSV("Via", true), contains("HTTP/1.1 blardorph"));
        String body = resp.getContentAsString();
        assertThat(body, startsWith("The host header is " + reverseProxyServer.uri().getAuthority() +
            " and the Via header is [HTTP/1.1 blardorph] and forwarded is by="));
        assertThat(body, endsWith(";host=\"" + reverseProxyServer.uri().getAuthority() + "\";proto=http"));
    }

    @Test
    public void itCanProxyPieceByPiece() throws InterruptedException, ExecutionException, TimeoutException {
        String m1 = StringUtils.randomAsciiStringOfLength(20000);
        String m2 = StringUtils.randomAsciiStringOfLength(120000);
        String m3 = StringUtils.randomAsciiStringOfLength(20000);
        MuServer targetServer = httpsServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> {
                resp.sendChunk(m1);
                resp.sendChunk(m2);
                resp.sendChunk(m3);
            })
            .start();

        MuServer reverseProxyServer = httpServer()
            .addHandler(reverseProxy().withUriMapper(UriMapper.toDomain(targetServer.uri())))
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri().resolve("/"));
        String body = resp.getContentAsString();
        assertThat(body, equalTo(m1 + m2 + m3));
    }

    @Test
    public void theHostNameProxyingCanBeTurnedOff() throws InterruptedException, ExecutionException, TimeoutException {
        MuServer targetServer = httpsServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write(
                "The host header is " + req.headers().get("Host") +
                    " and the Via header is "
                    + req.headers().getAll("via") + " and forwarded is " + ForwardedHeader.toString(req.headers().forwarded())))
            .start();

        MuServer reverseProxyServer = httpServer()
            .addHandler(reverseProxy()
                .withViaName("blardorph")
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .proxyHostHeader(false)
            )
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri().resolve("/"));
        assertThat(resp.getHeaders().getCSV("Via", true), contains("HTTP/1.1 blardorph"));
        String body = resp.getContentAsString();
        assertThat(body, startsWith("The host header is " + targetServer.uri().getAuthority() +
            " and the Via header is [HTTP/1.1 blardorph] and forwarded is by="));
        assertThat(body, endsWith(";host=\"" + reverseProxyServer.uri().getAuthority() + "\";proto=http"));
    }

    @Test
    public void http1ToHttp1ToHttp1Works() throws Exception {
        MuServer targetServer = httpServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> {
                String forwarded = req.headers().forwarded().stream().map(f -> f.proto() + " with host " + f.host()).collect(Collectors.joining(", "));
                resp.write("The Via header is "
                    + req.headers().getAll("via") + " and forwarded is " + forwarded);
            })
            .start();

        MuServer internalRP = httpServer()
            .addHandler(reverseProxy()
                .withViaName("internalrp")
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
            )
            .start();

        MuServer externalRP = httpsServer()
            .addHandler(reverseProxy()
                .withViaName("externalrp")
                .withUriMapper(UriMapper.toDomain(internalRP.uri()))
            )
            .start();

        ContentResponse resp = client.GET(externalRP.uri().resolve("/"));
        assertThat(resp.getHeaders().getValuesList("Date"), hasSize(1));
        assertThat(resp.getHeaders().getValuesList("Via"), contains("HTTP/1.1 internalrp, HTTP/1.1 externalrp"));
        assertThat(resp.getContentAsString(), is("The Via header is [HTTP/1.1 externalrp, HTTP/1.1 internalrp]" +
            " and forwarded is https with host " + externalRP.uri().getAuthority() + ", http with host "
            + externalRP.uri().getAuthority()));
    }

    @Test
    public void http2ToHttp1ToTargetWorks() throws Exception {
        runIfJava9OrLater();
        MuServer targetServer = httpServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> {
                String forwarded = req.headers().forwarded().stream().map(f -> f.proto() + " with host " + f.host()).collect(Collectors.joining(", "));
                resp.write("The Via header is "
                    + req.headers().getAll("via") + " and forwarded is " + forwarded);
            })
            .start();

        MuServer internalRP = httpServer()
            .addHandler(reverseProxy()
                .withViaName("internalrp")
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
            )
            .start();

        MuServer externalRP = httpsServer()
            .withHttp2Config(http2EnabledIfAvailable())
            .addHandler(reverseProxy()
                .withViaName("externalrp")
                .withUriMapper(UriMapper.toDomain(internalRP.uri()))
            )
            .start();

        try (okhttp3.Response resp = call(request(externalRP.uri().resolve("/")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("date"), hasSize(1));
            assertThat(resp.headers("via"), contains("HTTP/1.1 internalrp, HTTP/2 externalrp"));
            assertThat(resp.body().string(), is("The Via header is [HTTP/2 externalrp, HTTP/1.1 internalrp]" +
                " and forwarded is https with host " + externalRP.uri().getAuthority() + ", http with host "
                + externalRP.uri().getAuthority()));
        }
    }

    @Test
    public void http1ToHttp2ToHttp2TargetWorks() throws Exception {
        runIfJava9OrLater();

        MuServer targetServer = httpServer()
            .withHttp2Config(http2EnabledIfAvailable())
            .addHandler(Method.GET, "/", (req, resp, pp) -> {
                String forwarded = req.headers().forwarded().stream().map(f -> f.proto() + " with host " + f.host()).collect(Collectors.joining(", "));
                resp.write("The Via header is "
                    + req.headers().getAll("via") + " and forwarded is " + forwarded);
            })
            .start();

        MuServer internalRP = httpServer()
            .addHandler(reverseProxy()
                .withViaName("internalrp")
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
            )
            .start();

        MuServer externalRP = httpsServer()
            .addHandler(reverseProxy()
                .withViaName("externalrp")
                .withUriMapper(UriMapper.toDomain(internalRP.uri()))
            )
            .start();

        try (okhttp3.Response resp = call(request(externalRP.uri().resolve("/")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("date"), hasSize(1));
            // Note: internalrp is expected as 1.1 rather than 2 because the Jetty client in the RP makes 1.1 calls
            assertThat(resp.headers("via"), contains("HTTP/1.1 internalrp, HTTP/1.1 externalrp"));
            assertThat(resp.body().string(), is("The Via header is [HTTP/1.1 externalrp, HTTP/1.1 internalrp]" +
                " and forwarded is https with host " + externalRP.uri().getAuthority() + ", http with host "
                + externalRP.uri().getAuthority()));
        }

    }

    @Test
    public void cookiesSentOnMultipleHeadersAreConvertedToSingleLines() throws Exception {
        MuServer targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pp) -> {
                response.write("START; " + request.cookies().stream().map(Cookie::toString)
                    .sorted().collect(Collectors.joining("; "))
                    + "; END");
            })
            .start();

        MuServer rp = httpServer()
            .addHandler(reverseProxy()
                .withViaName("externalrp")
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
            )
            .start();

        try (RawClient rawClient = RawClient.create(rp.uri())
            .sendStartLine("GET", "/")
            .sendHeader("host", rp.uri().getAuthority())
            .sendHeader("cookie", "cookie1=something")
            .sendHeader("cookie", "cookie2=somethingelse")
            .endHeaders()
            .flushRequest()) {

            assertEventually(rawClient::responseString, endsWith("END"));
            assertThat(rawClient.responseString(), endsWith("START; cookie1=something; cookie2=somethingelse; END"));
        }
    }


    private void runIfJava9OrLater() {
        Assume.assumeThat("This test runs only on java 9 an later", System.getProperty("java.specification.version"), not(equalTo("1.8")));
    }

    @Test
    public void proxyingCanBeIntercepted() throws Exception {
        MuServer targetServer = httpServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> {
                resp.headers().add("X-Added-By-Target", "Boo");
                resp.write(
                    "X-Blocked = " + req.headers().get("X-Blocked") + ", X-Added = " + req.headers().get("X-Added")
                );
            })
            .start();

        MuServer reverseProxyServer = httpServer()
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .withRequestInterceptor(new RequestInterceptor() {
                    @Override
                    public void intercept(MuRequest clientRequest, Request targetRequest) {
                        clientRequest.attribute("blah", "blah-blah-blah");
                        targetRequest.header("X-Blocked", null);
                        targetRequest.header("X-Added", "I was added");
                    }
                })
                .withResponseInterceptor(new ResponseInterceptor() {
                    @Override
                    public void intercept(MuRequest clientRequest, Request targetRequest, Response targetResponse, MuResponse clientResponse) {
                        clientResponse.status(400);
                        Headers headers = clientResponse.headers();
                        headers.set("X-Blah", clientRequest.attribute("blah"));
                        headers.set("X-Added-By-Resp", "Added-by-resp");
                        headers.remove("X-Added-By-Target");
                    }
                })
                .addProxyCompleteListener(new Slf4jResponseLogger())
            )
            .start();

        ContentResponse resp = client.GET(reverseProxyServer.uri().resolve("/"));
        assertThat(resp.getStatus(), is(400));
        assertThat(resp.getHeaders().getValuesList("X-Added-By-Resp"), contains("Added-by-resp"));
        assertThat(resp.getHeaders().getValuesList("X-Added-By-Target"), empty());
        String body = resp.getContentAsString();
        assertThat(body, equalTo("X-Blocked = null, X-Added = I was added"));
    }

    @Test
    public void largeHeadersCanBeConfigured() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 26000; i++) {
            sb.append("a");
        }
        String value = sb.toString();
        int maxHeaderSize = 32768;
        MuServer targetServer = httpServer()
            .withMaxHeadersSize(maxHeaderSize)
            .addHandler(Method.GET, "/", (req, resp, pp) -> {
                resp.write(req.headers().get("X-Large"));
            })
            .start();

        MuServer rp = httpServer()
            .withMaxHeadersSize(maxHeaderSize)
            .addHandler(reverseProxy()
                .withUriMapper(UriMapper.toDomain(targetServer.uri()))
                .withHttpClient(httpClient().withMaxRequestHeadersSize(maxHeaderSize))
            )
            .start();

        ContentResponse resp = client.newRequest(rp.uri().resolve("/"))
            .header("X-Large", value)
            .send();
        assertThat(resp.getContentAsString(), equalTo(value));

    }

    @Test
    public void sseIsPublishedOneMessageAtATime() throws Exception {
        String m1 = "Message1";
        String m2 = "<html>\t\n" + StringUtils.randomAsciiStringOfLength(120000) + "\n</html>";
        CountDownLatch m1Latch = new CountDownLatch(1);
        AtomicBoolean waited = new AtomicBoolean(false);
        MuServer targetServer = httpServer()
            .addHandler(Method.GET, "/", (req, resp, pp) -> {
                SsePublisher publisher = SsePublisher.start(req, resp);
                publisher.send(m1);
                waited.set(m1Latch.await(20, TimeUnit.SECONDS));
                publisher.send(m2);
                publisher.close();
            })
            .start();

        MuServer rp = httpServer()
            .addHandler(reverseProxy().withUriMapper(UriMapper.toDomain(targetServer.uri())))
            .start();


        CountDownLatch messageReceivedLatch = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);
        EventSource.Factory esf = EventSources.createFactory(ClientUtils.client);
        List<String> received = new CopyOnWriteArrayList<>();
        esf.newEventSource(request(rp.uri()).build(), new EventSourceListener() {
            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                received.add(data);
                messageReceivedLatch.countDown();
            }

            @Override
            public void onClosed(EventSource eventSource) {
                closedLatch.countDown();
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, okhttp3.Response response) {
                System.out.println("Error from sse = " + t);
            }
        });
        assertThat(messageReceivedLatch.await(20, TimeUnit.SECONDS), is(true));

        assertThat(received, contains(m1));
        m1Latch.countDown();
        assertThat(closedLatch.await(20, TimeUnit.SECONDS), is(true));
        assertThat(received, contains(m1, m2));

    }

    private String largeRandomString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append(UUID.randomUUID()).append(" ");
        }
        return sb.toString();
    }

    @AfterClass
    public static void stop() throws Exception {
        client.stop();
    }

}
