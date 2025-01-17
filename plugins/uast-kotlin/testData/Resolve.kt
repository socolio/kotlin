// !IGNORE_FIR

open class A {
    fun foo() {}
    inline fun inlineFoo() {

    }
}

fun bar() {
    A().foo()
    A().inlineFoo()
    listOf(A()).forEach { println(it) } // inline from stdlib
    listOf("").joinToString() // not inline from stdlib
    listOf("").size // property from stdlib
    listOf("").indices // property from stdlib without backing method
    val date: java.util.Date = java.util.Date()
    date.time = 1000 // setter from Java
    listOf("").last() // overloaded extension from stdlib
    mutableMapOf(1 to "1").entries.first().setValue("123") // call on nested method in stdlib
    val intRange = 0L..3L
    intRange.contains(2 as Int) // extension-fun with @JvmName("longRangeContains")
    IntRange(1, 2) // constructor from stdlib
}

fun <T : A> barT(t: T) {
    t.foo()
}

fun <T : List<A>> barTL(listT: T) {
    listT.isEmpty()
    for (a in listT) {
        a.foo()
    }
}
