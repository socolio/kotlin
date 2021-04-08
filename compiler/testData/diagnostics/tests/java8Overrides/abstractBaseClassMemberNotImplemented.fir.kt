abstract class ALeft {
    abstract fun foo()
}

interface IRight {
    fun foo() {}
}

<!ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED!>class CDerived<!> : ALeft(), IRight

abstract class CAbstract : ALeft(), IRight

<!ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED!>class CDerivedFromAbstract<!> : CAbstract()

interface ILeft {
    fun foo()
}

abstract class AILeft : ILeft

// Should be ERROR
class AILeftImpl : AILeft(), IRight

// Should be ERROR
class RightLeft : ILeft, IRight
