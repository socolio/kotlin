/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.diagnostics

import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.diagnostics.rendering.DiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnosticRenderers.DECLARATION_NAME
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnosticRenderers.NULLABLE_STRING
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnosticRenderers.PROPERTY_NAME
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnosticRenderers.SYMBOL
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnosticRenderers.SYMBOLS
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.ABSTRACT_SUPER_CALL
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.AMBIGUITY
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.ANNOTATION_CLASS_MEMBER
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.ANY_METHOD_IMPLEMENTED_IN_INTERFACE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.ASSIGN_OPERATOR_AMBIGUITY
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.BREAK_OR_CONTINUE_OUTSIDE_A_LOOP
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.CLASS_IN_SUPERTYPE_FOR_ENUM
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.CONFLICTING_OVERLOADS
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.CONSTRUCTOR_IN_INTERFACE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.CONSTRUCTOR_IN_OBJECT
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.CYCLIC_CONSTRUCTOR_DELEGATION_CALL
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.DELEGATION_IN_INTERFACE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.DELEGATION_SUPER_CALL_IN_ENUM_CONSTRUCTOR
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.DEPRECATED_MODIFIER_PAIR
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.DESERIALIZATION_ERROR
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.ENUM_AS_SUPERTYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.ERROR_FROM_JAVA_RESOLUTION
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.EXPLICIT_DELEGATION_CALL_REQUIRED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.EXPOSED_FUNCTION_RETURN_TYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.EXPOSED_PARAMETER_TYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.EXPOSED_PROPERTY_TYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.EXPOSED_RECEIVER_TYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.EXPOSED_SUPER_CLASS
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.EXPOSED_SUPER_INTERFACE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.EXPOSED_TYPEALIAS_EXPANDED_TYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.EXPOSED_TYPE_PARAMETER_BOUND
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.FUNCTION_INVOCATION_REQUIRED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.HIDDEN
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.ILLEGAL_CONST_EXPRESSION
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.ILLEGAL_UNDERSCORE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.INAPPLICABLE_CANDIDATE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.INAPPLICABLE_INFIX_MODIFIER
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.INCOMPATIBLE_MODIFIERS
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.INFERENCE_ERROR
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.INTERFACE_WITH_SUPERCLASS
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.INVALID_TYPE_OF_ANNOTATION_MEMBER
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.LEAKED_IN_PLACE_LAMBDA
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.LOCAL_ANNOTATION_CLASS_ERROR
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.LOCAL_INTERFACE_NOT_ALLOWED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.LOCAL_OBJECT_NOT_ALLOWED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.MANY_COMPANION_OBJECTS
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.MISSING_COEFFECT_CONTEXT
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.MISSING_VAL_ON_ANNOTATION_PARAMETER
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.NONE_APPLICABLE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.NON_PRIVATE_CONSTRUCTOR_IN_ENUM
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.NON_PRIVATE_CONSTRUCTOR_IN_SEALED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.NOT_AN_ANNOTATION_CLASS
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.NOT_A_LOOP_LABEL
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.NOT_A_SAFE_BUILDER_MEMBER
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.NOT_A_SUPERTYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.NO_TYPE_FOR_TYPE_PARAMETER
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.NULLABLE_TYPE_OF_ANNOTATION_MEMBER
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.OTHER_ERROR
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.PRIMARY_CONSTRUCTOR_DELEGATION_CALL_EXPECTED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.PRIMARY_CONSTRUCTOR_REQUIRED_FOR_DATA_CLASS
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.PROPERTY_INITIALIZATION_REQUIRED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.QUALIFIED_SUPERTYPE_EXTENDED_BY_OTHER_SUPERTYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.RECURSION_IN_IMPLICIT_TYPES
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.RECURSION_IN_SUPERTYPES
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.REDECLARATION
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.REDUNDANT_MODIFIER
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.REPEATED_MODIFIER
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.RESOURCE_SHOULD_BE_CLOSED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.RESOURCE_SHOULD_BE_OPEN
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.RETURN_NOT_ALLOWED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.SEALED_CLASS_CONSTRUCTOR_CALL
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.SEALED_SUPERTYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.SEALED_SUPERTYPE_IN_LOCAL_CLASS
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.SUPERCLASS_NOT_ACCESSIBLE_FROM_INTERFACE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.SUPERTYPE_INITIALIZED_IN_INTERFACE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.SUPERTYPE_INITIALIZED_WITHOUT_PRIMARY_CONSTRUCTOR
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.SUPER_IS_NOT_AN_EXPRESSION
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.SUPER_NOT_AVAILABLE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.SYNTAX_ERROR
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.TYPE_ARGUMENTS_NOT_ALLOWED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.TYPE_MISMATCH
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.TYPE_PARAMETERS_IN_ENUM
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.TYPE_PARAMETERS_IN_OBJECT
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.TYPE_PARAMETER_AS_SUPERTYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.UNCHECKED_EXCEPTION
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.UNDEFINED_RESOURCE_STATE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.UNEXPECTED_COEFFECT_CONTEXT
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.UNINITIALIZED_VARIABLE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.UNPROVIDED_SAFE_BUILDER_INITIALIZATION
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.UNPROVIDED_SAFE_BUILDER_INVOCATION
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.UNRESOLVED_LABEL
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.UNRESOLVED_REFERENCE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.UPPER_BOUND_VIOLATED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.VARIABLE_EXPECTED
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.VAR_ANNOTATION_PARAMETER
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.WRONG_INVOCATION_KIND
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.WRONG_NUMBER_OF_TYPE_ARGUMENTS

