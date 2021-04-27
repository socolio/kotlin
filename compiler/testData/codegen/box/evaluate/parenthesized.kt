// TARGET_BACKEND: JVM

// WITH_RUNTIME
// FULL_JDK

import java.lang.ref.SoftReference
import java.nio.ByteBuffer


private var weakBuffer: SoftReference<ByteBuffer> = SoftReference(null)

fun box(): String {

    return "OK"
}
