package org.usvm.samples.strings11

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.usvm.PathSelectionStrategy
import org.usvm.samples.JavaMethodTestRunner
import org.usvm.samples.approximations.ApproximationsTestRunner
import org.usvm.test.util.checkers.eq
import org.usvm.test.util.checkers.ignoreNumberOfAnalysisResults
import org.usvm.util.isException
import kotlin.time.Duration

class StringConcatApproximationsTest : ApproximationsTestRunner() {

    init {
        options = options.copy(stepsFromLastCovered = null, timeout = Duration.INFINITE, pathSelectionStrategies = listOf(PathSelectionStrategy.DFS))
    }

    @Test
    fun testConcatArguments() {
        checkDiscoveredProperties(
            StringConcat::checkStringBuilder,
            ignoreNumberOfAnalysisResults,
            { _, _, _, _ -> true }
        )
    }
}
