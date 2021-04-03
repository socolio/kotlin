// !LANGUAGE: +ProperIeee754Comparisons

fun lt(x: Any, y: Any) = x is Double && y is Float && x < y
fun gt(x: Any, y: Any) = x is Double && y is Float && x > y

fun box(): String {
    if (lt(0.0, -0.0F)) return "fail 1"
    if (gt(0.0, -0.0F)) return "fail 2"

    return "OK"
}
