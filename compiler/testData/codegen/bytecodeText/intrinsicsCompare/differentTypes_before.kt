// !LANGUAGE: -ProperIeee754Comparisons
fun box(): String {
    val zero: Any = 0.0
    val floatZero: Any = -0.0F
    if (zero is Double && floatZero is Float) {
        if (zero <= floatZero) return "fail 1"

        return "OK"
    }

    return "fail"
}

// 0 Intrinsics\.areEqual
// 1 Double\.compare
