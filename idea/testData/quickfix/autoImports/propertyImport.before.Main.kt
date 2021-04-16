// "Import" "true"
// ERROR: Unresolved reference: someTestProp

package test

fun foo() {
    <caret>someTestProp
}

/* FIR_COMPARISON */