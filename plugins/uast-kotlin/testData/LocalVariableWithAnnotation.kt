// !IGNORE_FIR

annotation class TestAnnotation

fun foo() {
    @TestAnnotation
    val bar = "lorem ipsum"
}
