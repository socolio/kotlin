/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#ifndef RUNTIME_MM_THREAD_SUSPENSION_UTILS_H
#define RUNTIME_MM_THREAD_SUSPENSION_UTILS_H

namespace kotlin {
namespace mm {

bool IsThreadSuspensionRequested();
void SuspendCurrentThreadIfRequested();

/**
 * Suspends all threads registered in ThreadRegistry except threads that are in the Native state.
 * Blocks until all such threads are suspended. Threads that are in the Native state on the moment
 * of this call will be suspended on exit from the Native state.
 */
void SuspendThreads();

/**
 * Resumes all threads registered in ThreadRegistry that were suspended by the SuspendThreads call.
 * Blocks until all such threads are resumed.
 */
void ResumeThreads();

} // namespace mm
} // namespace kotlin

#endif // RUNTIME_MM_THREAD_SUSPENSION_UTILS_H
