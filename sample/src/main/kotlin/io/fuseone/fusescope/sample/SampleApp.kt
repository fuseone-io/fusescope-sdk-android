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
            ),
        )
        FuseScope.setUser("sample-user")
    }
}
