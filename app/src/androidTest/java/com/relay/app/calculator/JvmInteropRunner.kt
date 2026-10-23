package com.relay.app.calculator

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Only selected explicitly by the disposable emulator harness, never the shipped app runner. */
class JvmInteropRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun callApplicationOnCreate(app: Application) {
        // Install before AndroidJUnitRunner can resume any previously retained target Activity.
        val field=com.relay.app.transport.Transports::class.java.getDeclaredField("instance").apply { isAccessible=true }
        check(field.get(null)==null) { "Transport created before isolated test Application startup" }
        field.set(null,com.relay.app.transport.mailbox.MailboxTransport(app,"http://10.0.2.2:8787"))
        super.callApplicationOnCreate(app)
    }
}
