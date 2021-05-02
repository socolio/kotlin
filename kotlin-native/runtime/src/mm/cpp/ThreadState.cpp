/*
 * Copyright 2010-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "MemoryPrivate.hpp"
#include "ThreadData.hpp"
#include "ThreadState.hpp"
#include "ThreadSuspensionUtils.hpp"

namespace {

ALWAYS_INLINE bool isStateSwitchAllowed(ThreadState oldState, ThreadState newState) noexcept {
    // TODO: May be forbid SUSPEND -> Native switch?
    return oldState != newState;
}

const char* stateToString(ThreadState state) noexcept {
    switch (state) {
        case ThreadState::kRunnable:
            return "RUNNABLE";
        case ThreadState::kNative:
            return "NATIVE";
        case ThreadState::kSuspended:
            return "SUSPENDED";
    }
}

std::string statesToString(std::initializer_list<ThreadState> states) noexcept {
    std::string result = "{ ";
    for (size_t i = 0; i < states.size(); i++) {
        if (i != 0) {
            result += ", ";
        }
        result += stateToString(data(states)[i]);
    }
    result += " }";
    return result;
}

} // namespace

// Switches the state of the current thread to `newState` and returns the previous state.
ALWAYS_INLINE ThreadState kotlin::SwitchThreadState(mm::ThreadData* threadData, ThreadState newState) noexcept {
    // TODO: This change means that state switch is not atomic. Is it ok?
    auto oldState = threadData->state();
    // TODO(perf): Mesaure the impact of this assert in debug and opt modes.
    RuntimeAssert(isStateSwitchAllowed(oldState, newState),
                  "Illegal thread state switch. Old state: %s. New state: %s.",
                  stateToString(oldState), stateToString(newState));
    if (oldState == ThreadState::kNative && newState == ThreadState::kRunnable){
        mm::SuspendCurrentThreadIfRequested();
    }
    threadData->setState(newState);
    return oldState;
}

ALWAYS_INLINE ThreadState kotlin::SwitchThreadState(MemoryState* thread, ThreadState newState) noexcept {
    return SwitchThreadState(thread->GetThreadData(), newState);
}

ALWAYS_INLINE void kotlin::AssertThreadState(mm::ThreadData* threadData, ThreadState expected) noexcept {
    auto actual = threadData->state();
    RuntimeAssert(actual == expected,
                  "Unexpected thread state. Expected: %s. Actual: %s.",
                  stateToString(expected), stateToString(actual));
}

ALWAYS_INLINE void kotlin::AssertThreadState(MemoryState* thread, ThreadState expected) noexcept {
    AssertThreadState(thread->GetThreadData(), expected);
}

ALWAYS_INLINE void kotlin::AssertThreadState(mm::ThreadData* threadData, std::initializer_list<ThreadState> expected) noexcept {
    auto actual = threadData->state();
    bool expectedContainsActual = false;
    for (auto state : expected) {
        if (state == actual) {
            expectedContainsActual = true;
            break;
        }
    }
    RuntimeAssert(expectedContainsActual,
                  "Unexpected thread state. Expected one of: %s. Actual: %s",
                  statesToString(expected).c_str(), stateToString(actual));
}

ALWAYS_INLINE void kotlin::AssertThreadState(MemoryState* thread, std::initializer_list<ThreadState> expected) noexcept {
    AssertThreadState(thread->GetThreadData(), expected);
}