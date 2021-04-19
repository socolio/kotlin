// !WITH_NEW_INFERENCE
// !DIAGNOSTICS: -UNUSED_PARAMETER

import kotlin.reflect.KProperty

val c: Int by <!TYPE_MISMATCH!>Delegate()<!>

class Delegate {
  operator fun getValue(t: Any?, p: KProperty<*>): String {
    return ""
  }
}
