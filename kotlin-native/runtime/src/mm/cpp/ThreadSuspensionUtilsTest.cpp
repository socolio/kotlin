/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "MemoryPrivate.hpp"
#include "ThreadSuspensionUtils.hpp"

#include <gtest/gtest.h>
#include <gmock/gmock.h>

#include <thread>
#include <TestSupport.hpp>

#include <iostream>

using namespace kotlin;

namespace {

constexpr size_t kDefaultIterations = 100;

void joinAll(KStdVector<std::thread>& threads) {
    for (auto& thread : threads) {
        thread.join();
    }
}

KStdVector<mm::ThreadData*> collectThreadData() {
    KStdVector<mm::ThreadData*> result;
    auto iter = mm::ThreadRegistry::Instance().Iter();
    for (auto& thread : iter) {
        result.push_back(&thread);
    }
    return result;
}

KStdVector<ThreadState> collectThreadStates() {
    KStdVector<ThreadState> result;
    auto threadData = collectThreadData();
    std::transform(threadData.begin(), threadData.end(), std::back_inserter(result),
                   [](mm::ThreadData* threadData) { return threadData->state(); });
    return result;
}

void reportProgress(size_t currentIteration, size_t totalIterations) {
    if (currentIteration % 50 == 0) {
       std::cout << "Iteration: " << currentIteration << " of " << totalIterations << std::endl;
    }
}

} // namespace

namespace kotlin {

// TODO: Move to thread state header.
std::ostream& operator<<(std::ostream& stream, ThreadState state) {
    switch (state) {
        case ThreadState::kRunnable:
            stream << "Runnable";
            break;
        case ThreadState::kNative:
            stream << "Native";
            break;
        case ThreadState::kSuspended:
            stream << "Suspended";
            break;
    }
    return stream;
}

}

TEST(ThreadSuspensionTest, SimpleStartStop) {
    constexpr size_t kThreadCount = kDefaultThreadCount;
    constexpr size_t kIterations = 10;
    KStdVector<std::thread> threads;
    std::array<std::atomic<bool>, kThreadCount> ready{false};
    std::atomic<bool> canStart(false);
    std::atomic<bool> shouldStop(false);
    ASSERT_THAT(collectThreadData(), testing::IsEmpty());

    for (size_t i = 0; i < kThreadCount; i++) {
        threads.emplace_back([&canStart, &shouldStop, &ready, i]() {
            ScopedRuntimeInit init;
            auto* threadData = init.memoryState()->GetThreadData();
            EXPECT_EQ(mm::IsThreadSuspensionRequested(), false);

            while(!shouldStop) {
                ready[i] = true;
                while(!canStart) {
                    std::this_thread::yield();
                }
                ready[i] = false;

                EXPECT_EQ(threadData->state(), ThreadState::kRunnable);
                mm::SuspendCurrentThreadIfRequested();
                EXPECT_EQ(threadData->state(), ThreadState::kRunnable);
           }
        });
    }

    for (size_t i = 0; i < kIterations; i++) {
        reportProgress(i, kIterations);

        while (!std::all_of(ready.begin(), ready.end(), [](bool it) { return it; })) {
            std::this_thread::yield();
        }
        canStart = true;

        mm::SuspendThreads();
        auto threadStates = collectThreadStates();
        EXPECT_THAT(threadStates, testing::Each(ThreadState::kSuspended));
        EXPECT_EQ(mm::IsThreadSuspensionRequested(), true);

        mm::ResumeThreads();
        threadStates = collectThreadStates();
        EXPECT_THAT(threadStates, testing::Each(ThreadState::kRunnable));
        EXPECT_EQ(mm::IsThreadSuspensionRequested(), false);

        // Sync for the next iteration.
        canStart = false;
    }

    canStart = true;
    shouldStop = true;
    joinAll(threads);
}


TEST(ThreadSuspensionTest, SwitchStateToNative) {
    constexpr size_t kThreadCount = kDefaultThreadCount;
    constexpr size_t kIterations = kDefaultIterations;
    KStdVector<std::thread> threads;
    std::array<std::atomic<bool>, kThreadCount> ready{false};
    std::atomic<bool> canStart(false);
    std::atomic<bool> shouldStop(false);
    ASSERT_THAT(collectThreadData(), testing::IsEmpty());

    for (size_t i = 0; i < kThreadCount; i++) {
        threads.emplace_back([&canStart, &shouldStop, &ready, i]() {
            ScopedRuntimeInit init;
            auto* threadData = init.memoryState()->GetThreadData();
            EXPECT_EQ(mm::IsThreadSuspensionRequested(), false);

            while(!shouldStop) {
                ready[i] = true;
                while(!canStart) {
                    std::this_thread::yield();
                }
                ready[i] = false;

                EXPECT_EQ(threadData->state(), ThreadState::kRunnable);
                SwitchThreadState(threadData, ThreadState::kNative);
                EXPECT_EQ(threadData->state(), ThreadState::kNative);
                SwitchThreadState(threadData, ThreadState::kRunnable);
                EXPECT_EQ(threadData->state(), ThreadState::kRunnable);
            }
        });
    }

    for (size_t i = 0; i < kIterations; i++) {
        reportProgress(i, kIterations);

        while (!std::all_of(ready.begin(), ready.end(), [](bool it) { return it; })) {
            std::this_thread::yield();
        }
        canStart = true;

        mm::SuspendThreads();
        auto threadStates = collectThreadStates();
        EXPECT_THAT(threadStates, testing::Each(testing::AnyOf(ThreadState::kSuspended, ThreadState::kNative)));
        EXPECT_EQ(mm::IsThreadSuspensionRequested(), true);

        mm::ResumeThreads();
        threadStates = collectThreadStates();
        EXPECT_THAT(threadStates, testing::Each(testing::AnyOf(ThreadState::kRunnable, ThreadState::kNative)));
        EXPECT_EQ(mm::IsThreadSuspensionRequested(), false);

        // Sync for the next iteration.
        canStart = false;
    }

    canStart = true;
    shouldStop = true;
    joinAll(threads);
}