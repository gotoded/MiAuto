package com.andrerinas.openheadunit.connection

import android.content.Context
import java.io.File

/**
 * Which native libraries the ICCOA SDK loads, and whether this device shipped them.
 *
 * The SDK dex calls `System.loadLibrary("usbio")` from `UsbNative.<clinit>` and
 * `System.loadLibrary("ovmsink")` from `SinkNative.<clinit>`. Only the arm64-v8a split carries
 * `libusbio.so` / `libovmsink.so`; on armeabi-v7a, x86 and x86_64 the loader throws *inside static
 * initialization*, which is unrecoverable — the class is left in an erroneous state and the process
 * dies on the spot. Checking the installed library directory first turns that crash into an honest
 * message.
 *
 * `libovmsink.so` is the projection sink itself, so a device without it can never cast even if the
 * USB half were patched; that is why both are required.
 */
object NativeStack {

    /** Libraries the SDK loads by name, in the order it loads them. */
    val requiredLibraries = listOf("libovmsink.so", "libusbio.so")

    /** Pure part of [missingLibraries], so the rule can be unit-tested without a Context. */
    fun missingFrom(present: Collection<String>): List<String> =
        requiredLibraries.filter { it !in present }

    fun missingLibraries(context: Context): List<String> {
        val dir = context.applicationInfo.nativeLibraryDir ?: return requiredLibraries
        val present = File(dir).list()?.toList().orEmpty()
        return missingFrom(present)
    }
}