@Suppress("unused")
class FirDefaultErrorMessages : DefaultErrorMessages.Extension {
    override fun getMap(): DiagnosticFactoryToRendererMap {
        return MAP.psiDiagnosticMap
    }

    companion object {
        // * - The old FE reports these diagnostics with additional parameters
        // & - New diagnostic that has no analogues in the old FE
        // + - Better message required
        // # - The new diagnostic differs from the old FE's one
        val MAP = FirDiagnosticFactoryToRendererMap("FIR").also { map ->
            // Miscellaneous
            map.put(SYNTAX_ERROR, "Syntax error")
            map.put(OTHER_ERROR, "Unknown (other) error")

            // General syntax
            map.put(ILLEGAL_CONST_EXPRESSION, "Illegal const expression")
            map.put(ILLEGAL_UNDERSCORE, "Illegal underscore")
//            map.put(EXPRESSION_REQUIRED, ...) // &
            map.put(BREAK_OR_CONTINUE_OUTSIDE_A_LOOP, "'break' and 'continue' are only allowed inside a loop")
            map.put(NOT_A_LOOP_LABEL, "The label does not denote a loop") // *
            map.put(VARIABLE_EXPECTED, "Variable expected")
            map.put(RETURN_NOT_ALLOWED, "'return' is not allowed here")
            map.put(DELEGATION_IN_INTERFACE, "Interfaces cannot use delegation")

            // Unresolved
            map.put(HIDDEN, "Symbol {0} is invisible", SYMBOL)
            map.put(UNRESOLVED_REFERENCE, "Unresolved reference: {0}", NULLABLE_STRING)
            map.put(UNRESOLVED_LABEL, "Unresolved label")
            map.put(DESERIALIZATION_ERROR, "Deserialization error")
            map.put(ERROR_FROM_JAVA_RESOLUTION, "Java resolution error")
//            map.put(UNKNOWN_CALLABLE_KIND, ...) // &
//            map.put(MISSING_STDLIB_CLASS, ...) // &

            // Super
            map.put(SUPER_IS_NOT_AN_EXPRESSION, "Super cannot be a callee")
            map.put(SUPER_NOT_AVAILABLE, "No supertypes are accessible in this context")
            map.put(ABSTRACT_SUPER_CALL, "Abstract member cannot be accessed directly")

            // Supertypes
            map.put(TYPE_PARAMETER_AS_SUPERTYPE, "Type parameter as supertype")
            map.put(ENUM_AS_SUPERTYPE, "Enum as supertype")
            map.put(RECURSION_IN_SUPERTYPES, "Recursion in supertypes")
            map.put(NOT_A_SUPERTYPE, "Not an immediate supertype")
            map.put(SUPERCLASS_NOT_ACCESSIBLE_FROM_INTERFACE, "Superclass is not accessible from interface")
            map.put(
                QUALIFIED_SUPERTYPE_EXTENDED_BY_OTHER_SUPERTYPE,
                "Explicitly qualified supertype is extended by another supertype ''{0}''",
                TO_STRING
            )
            map.put(SUPERTYPE_INITIALIZED_IN_INTERFACE, "Interfaces cannot initialize supertypes")
            map.put(INTERFACE_WITH_SUPERCLASS, "An interface cannot inherit from a class")
            map.put(CLASS_IN_SUPERTYPE_FOR_ENUM, "Enum class cannot inherit from classes")
            map.put(SEALED_SUPERTYPE, "This type is sealed, so it can be inherited by only its own nested classes or objects")
            map.put(SEALED_SUPERTYPE_IN_LOCAL_CLASS, "Local class cannot extend a sealed class")

            // Constructor problems
            map.put(CONSTRUCTOR_IN_OBJECT, "Constructors are not allowed for objects")
            map.put(CONSTRUCTOR_IN_INTERFACE, "An interface may not have a constructor")
            map.put(NON_PRIVATE_CONSTRUCTOR_IN_ENUM, "Constructor must be private in enum class")
            map.put(NON_PRIVATE_CONSTRUCTOR_IN_SEALED, "Constructor must be private in sealed class")
            map.put(CYCLIC_CONSTRUCTOR_DELEGATION_CALL, "There's a cycle in the delegation calls chain")
            map.put(PRIMARY_CONSTRUCTOR_DELEGATION_CALL_EXPECTED, "Primary constructor call expected")
            map.put(SUPERTYPE_INITIALIZED_WITHOUT_PRIMARY_CONSTRUCTOR, "Supertype initialization is impossible without primary constructor")
            map.put(DELEGATION_SUPER_CALL_IN_ENUM_CONSTRUCTOR, "Call to super is not allowed in enum constructor")
            map.put(PRIMARY_CONSTRUCTOR_REQUIRED_FOR_DATA_CLASS, "Primary constructor required for data class")
            map.put(
                EXPLICIT_DELEGATION_CALL_REQUIRED,
                "Explicit 'this' or 'super' call is required. There is no constructor in superclass that can be called without arguments"
            )
            map.put(SEALED_CLASS_CONSTRUCTOR_CALL, "Sealed types cannot be instantiated")

            // Annotations
            map.put(ANNOTATION_CLASS_MEMBER, "Members are not allowed in annotation class")
            map.put(
                ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT,
                "Default value of annotation parameter must be a compile-time constant"
            )
            map.put(LOCAL_ANNOTATION_CLASS_ERROR, "Annotation class cannot be local")
            map.put(MISSING_VAL_ON_ANNOTATION_PARAMETER, "'val' keyword is missing on annotation parameter")
            map.put(NULLABLE_TYPE_OF_ANNOTATION_MEMBER, "An annotation parameter cannot be nullable")
            map.put(INVALID_TYPE_OF_ANNOTATION_MEMBER, "Invalid type of annotation member")
            map.put(VAR_ANNOTATION_PARAMETER, "An annotation parameter cannot be 'var'")
            map.put(NOT_AN_ANNOTATION_CLASS, "Illegal annotation class: {0}", NULLABLE_STRING)

            // Exposed visibility group // #
            map.put(
                EXPOSED_TYPEALIAS_EXPANDED_TYPE,
                "{0} typealias exposes {2} in expanded type ''{1}''",
                TO_STRING,
                DECLARATION_NAME,
                TO_STRING
            )
            map.put(
                EXPOSED_FUNCTION_RETURN_TYPE,
                "{0} function exposes its {2} return type ''{1}''",
                TO_STRING,
                DECLARATION_NAME,
                TO_STRING
            )
            map.put(EXPOSED_RECEIVER_TYPE, "{0} member exposes its {2} receiver type ''{1}''", TO_STRING, DECLARATION_NAME, TO_STRING)
            map.put(EXPOSED_PROPERTY_TYPE, "{0} property exposes its {2} type ''{1}''", TO_STRING, DECLARATION_NAME, TO_STRING)
            map.put(EXPOSED_PARAMETER_TYPE, "{0} function exposes its {2} parameter type ''{1}''", TO_STRING, DECLARATION_NAME, TO_STRING)
            map.put(EXPOSED_SUPER_INTERFACE, "{0} sub-interface exposes its {2} supertype ''{1}''", TO_STRING, DECLARATION_NAME, TO_STRING)
            map.put(EXPOSED_SUPER_CLASS, "{0} subclass exposes its {2} supertype ''{1}''", TO_STRING, DECLARATION_NAME, TO_STRING)
            map.put(
                EXPOSED_TYPE_PARAMETER_BOUND,
                "{0} generic exposes its {2} parameter bound type ''{1}''",
                TO_STRING,
                DECLARATION_NAME,
                TO_STRING
            )

            // Modifiers
            map.put(INAPPLICABLE_INFIX_MODIFIER, "''infix'' modifier is inapplicable on this function: {0}", TO_STRING)
            map.put(REPEATED_MODIFIER, "Repeated ''{0}''", TO_STRING)
            map.put(REDUNDANT_MODIFIER, "Modifier ''{0}'' is redundant because ''{1}'' is present", TO_STRING, TO_STRING)
            map.put(DEPRECATED_MODIFIER_PAIR, "Modifier ''{0}'' is deprecated in presence of ''{1}''", TO_STRING, TO_STRING)
            map.put(INCOMPATIBLE_MODIFIERS, "Modifier ''{0}'' is incompatible with ''{1}''", TO_STRING, TO_STRING)

            // Applicability
            map.put(NONE_APPLICABLE, "None of the following functions are applicable: {0}", SYMBOLS)
            map.put(INAPPLICABLE_CANDIDATE, "Inapplicable candidate(s): {0}", SYMBOL)

            // Ambiguity
            map.put(AMBIGUITY, "Ambiguity between candidates: {0}", SYMBOLS)
            map.put(ASSIGN_OPERATOR_AMBIGUITY, "Ambiguity between assign operator candidates: {0}", SYMBOLS)

            // Types & type parameters
            map.put(TYPE_MISMATCH, "Type mismatch: inferred type is {1} but {0} was expected", TO_STRING, TO_STRING)
            map.put(RECURSION_IN_IMPLICIT_TYPES, "Recursion in implicit types")
            map.put(INFERENCE_ERROR, "Inference error")
            map.put(PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT, "Projections are not allowed on type arguments of functions and properties")
            map.put(UPPER_BOUND_VIOLATED, "Type argument is not within its bounds: should be subtype of ''{0}''", TO_STRING, TO_STRING)
            map.put(TYPE_ARGUMENTS_NOT_ALLOWED, "Type arguments are not allowed for type parameters") // *
            map.put(
                WRONG_NUMBER_OF_TYPE_ARGUMENTS,
                "{0,choice,0#No type arguments|1#One type argument|1<{0,number,integer} type arguments} expected for {1}",
                null,
                TO_STRING
            )
            map.put(NO_TYPE_FOR_TYPE_PARAMETER, "There're no types suitable for this type parameter") // &
            map.put(TYPE_PARAMETERS_IN_OBJECT, "Type parameters are not allowed for objects")
//            map.put(ILLEGAL_PROJECTION_USAGE, ...) // &
            map.put(TYPE_PARAMETERS_IN_ENUM, "Enum class cannot have type parameters")

            // Redeclarations
            map.put(MANY_COMPANION_OBJECTS, "Only one companion object is allowed per class")
            map.put(CONFLICTING_OVERLOADS, "Conflicting overloads: {0}", TO_STRING) // *
            map.put(REDECLARATION, "Conflicting declarations: {0}", TO_STRING) // *
            map.put(ANY_METHOD_IMPLEMENTED_IN_INTERFACE, "An interface may not implement a method of 'Any'") // &

            // Invalid local declarations
            map.put(
                LOCAL_OBJECT_NOT_ALLOWED,
                "Named object ''{0}'' is a singleton and cannot be local. Try to use anonymous object instead",
                TO_STRING
            ) // +
            map.put(
                LOCAL_INTERFACE_NOT_ALLOWED,
                "''{0}'' is an interface so it cannot be local. Try to use anonymous object or abstract class instead",
                TO_STRING
            )

            // Control flow diagnostics
            map.put(UNINITIALIZED_VARIABLE, "{0} must be initialized before access", PROPERTY_NAME)
            map.put(
                WRONG_INVOCATION_KIND,
                "{2} wrong invocation kind: given {3} case, but {4} case is possible",
                SYMBOL,
                TO_STRING,
                TO_STRING
            )
            map.put(LEAKED_IN_PLACE_LAMBDA, "Leaked in-place lambda: {2}", SYMBOL)
            map.put(FirErrors.WRONG_IMPLIES_CONDITION, "Wrong implies condition")
            map.put(MISSING_COEFFECT_CONTEXT, "Missing coeffect context")
            map.put(UNEXPECTED_COEFFECT_CONTEXT, "Unexpected coeffect context")
            map.put(UNCHECKED_EXCEPTION, "Unchecked exception: {2}", TO_STRING)
            map.put(
                PROPERTY_INITIALIZATION_REQUIRED,
                "Property '{2}::{3}' needs to be initialized: {4}'", SYMBOL, SYMBOL, TO_STRING
            )
            map.put(
                FUNCTION_INVOCATION_REQUIRED,
                "Function '{2}::{3}' needs to be invoked: {4}'", SYMBOL, SYMBOL, TO_STRING
            )
            map.put(
                UNPROVIDED_SAFE_BUILDER_INITIALIZATION,
                "Property '{3}::{4}' is a member of safe builder. Provide its initialization using contract or close safe builder",
                SYMBOL, SYMBOL
            )
            map.put(
                UNPROVIDED_SAFE_BUILDER_INVOCATION,
                "Function '{3}::{4}' is a member of safe builder. Provide its invocation using contract or close safe builder",
                SYMBOL, SYMBOL
            )
            map.put(NOT_A_SAFE_BUILDER_MEMBER, "'{3}::{4}' is not a member of safe builder", SYMBOL, SYMBOL)
            map.put(
                UNDEFINED_RESOURCE_STATE,
                "Undefined {2} resource state",
                SYMBOL,
            )
            map.put(
                RESOURCE_SHOULD_BE_OPEN,
                "Resource {2} shold be open",
                SYMBOL,
            )
            map.put(
                RESOURCE_SHOULD_BE_CLOSED,
                "Resource {2} shold be closed",
                SYMBOL,
            )

            // Extended checkers group
//            map.put(REDUNDANT_VISIBILITY_MODIFIER, ...) // &
//            map.put(REDUNDANT_MODALITY_MODIFIER, ...) // &
//            map.put(REDUNDANT_RETURN_UNIT_TYPE, ...) // &
//            map.put(REDUNDANT_EXPLICIT_TYPE, ...) // &
//            map.put(REDUNDANT_SINGLE_EXPRESSION_STRING_TEMPLATE, ...) // &
//            map.put(CAN_BE_VAL, ...) // &
//            map.put(CAN_BE_REPLACED_WITH_OPERATOR_ASSIGNMENT, ...) // &
//            map.put(REDUNDANT_CALL_OF_CONVERSION_METHOD, ...) // &
//            map.put(ARRAY_EQUALITY_OPERATOR_CAN_BE_REPLACED_WITH_EQUALS, ...) // &
//            map.put(EMPTY_RANGE, ...) // &
//            map.put(REDUNDANT_SETTER_PARAMETER_TYPE, ...) // &
        }
    }
}
