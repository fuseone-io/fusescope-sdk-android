package io.fuseone.fusescope.sample

import android.app.Application
import io.fuseone.fusescope.android.FuseScope
import io.fuseone.fusescope.android.FuseScopeConfig

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FuseScope.init(
            this,
            FuseScopeConfig(
                applicationId = BuildConfig.FUSESCOPE_APPLICATION_ID,
                key = BuildConfig.FUSESCOPE_KEY,
                endpoint = BuildConfig.FUSESCOPE_ENDPOINT,
                environment = "sample",
                requireConsent = BuildConfig.REQUIRE_CONSENT,
                sessionSampleRate = BuildConfig.SAMPLE_RATE,
                traceparentHosts = if (BuildConfig.PROPAGATE) null else emptyList(),
            ),
        )
        FuseScope.setUser("sample-user")
    }
}
