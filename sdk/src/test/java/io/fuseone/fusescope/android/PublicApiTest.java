package io.fuseone.fusescope.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.opentelemetry.api.OpenTelemetry;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import org.junit.Test;

/** Compile the Java facade and shortest overloads, without starting Android instrumentation. */
public class PublicApiTest {
    @Test
    public void javaConsumersCanConfigureAndCallTheFacade() {
        FuseScopeConfig config = new FuseScopeConfig("app", "rum_pub_test", "https://example.com");
        assertEquals(1.0, config.getSessionSampleRate(), 0.0);
        assertEquals(new PrivacyConfig(), config.getPrivacy());
        assertEquals(new SessionConfig(), config.getSession());
        assertEquals(new InstrumentationsConfig(), config.getInstrumentations());
        FuseScope.setUser(null);
        FuseScope.addError(new IllegalStateException("before init"));
        FuseScope.addAction("before init");
        FuseScope.startView("orders/{id}");
        Call.Factory calls = FuseScope.callFactory(new OkHttpClient());
        assertNotNull(calls);
        assertEquals(OpenTelemetry.noop(), FuseScope.getOpenTelemetry());
        assertNotNull(FuseScope.VERSION);
        FuseScope.flush();
    }
}
